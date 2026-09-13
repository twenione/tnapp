#!/usr/bin/env python3
"""Replay the deterministic Phase 0 stub engine against a session."""
from __future__ import annotations

import json
import sys
from pathlib import Path


def replay(root: Path, strict: bool) -> int:
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    seed = manifest["engine"]["rng_seed"]
    loc_count = 0
    comparisons = 0
    mismatches: list[str] = []
    for line_number, raw in enumerate((root / "events.ndjson").read_text(encoding="utf-8").splitlines(), 1):
        if not raw.strip():
            continue
        event = json.loads(raw)
        if event.get("stream") == "loc":
            loc_count += 1
        if event.get("stream") != "guide":
            continue
        comparisons += 1
        expected = {"decision": "CONTINUE", "frame_count": loc_count, "rng_seed": seed}
        recorded = {"decision": event.get("decision"), "frame_count": event.get("inputs", {}).get("frame_count"), "rng_seed": event.get("inputs", {}).get("rng_seed", seed)}
        status = "MATCH" if expected == recorded else "MISMATCH"
        print(f"guide seq={event.get('seq')} {status} expected={json.dumps(expected, sort_keys=True)} recorded={json.dumps(recorded, sort_keys=True)}")
        if status == "MISMATCH":
            mismatches.append(f"line {line_number} seq {event.get('seq')}")
    print(f"RESULT comparisons={comparisons} mismatches={len(mismatches)} strict={strict}")
    if mismatches and strict:
        print("FAIL: " + ", ".join(mismatches))
        return 1
    if mismatches:
        print("WARN: mismatches ignored without --strict")
    return 0


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print("usage: replay.py <session-dir> [--strict]", file=sys.stderr)
        return 2
    root = Path(argv[1])
    if not root.is_dir():
        print(f"ERROR: session directory missing: {root}", file=sys.stderr)
        return 2
    try:
        return replay(root, "--strict" in argv[2:])
    except (OSError, KeyError, json.JSONDecodeError) as exc:
        print(f"ERROR: replay failed: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))

