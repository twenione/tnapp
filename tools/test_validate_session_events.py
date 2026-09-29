#!/usr/bin/env python3
"""Negative controls for optional manifest engine.config.events validation."""
from __future__ import annotations

import json
import shutil
import tempfile
from pathlib import Path

from validate_session import validate


EXPECTED_ERROR = "manifest.json: engine.config.events must contain exactly E1..E8 booleans"
APP_EVENTS = {
    "E1": False, "E2": False, "E3": True, "E4": True,
    "E5": False, "E6": True, "E7": True, "E8": True,
}


def write_events_manifest(root: Path, events: object) -> None:
    path = root / "manifest.json"
    manifest = json.loads(path.read_text(encoding="utf-8"))
    manifest.setdefault("engine", {}).setdefault("config", {})["events"] = events
    path.write_text(json.dumps(manifest), encoding="utf-8")


def test_session_event_validation() -> None:
    source = Path("testdata/sessions/golden/golden_engine")
    with tempfile.TemporaryDirectory(prefix="tnapp-session-events-") as temporary:
        root = Path(temporary) / "session"
        shutil.copytree(source, root)

        errors, _, _ = validate(root)
        assert EXPECTED_ERROR not in errors, f"legacy manifest without events rejected: {errors}"

        write_events_manifest(root, APP_EVENTS)
        errors, _, _ = validate(root)
        assert EXPECTED_ERROR not in errors, f"valid E1..E8 manifest rejected: {errors}"

        invalid_cases = {
            "missing key": {key: value for key, value in APP_EVENTS.items() if key != "E8"},
            "extra key": {**APP_EVENTS, "E9": False},
            "string value": {**APP_EVENTS, "E3": "true"},
        }
        for label, events in invalid_cases.items():
            write_events_manifest(root, events)
            errors, _, _ = validate(root)
            assert EXPECTED_ERROR in errors, f"{label} was accepted: {errors}"


if __name__ == "__main__":
    test_session_event_validation()
    print("PASS: session event config accepts absent and exact E1..E8 booleans; rejects missing, extra, and string values")
