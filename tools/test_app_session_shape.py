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
            "event": "E4", "threshold": "0", "progress_meters": "520.0",
            "peak_index": "0", "peak_s": "510.0",
            "passed_projected_meters": "520.0", "delay_reason": "event-min-interval", "reason": "passed",
        },
    }


def discarded_generic_event() -> dict:
    return {
        "seq": 6, "t": 1.0, "stream": "sys", "kind": "event.discarded", "battery_pct": None,
        "details": {"event": "E1", "threshold": "2", "reason": "interval", "progress_meters": "250.0"},
    }


def discarded_waypoint_event() -> dict:
    return {
        "seq": 5, "t": 1.0, "stream": "sys", "kind": "event.discarded", "battery_pct": None,
        "details": {
            "event": "E6", "threshold": "1", "progress_meters": "555.0",
            "waypoint_index": "1", "waypoint_s": "600.0",
            "distance_meters": "45.0", "reason": "priority",
        },
    }


def arrival_blocked_event() -> dict:
    return {
        "seq": 7, "t": 1.0, "stream": "sys", "kind": "arrival.blocked", "battery_pct": None,
        "details": {
            "creditedMeters": "0.0", "requiredMeters": "50.0",
            "baselineRemainingMeters": "20.0", "projectedMeters": "1980.0",
        },
    }


def assert_discard_shape(event: dict) -> None:
    details = event["details"]
    expected = {
        "E1": {"event", "threshold", "reason", "progress_meters"},
        "E2": {"event", "threshold", "reason", "progress_meters"},
        "E3": {"event", "threshold", "reason", "progress_meters"},
        "E4": {"event", "threshold", "progress_meters", "peak_index", "peak_s", "passed_projected_meters", "delay_reason", "reason"},
        "E6": {"event", "threshold", "progress_meters", "waypoint_index", "waypoint_s", "distance_meters", "reason"},
        "E8": {"event", "threshold", "reason", "progress_meters"},
    }.get(details.get("event"))

    if expected is None or set(details) != expected:
        raise AssertionError(f"unexpected discarded-event fields for {details.get('event')}")
    forbidden = {"lat", "lon", "timestamp", "t", "waypoint_name", "name", "bearing_deg"}
    if forbidden & set(details):
        raise AssertionError("discarded-event details contain location, time, or waypoint-name data")


def assert_arrival_blocked_shape(event: dict) -> None:
    details = event["details"]
    expected = {"creditedMeters", "requiredMeters", "baselineRemainingMeters", "projectedMeters"}
    if event.get("kind") != "arrival.blocked" or set(details) != expected:
        raise AssertionError("unexpected arrival.blocked fields")
    if any(not isinstance(value, str) for value in details.values()):
        raise AssertionError("arrival.blocked details must be strings")
    if {"lat", "lon", "timestamp", "t", "name"} & set(details):
        raise AssertionError("arrival.blocked details contain location, time, or name data")


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
    events.append(discarded_generic_event())
    events.append(arrival_blocked_event())
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
        assert count == 8
        emitted = [json.loads(raw) for raw in (root / "events.ndjson").read_text(encoding="utf-8").splitlines()]
        discards = [event for event in emitted if event.get("kind") == "event.discarded"]
        assert [event["details"]["event"] for event in discards] == ["E4", "E6", "E1"]
        assert all(event["stream"] == "sys" for event in discards)
        for discard in discards:
            assert_discard_shape(discard)
            if discard["details"]["event"] == "E1":
                assert all(isinstance(value, str) for value in discard["details"].values())
                for forbidden_key in ("lat", "timestamp", "name"):
                    invalid_discard = dict(discard)
                    invalid_discard["details"] = dict(discard["details"], **{forbidden_key: "10.0"})
                    try:
                        assert_discard_shape(invalid_discard)
                    except AssertionError:
                        pass
                    else:
                        raise AssertionError(f"event.discarded containing {forbidden_key} was accepted")

        blocked = [event for event in emitted if event.get("kind") == "arrival.blocked"]
        assert len(blocked) == 1 and blocked[0]["stream"] == "sys"
        assert_arrival_blocked_shape(blocked[0])

        for forbidden_key in ("lat", "timestamp"):
            invalid_arrival = arrival_blocked_event()
            invalid_arrival["details"][forbidden_key] = "10.0"
            try:
                assert_arrival_blocked_shape(invalid_arrival)
            except AssertionError:
                pass
            else:
                raise AssertionError(f"arrival.blocked details containing {forbidden_key} were accepted")

        invalid = root_dir / "missing-frame"
        invalid.mkdir()
        write_session(invalid, include_frame=False)
        errors, _, _ = validate(invalid)
        assert any("loc/frame guide count mismatch" in error for error in errors), errors
    finally:
        shutil.rmtree(root_dir, ignore_errors=True)
    print("app_session_shape=PASS all_discard_shapes=PASS sensitive_discard_fields_rejected=PASS arrival_blocked_shape=PASS separate_frame_and_slot_trigger=PASS missing_frame_rejected=PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
