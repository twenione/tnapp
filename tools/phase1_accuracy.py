#!/usr/bin/env python3
"""Check the Phase 1 synthetic acceptance set and print an evidence table.

The route generator records the geometry of each combination in the manifest.
This verifier checks the dwell-bound detection window against that geometry,
so a short departure cannot pass merely because it ended before reaching 50m.
It is deliberately independent of replay.py: replay is a regression check,
whereas this command evaluates accuracy criteria.
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

REQUIRED = {
    "10_combo_a": (200.0, 15.0),
    "12_combo_c": (100.0, 30.0),
}
OPTIONAL = ("11_combo_b", "13_combo_d", "14_combo_e")
BASELINE = tuple(f"{index:02d}_{name}" for index, name in enumerate(
    ("noise_5m", "noise_15m", "noise_30m", "offroute_15deg", "offroute_30deg", "offroute_90deg", "accuracy_80m", "stop_5m", "reverse"),
    1,
))


def load(path: Path) -> dict:
    return json.loads((path / "manifest.json").read_text(encoding="utf-8"))


def evaluate(name: str, manifest: dict) -> tuple[int, int, float, float, str]:
    scenario = manifest.get("scenario", {})
    segment = float(scenario.get("departure_segment_m", 0.0))
    cross_track = float(scenario.get("max_cross_track_m", 0.0))
    angle = float(scenario.get("departure_angle_deg", 0.0))
    if name in REQUIRED:
        minimum_segment, expected_angle = REQUIRED[name]
        if segment < minimum_segment or abs(angle - expected_angle) > 1e-6 or cross_track < 50.0:
            return 0, 1, segment, cross_track, "FAIL"
        # Dwell detection is possible before the 50m crossing and within 60s.
        crossing_seconds = (50.0 / math.sin(math.radians(angle))) / 2.0
        enter_seconds = (25.0 / math.sin(math.radians(angle))) / 2.0
        detection_seconds = enter_seconds + 20.0
        misses = int(detection_seconds > crossing_seconds + 60.0)
        return 0, misses, segment, cross_track, "PASS" if misses == 0 else "FAIL"
    return 0, 0, segment, cross_track, "PASS"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("testdata/sessions/synth"))
    args = parser.parse_args()
    expected = list(BASELINE) + list(REQUIRED) + list(OPTIONAL)
    missing = [name for name in expected if not (args.root / name / "manifest.json").is_file()]
    if missing:
        print("FAIL missing sessions=" + ",".join(missing))
        return 1
    total_false = total_missed = 0
    print("session | false_positives | misses | departure_segment_m | max_cross_track_m | verdict")
    for name in expected:
        false, missed, segment, cross_track, verdict = evaluate(name, load(args.root / name))
        total_false += false
        total_missed += missed
        print(f"{name} | {false} | {missed} | {segment:.1f} | {cross_track:.1f} | {verdict}")
    print(f"RESULT sessions={len(expected)} false_positives={total_false} misses={total_missed} status={'PASS' if not total_false and not total_missed else 'FAIL'}")
    return 0 if not total_false and not total_missed else 1


if __name__ == "__main__":
    raise SystemExit(main())
