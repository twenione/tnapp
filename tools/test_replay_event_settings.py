#!/usr/bin/env python3
"""Exercise replay's manifest event settings through the compiled engine CLI."""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from replay import invoke
from session_events import event_config_overrides, manifest_events


SESSION = Path("testdata/sessions/synth/15_events_on")
TOOLS = Path("tools")


def decisions(trace: list[dict]) -> set[str]:
    return {
        item.get("reason_details", {}).get("event", "")
        for item in trace
        if item.get("kind") == "frame"
    }


def run_replay(session: Path, cli: Path, extra: list[str] | None = None) -> subprocess.CompletedProcess[str]:
    command = [sys.executable, str(TOOLS / "replay.py"), str(session), "--cli", str(cli), "--strict", *(extra or [])]
    return subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace")


def test_manifest_events_drive_actual_engine(cli: Path) -> None:
    manifest = json.loads((SESSION / "manifest.json").read_text(encoding="utf-8"))
    present, events, error = manifest_events(manifest)
    assert present and error is None and events is not None
    override = event_config_overrides(events)

    # Direct CLI invocation without the session manifest retains engine defaults.
    defaults = decisions(invoke(cli, SESSION, every_frame=True))
    assert not {"E3", "E4", "E6"} & defaults, f"default config unexpectedly emitted acceptance events: {defaults}"

    configured = decisions(invoke(cli, SESSION, every_frame=True, overrides=override))
    assert {"E3", "E4", "E6"} <= configured, f"compiled engine did not emit all required events: {configured}"

    replayed = run_replay(SESSION, cli)
    combined = replayed.stdout + replayed.stderr
    assert replayed.returncode == 0, combined
    assert "mismatches=0" in combined and "events=applied" in combined, combined

    # One user override wins over the manifest and suppresses only E4 among
    # the required E3/E4/E6 event families.
    overridden = run_replay(SESSION, cli, ["--config", "slopeEnabled=0"])
    override_output = overridden.stdout + overridden.stderr
    assert overridden.returncode == 1, "the intentionally divergent recorded E4 guides should be reported"
    assert "reason_rule=event.slope" not in override_output, override_output
    assert "reason_rule=event.remaining" in override_output, override_output
    assert "reason_rule=event.waypoint" in override_output, override_output


def test_invalid_manifest_events_fail_closed(cli: Path) -> None:
    with tempfile.TemporaryDirectory(prefix="tnapp-events-invalid-") as temporary:
        copied = Path(temporary) / "session"
        shutil.copytree(SESSION, copied)
        path = copied / "manifest.json"
        manifest = json.loads(path.read_text(encoding="utf-8"))
        manifest["engine"]["config"]["events"]["E3"] = "true"
        path.write_text(json.dumps(manifest, separators=(",", ":")), encoding="utf-8")
        result = run_replay(copied, cli)
        combined = result.stdout + result.stderr
        assert result.returncode == 1
        assert combined.startswith("FAIL: manifest engine.config.events invalid: non-boolean E3"), combined


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cli", type=Path, required=True)
    args = parser.parse_args()
    test_manifest_events_drive_actual_engine(args.cli)
    test_invalid_manifest_events_fail_closed(args.cli)
    print("PASS: manifest E3/E4/E6 settings replay through compiled engine, overrides win, invalid config fails closed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
