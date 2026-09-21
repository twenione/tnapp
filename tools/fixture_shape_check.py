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


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("session", type=Path)
    args = parser.parse_args(argv[1:])
    events = [json.loads(line) for line in (args.session / "events.ndjson").read_text(encoding="utf-8").splitlines() if line.strip()]
    loc = [event for event in events if event.get("stream") == "loc"]
    guide = [event for event in events if event.get("stream") == "guide"]
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
    recovered = any(event.get("stream") == "sys" and event.get("kind") == "voice.recovered" for event in events)
    entered = any(event.get("stream") == "guide" and event.get("reason", {}).get("rule") == "off-route.enter" for event in events)
    checks = {
        "loc_eq_guide": len(loc) == len(guide) and len(loc) > 0,
        "global_envelope_regression": global_regressions > 0,
        "envelope_key_monotonic": envelope_regressions == 0,
        "subsecond_20pct": len(loc) > 1 and subsecond / (len(loc) - 1) >= 0.20,
        "route_orientation": orientation,
        "off_route_enter": entered,
        "recovery": recovered,
    }
    print(f"RESULT loc={len(loc)} guide={len(guide)} envelope={len(envelopes)} subsecond={subsecond} global_regressions={global_regressions} keyed_regressions={envelope_regressions}")
    for name, passed in checks.items():
        print(f"{name}={'PASS' if passed else 'FAIL'}")
    return 0 if all(checks.values()) else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
