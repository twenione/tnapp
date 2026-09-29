#!/usr/bin/env python3
"""Contract tests for the session E1..E8 to GuideConfig mapping."""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

from session_events import EVENT_CONFIG_FIELDS, event_config_overrides, manifest_events
from replay import invoke_probe


APP_MANIFEST_EVENTS = (
    '{"events":{"E1":false,"E2":false,"E3":true,"E4":true,'
    '"E5":false,"E6":true,"E7":true,"E8":true}}'
)


def test_manifest_event_values() -> None:
    actual = json.loads(APP_MANIFEST_EVENTS)
    present, events, error = manifest_events({"engine": {"config": actual}})
    assert present and error is None
    assert events == {
        "E1": False, "E2": False, "E3": True, "E4": True,
        "E5": False, "E6": True, "E7": True, "E8": True,
    }
    assert event_config_overrides(events) == [
        "milestoneEnabled=0", "elapsedEnabled=0", "remainingEnabled=1", "slopeEnabled=1",
        "elevationEnabled=0", "waypointEnabled=1", "sunsetEnabled=1", "sunriseEnabled=1",
    ]
    assert manifest_events({"engine": {"config": {"stub": False}}}) == (False, None, None)


def model_event_fields() -> set[str]:
    source = Path("core-guide/src/main/kotlin/com/trailnav/core/Model.kt").read_text(encoding="utf-8")
    match = re.search(r"data class GuideConfig\((.*?)\)\s*\{", source, re.DOTALL)
    assert match, "GuideConfig declaration not found"
    return set(re.findall(r"^\s*val\s+(\w+Enabled):\s*Boolean\s*=", match.group(1), re.MULTILINE))


def mapping_covers_model(mapping: dict[str, str], fields: set[str]) -> bool:
    return set(mapping) == set(EVENT_CONFIG_FIELDS) and set(mapping.values()) == fields and len(set(mapping.values())) == len(mapping)


def test_event_settings_match_guide_config_fields() -> None:
    fields = model_event_fields()
    assert mapping_covers_model(EVENT_CONFIG_FIELDS, fields), (
        f"GuideConfig event toggles differ from replay mapping: model={sorted(fields)} "
        f"mapping={sorted(EVENT_CONFIG_FIELDS.values())}"
    )
    wrong_field = dict(EVENT_CONFIG_FIELDS)
    wrong_field["E3"] = wrong_field["E2"]
    assert not mapping_covers_model(wrong_field, fields), "mapping field mutation should be rejected"
    missing_toggle = dict(EVENT_CONFIG_FIELDS)
    del missing_toggle["E3"]
    assert not mapping_covers_model(missing_toggle, fields), "missing E3 mapping should be rejected"


def test_cli_toggle_values(cli: Path) -> None:
    for field in EVENT_CONFIG_FIELDS.values():
        for value in ("0", "1"):
            trace = invoke_probe(cli, [f"{field}={value}"])
            assert trace, f"valid event toggle was rejected: {field}={value}"
        try:
            invoke_probe(cli, [f"{field}=2"])
        except RuntimeError as exc:
            assert field in str(exc) and "2" in str(exc)
        else:
            raise AssertionError(f"invalid event toggle was accepted: {field}=2")
    assert invoke_probe(cli, ["offRouteEnterDwellSeconds=20"]), "legacy numeric override was rejected"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cli", type=Path, required=True)
    args = parser.parse_args()
    test_manifest_event_values()
    test_event_settings_match_guide_config_fields()
    test_cli_toggle_values(args.cli)
    print("PASS: E1..E8 mapping, app-shaped manifest, GuideConfig coverage, and CLI toggle values")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
