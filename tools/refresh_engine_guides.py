#!/usr/bin/env python3
"""Rewrite recorded guide events with decisions from the compiled engine."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from replay import invoke, resolve_cli


def rewrite_events(raw_lines: list[str], trace: dict[int, dict]) -> tuple[list[str], int]:
    """Rewrite frame guide decisions while preserving every other event field."""
    lines: list[str] = []
    changed = 0
    for raw in raw_lines:
        if not raw.strip():
            continue
        event = json.loads(raw)
        if event.get("stream") == "guide":
            if event.get("trigger") is not None:
                lines.append(json.dumps(event, sort_keys=True))
                continue
            item = trace.get(int(event.get("seq", -1)))
            if item is None:
                raise ValueError(f"no engine result for guide seq {event.get('seq')}")
            event["decision"] = item["decision"]
            old_reason = event.get("reason") if isinstance(event.get("reason"), dict) else {}
            event["reason"] = {
                **old_reason,
                "rule": item.get("reason_rule", ""),
                "details": item.get("reason_details", old_reason.get("details", {})),
            }
            changed += 1
        lines.append(json.dumps(event, sort_keys=True))
    return lines, changed


def refresh_session(session: Path, cli: Path) -> int:
    trace = {int(item["seq"]): item for item in invoke(cli, session) if item.get("kind") == "guide"}
    lines, changed = rewrite_events((session / "events.ndjson").read_text(encoding="utf-8").splitlines(), trace)
    (session / "events.ndjson").write_text("\n".join(lines) + "\n", encoding="utf-8")
    return changed


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("testdata/sessions"))
    parser.add_argument("--cli", required=True)
    args = parser.parse_args()
    cli = resolve_cli(args.cli)
    sessions = sorted(path for path in (args.root / "golden").glob("*") if path.is_dir())
    sessions += sorted(path for path in (args.root / "synth").glob("*") if path.is_dir())
    total = 0
    for session in sessions:
        count = refresh_session(session, cli)
        total += count
        print(f"{session}: refreshed={count}")
    print(f"RESULT sessions={len(sessions)} guide_events={total} engine_cli={cli}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
