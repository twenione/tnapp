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
        assert count == 4

        invalid = root_dir / "missing-frame"
        invalid.mkdir()
        write_session(invalid, include_frame=False)
        errors, _, _ = validate(invalid)
        assert any("loc/frame guide count mismatch" in error for error in errors), errors
    finally:
        shutil.rmtree(root_dir, ignore_errors=True)
    print("app_session_shape=PASS separate_frame_and_slot_trigger=PASS missing_frame_rejected=PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
