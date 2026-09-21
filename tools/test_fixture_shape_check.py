#!/usr/bin/env python3
"""Negative controls for every fixture_shape_check.py item."""
from __future__ import annotations

import copy
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from fixture_shape_check import SHAPE_CHECKS


ROOT = Path(__file__).resolve().parents[1]
CHECKER = ROOT / "tools" / "fixture_shape_check.py"
FIXTURE = ROOT / "testdata" / "sessions" / "golden" / "log_shape_fixture"


def load_events() -> list[dict]:
    return [json.loads(line) for line in (FIXTURE / "events.ndjson").read_text(encoding="utf-8").splitlines() if line.strip()]


def run(events: list[dict], expected: int) -> bool:
    temp = Path(tempfile.mkdtemp(prefix=".fixture-shape-negative-", dir=ROOT))
    try:
        (temp / "events.ndjson").write_text(
            "\n".join(json.dumps(event, ensure_ascii=False) for event in events) + "\n",
            encoding="utf-8",
        )
        result = subprocess.run([sys.executable, str(CHECKER), str(temp)], capture_output=True, text=True, check=False)
        return result.returncode == expected
    finally:
        shutil.rmtree(temp, ignore_errors=True)


def with_rules(events: list[dict], rule: str) -> list[dict]:
    mutated = copy.deepcopy(events)
    entered = False
    for event in mutated:
        if event.get("stream") != "guide":
            continue
        current = event.get("reason", {}).get("rule", "")
        if current == "off-route.enter":
            entered = True
            continue
        if entered and current and not current.startswith("off-route."):
            event.setdefault("reason", {})["rule"] = rule
    return mutated


def main() -> int:
    events = load_events()
    variants: list[tuple[str, list[dict], int]] = []

    missing_guide = copy.deepcopy(events)
    missing_guide.pop(next(index for index, event in enumerate(missing_guide) if event.get("stream") == "guide"))
    variants.append(("loc_eq_guide", missing_guide, 1))

    global_monotonic = copy.deepcopy(events)
    envelope_index = 0
    for event in global_monotonic:
        if event.get("stream") == "envelope":
            event["t"] = float(envelope_index)
            envelope_index += 1
    variants.append(("global_envelope_regression", global_monotonic, 1))

    keyed_regression = copy.deepcopy(events)
    envelope_positions = [index for index, event in enumerate(keyed_regression) if event.get("stream") == "envelope"]
    first, second = envelope_positions[:2]
    keyed_regression[second]["t"] = float(keyed_regression[first]["t"]) - 1.0
    variants.append(("envelope_key_monotonic", keyed_regression, 1))

    no_subsecond = copy.deepcopy(events)
    loc_index = 0
    for event in no_subsecond:
        if event.get("stream") == "loc":
            event["t"] = float(loc_index)
            loc_index += 1
    variants.append(("subsecond_20pct", no_subsecond, 1))

    no_orientation = [event for event in copy.deepcopy(events) if not (event.get("stream") == "sys" and event.get("kind") == "route.orientation")]
    variants.append(("route_orientation", no_orientation, 1))

    no_enter = copy.deepcopy(events)
    for event in no_enter:
        if event.get("stream") == "guide" and event.get("reason", {}).get("rule") == "off-route.enter":
            event["reason"]["rule"] = "matching.on-route"
    variants.append(("off_route_enter", no_enter, 1))

    no_recovery = with_rules(events, "off-route.holding")
    variants.append(("recovery_cut", no_recovery, 1))

    voice_only = with_rules(events, "off-route.holding")
    voice_only.append({"seq": 999999, "t": 0.0, "stream": "sys", "kind": "voice.recovered", "battery_pct": None, "details": {}})
    variants.append(("recovery_voice_only", voice_only, 1))
    variants.append(("recovery_without_voice", copy.deepcopy(events), 0))

    failures = sum(not run(mutated, expected) for _, mutated, expected in variants)
    print(f"fixture_shape_check checks={len(SHAPE_CHECKS)} cases={len(variants)} failures={failures}")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
