#!/usr/bin/env python3
"""Run D-033 engine mutation fixtures through every real consumer.

The production checkout is never modified.  Each variant is applied to an
isolated temporary copy and the same Gradle/CLI commands used by guard are
run there.  A passing negative-control means every consumer rejected the
mutated engine (non-zero exit), while the command output is retained as the
auditable evidence of the expected failure.
"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import tempfile
from pathlib import Path


ENGINE = Path("core-guide/src/main/kotlin/com/trailnav/core/Engine.kt")
VARIANTS = {
    "dwell-bypass": (
        "elapsedSeconds(timestamp, since) >= config.offRouteEnterDwellSeconds",
        "true /* D-033 dwell bypass */",
    ),
    "config-constant": (
        "match.distanceMeters > config.offRouteEnterDistMeters",
        "match.distanceMeters > 25.0 /* D-033 config constant */",
    ),
    "unit-heuristic": (
        "return (now - then) / 1_000.0",
        "val delta = now - then\n    return if (delta >= 1_000L) delta / 1_000.0 else delta.toDouble() /* D-033 unit heuristic */",
    ),
}


def run(command: list[str], cwd: Path) -> tuple[int, str]:
    result = subprocess.run(command, cwd=cwd, text=True, encoding="utf-8", errors="replace", capture_output=True)
    return result.returncode, (result.stdout + "\n" + result.stderr).strip()


def copy_repo(source: Path, destination: Path) -> None:
    ignored = shutil.ignore_patterns(".git", ".gradle", "build", "*.jar", "*.log", ".task005-evidence")
    shutil.copytree(source, destination, ignore=ignored)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path, default=Path("."))
    parser.add_argument("--gradle", default="gradle")
    parser.add_argument("--out", type=Path, default=Path("d033-negative-control.log"))
    args = parser.parse_args()
    source = args.repo_root.resolve()
    evidence: list[str] = []
    failures: list[str] = []
    with tempfile.TemporaryDirectory(prefix="tnapp-d033-") as temporary:
        workspace = Path(temporary) / "repo"
        copy_repo(source, workspace)
        engine = workspace / ENGINE
        original = engine.read_text(encoding="utf-8")
        for name, (needle, replacement) in VARIANTS.items():
            variant_engine = original.replace(needle, replacement, 1)
            if variant_engine == original:
                failures.append(f"{name}: mutation needle not found")
                continue
            engine.write_text(variant_engine, encoding="utf-8")
            test_code, test_output = run([args.gradle, ":core-guide:test", "--no-daemon"], workspace)
            evidence.append(f"VARIANT {name} consumer=core-guide-test exit={test_code}\n{test_output}")
            if test_code == 0:
                failures.append(f"{name}: core-guide-test unexpectedly passed")

            # A failing test task prevents Gradle from reaching installDist in
            # the same invocation. Build the real CLI separately so each
            # downstream consumer is exercised against the mutated engine.
            cli_code, cli_output = run([args.gradle, ":replay:installDist", "--no-daemon"], workspace)
            evidence.append(f"VARIANT {name} consumer=replay-build exit={cli_code}\n{cli_output}")
            if cli_code != 0:
                failures.append(f"{name}: replay CLI build failed")
            cli = workspace / "replay/build/install/replay/bin/replay"
            if not cli.exists():
                evidence.append(f"VARIANT {name} consumer=replay exit=not-built")
                failures.append(f"{name}: replay CLI was not built")
                failures.append(f"{name}: phase1-accuracy CLI was not built")
                failures.append(f"{name}: config-sensitivity CLI was not built")
                engine.write_text(original, encoding="utf-8")
                continue
            commands = [
                (
                    "replay",
                    ["python", "tools/replay.py", "--cli", str(cli), "--contract"],
                ),
                (
                    "phase1-accuracy",
                    ["python", "tools/phase1_accuracy.py", "--cli", str(cli), "--contract", "--run-id", "d033", "--commit-sha", "fixture"],
                ),
                (
                    "config-sensitivity",
                    ["python", "tools/config_sensitivity.py", "--cli", str(cli)],
                ),
            ]
            for consumer, command in commands:
                code, output = run(command, workspace)
                evidence.append(f"VARIANT {name} consumer={consumer} exit={code}\n{output}")
                if code == 0:
                    failures.append(f"{name}: {consumer} unexpectedly passed")
            engine.write_text(original, encoding="utf-8")

        fixture = workspace / "testdata/sessions/golden/log_shape_fixture"
        if fixture.is_dir():
            broken_fixture = workspace / "d033-replay-missing-loc"
            shutil.copytree(fixture, broken_fixture)
            event_lines = (broken_fixture / "events.ndjson").read_text(encoding="utf-8").splitlines()
            removed = False
            retained: list[str] = []
            for line in event_lines:
                event = json.loads(line)
                if not removed and event.get("stream") == "loc":
                    removed = True
                    continue
                retained.append(line)
            (broken_fixture / "events.ndjson").write_text("\n".join(retained) + "\n", encoding="utf-8")
            pairing_code, pairing_output = run(
                ["python", "tools/replay.py", str(broken_fixture), "--cli", str(cli), "--strict"],
                workspace,
            )
            evidence.append(f"REPLAY missing-loc consumer=replay exit={pairing_code}\n{pairing_output}")
            if pairing_code == 0:
                failures.append("replay: missing-loc mutation unexpectedly passed")
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text("\n\n".join(evidence) + "\n", encoding="utf-8")
    if failures:
        print("FAIL: D-033 negative control")
        print("\n".join(failures))
        return 1
    print(f"PASS: D-033 consumers rejected {len(VARIANTS)} engine mutations")
    print(f"evidence={args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
