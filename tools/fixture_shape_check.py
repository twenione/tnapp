#!/usr/bin/env python3
"""Check the observable shape required of the Phase 3 log fixture.

This check is intentionally about shape, not correctness: replay and the
session validator provide those separate contracts. It emits counts and
never prints source coordinates or timestamps.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from xml.etree import ElementTree as ET


SHAPE_CHECKS = (
    "loc_eq_guide",
    "global_envelope_regression",
    "envelope_key_monotonic",
    "subsecond_20pct",
    "coordinates_valid",
    "route_orientation",
    "off_route_enter",
    "recovery",
)


def _valid_coordinate(value: object, *, lower: float, upper: float) -> bool:
    if isinstance(value, bool) or value is None:
        return False
    try:
        number = float(value)
    except (TypeError, ValueError):
        return False
    return lower <= number <= upper


def _coordinates_valid(events: list[dict], route: Path | None) -> bool:
    for event in events:
        if event.get("stream") == "loc":
            if not (_valid_coordinate(event.get("lat"), lower=-90.0, upper=90.0)
                    and _valid_coordinate(event.get("lon"), lower=-180.0, upper=180.0)):
                return False
        if event.get("stream") == "guide":
            inputs = event.get("inputs")
            if isinstance(inputs, dict) and not (
                _valid_coordinate(inputs.get("lat"), lower=-90.0, upper=90.0)
                and _valid_coordinate(inputs.get("lon"), lower=-180.0, upper=180.0)
            ):
                return False
    if route is None or not route.is_file():
        return True
    try:
        root = ET.parse(route).getroot()
    except (OSError, ET.ParseError):
        return False
    for point in root.iter():
        if point.tag.rsplit("}", 1)[-1] not in {"trkpt", "rtept", "wpt"}:
            continue
        if not (
            _valid_coordinate(point.attrib.get("lat"), lower=-90.0, upper=90.0)
            and _valid_coordinate(point.attrib.get("lon"), lower=-180.0, upper=180.0)
        ):
            return False
    return True


def check_shape(events: list[dict], route: Path | None = None) -> tuple[dict[str, bool], tuple[int, int, int, int, int, int]]:
    loc = [event for event in events if event.get("stream") == "loc"]
    guide = [event for event in events if event.get("stream") == "guide" and "trigger" not in event]
    envelopes = [event for event in events if event.get("stream") == "envelope"]
    subsecond = sum(1 for previous, current in zip(loc, loc[1:]) if float(current["t"]) - float(previous["t"]) < 1.0)
    global_regressions = sum(1 for previous, current in zip(envelopes, envelopes[1:]) if float(current["t"]) < float(previous["t"]))
    by_stream: dict[str, float] = {}
    envelope_regressions = 0
    for event in envelopes:
        key = str(event.get("event_stream"))
        current = float(event["t"])
        if key in by_stream and current < by_stream[key]:
            envelope_regressions += 1
        by_stream[key] = current
    orientation = any(event.get("stream") == "sys" and event.get("kind") == "route.orientation" for event in events)
    guide_rules = [event.get("reason", {}).get("rule", "") for event in guide]
    entered_index = next((index for index, rule in enumerate(guide_rules) if rule == "off-route.enter"), None)
    recovered = False
    if entered_index is not None:
        was_off_route = False
        for rule in guide_rules[entered_index:]:
            if rule.startswith("off-route."):
                was_off_route = True
            elif was_off_route:
                recovered = True
                break
    checks = {
        "loc_eq_guide": len(loc) == len(guide) and len(loc) > 0,
        "global_envelope_regression": global_regressions > 0,
        "envelope_key_monotonic": envelope_regressions == 0,
        "subsecond_20pct": len(loc) > 1 and subsecond / (len(loc) - 1) >= 0.20,
        "coordinates_valid": _coordinates_valid(events, route),
        "route_orientation": orientation,
        "off_route_enter": entered_index is not None,
        "recovery": recovered,
    }
    metrics = (len(loc), len(guide), len(envelopes), subsecond, global_regressions, envelope_regressions)
    return checks, metrics


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("session", type=Path)
    args = parser.parse_args(argv[1:])
    events = [json.loads(line) for line in (args.session / "events.ndjson").read_text(encoding="utf-8").splitlines() if line.strip()]
    checks, metrics = check_shape(events, args.session / "route.gpx")
    loc_count, guide_count, envelope_count, subsecond, global_regressions, envelope_regressions = metrics
    print(f"RESULT loc={loc_count} guide={guide_count} envelope={envelope_count} subsecond={subsecond} global_regressions={global_regressions} keyed_regressions={envelope_regressions}")
    for name in SHAPE_CHECKS:
        passed = checks[name]
        print(f"{name}={'PASS' if passed else 'FAIL'}")
    if not all(checks.values()):
        failed = ",".join(name for name, passed in checks.items() if not passed)
        print(f"FAIL: fixture shape check failed: {failed}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
