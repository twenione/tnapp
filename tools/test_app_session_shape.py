#!/usr/bin/env python3
"""Exercise the app-shaped session contract with a frame guide plus a slot trigger."""
from __future__ import annotations

import json
import shutil
from pathlib import Path

from validate_session import validate


def manifest() -> dict:
    return {
        "session_id": "11111111-1111-4111-8111-111111111111",
        "schema_version": "0.1.0-draft",
        "started_at_wall": "2026-09-22T00:00:00Z",
        "app": {"version": "test", "code_hash": "sha256:test"},
        "engine": {"config": {"implementation": "core-guide", "config_hash": "sha256:test"}, "rng_seed": 0},
        "route": {"gpx_hash": "sha256:test", "elevation_used": False, "elevation_reason": "absent", "waypoint_count": 0},
        "clock": {"monotonic_source": "location-frame-timestamp", "timestamp_unit": "seconds", "t_order": "per-stream", "seq_order": "append"},
        "privacy": {"upload_default": False},
    }


def discarded_event() -> dict:
    return {
        "seq": 4, "t": 1.0, "stream": "sys", "kind": "event.discarded", "battery_pct": None,
        "details": {
            "event": "E4", "peak_index": "0", "peak_s": "510.0",
            "passed_projected_meters": "520.0", "delay_reason": "event-min-interval", "reason": "passed",
        },
    }


def discarded_waypoint_event() -> dict:
    return {
        "seq": 5, "t": 1.0, "stream": "sys", "kind": "event.discarded", "battery_pct": None,
        "details": {
            "event": "E6", "waypoint_index": "1", "waypoint_s": "600.0",
            "distance_meters": "45.0", "reason": "priority",
        },
    }


def assert_discard_shape(event: dict) -> None:
    details = event["details"]
    expected = {
        "E4": {"event", "peak_index", "peak_s", "passed_projected_meters", "delay_reason", "reason"},
        "E6": {"event", "waypoint_index", "waypoint_s", "distance_meters", "reason"},
    }.get(details.get("event"))
    if expected is None or set(details) != expected:
        raise AssertionError(f"unexpected discarded-event fields for {details.get('event')}")
    forbidden = {"lat", "lon", "timestamp", "t", "waypoint_name", "name"}
    if forbidden & set(details):
        raise AssertionError("discarded-event details contain location, time, or waypoint-name data")

def event_stream(include_frame: bool = True) -> list[dict]:
    events: list[dict] = [
        {"seq": 0, "t": 1.0, "stream": "envelope", "event_stream": "loc", "event_type": "location"},
        {"seq": 1, "t": 1.0, "stream": "loc", "lat": 10.0, "lon": 20.0, "accuracy": 5.0, "provider": "test"},
    ]
    if include_frame:
        events.append({
            "seq": 2, "t": 1.0, "stream": "guide", "src_seq": 1,
            "decision": "CONTINUE", "inputs": {}, "reason": {"rule": "matching.on-route", "details": {}}, "state_hash": "sha256:test", "output_text": "",
        })
    events.append({
        "seq": 3 if include_frame else 2, "t": 1.0, "stream": "guide", "src_seq": 1, "trigger": "slot",
        "decision": "CONTINUE", "inputs": {}, "reason": {"rule": "matching.on-route", "details": {}}, "state_hash": "sha256:test", "output_text": "경로 위입니다",
    })
    events.append(discarded_event())
    events.append(discarded_waypoint_event())
    return events


def write_session(root: Path, include_frame: bool) -> None:
    (root / "manifest.json").write_text(json.dumps(manifest()), encoding="utf-8")
    (root / "events.ndjson").write_text("\n".join(json.dumps(item, ensure_ascii=False) for item in event_stream(include_frame)) + "\n", encoding="utf-8")


def main() -> int:
    root_dir = Path.cwd() / ".tmp-app-session-shape"
    shutil.rmtree(root_dir, ignore_errors=True)
    root_dir.mkdir()
    try:
        root = root_dir / "valid"
        root.mkdir()
        write_session(root, include_frame=True)
        errors, warnings, count = validate(root)
        assert not errors, errors
        assert count == 6
        emitted = [json.loads(raw) for raw in (root / "events.ndjson").read_text(encoding="utf-8").splitlines()]
        discards = [event for event in emitted if event.get("kind") == "event.discarded"]
        assert [event["details"]["event"] for event in discards] == ["E4", "E6"]
        assert all(event["stream"] == "sys" for event in discards)
        for discard in discards:
            assert_discard_shape(discard)

        invalid_discard = discarded_waypoint_event()
        invalid_discard["details"]["lat"] = "10.0"
        try:
            assert_discard_shape(invalid_discard)
        except AssertionError:
            pass
        else:
            raise AssertionError("coordinate-bearing discarded-event details were accepted")

        invalid = root_dir / "missing-frame"
        invalid.mkdir()
        write_session(invalid, include_frame=False)
        errors, _, _ = validate(invalid)
        assert any("loc/frame guide count mismatch" in error for error in errors), errors
    finally:
        shutil.rmtree(root_dir, ignore_errors=True)
    print("app_session_shape=PASS E4_E6_discard_shape=PASS sensitive_discard_fields_rejected=PASS separate_frame_and_slot_trigger=PASS missing_frame_rejected=PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
