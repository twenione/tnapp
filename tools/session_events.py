"""Shared validation and EngineCli mapping for optional session event settings."""
from __future__ import annotations

from typing import Any


EVENT_CONFIG_FIELDS = {
    "E1": "milestoneEnabled",
    "E2": "elapsedEnabled",
    "E3": "remainingEnabled",
    "E4": "slopeEnabled",
    "E5": "elevationEnabled",
    "E6": "waypointEnabled",
    "E7": "sunsetEnabled",
    "E8": "sunriseEnabled",
}


def manifest_events(manifest: Any) -> tuple[bool, dict[str, bool] | None, str | None]:
    """Return presence, validated E1..E8 values, and a useful validation error."""
    engine = manifest.get("engine") if isinstance(manifest, dict) else None
    config = engine.get("config") if isinstance(engine, dict) else None
    if not isinstance(config, dict) or "events" not in config:
        return False, None, None

    events = config["events"]
    if not isinstance(events, dict):
        return True, None, "expected an object with exactly E1..E8 boolean values"
    expected = set(EVENT_CONFIG_FIELDS)
    actual = set(events)
    if actual != expected:
        missing = sorted(expected - actual)
        extra = sorted(actual - expected)
        details = []
        if missing:
            details.append("missing " + ",".join(missing))
        if extra:
            details.append("unexpected " + ",".join(str(key) for key in extra))
        return True, None, "; ".join(details)
    invalid = [key for key in EVENT_CONFIG_FIELDS if type(events[key]) is not bool]
    if invalid:
        return True, None, "non-boolean " + ",".join(invalid)
    return True, {key: events[key] for key in EVENT_CONFIG_FIELDS}, None


def event_config_overrides(events: dict[str, bool]) -> list[str]:
    """Translate a validated E1..E8 setting to EngineCli --config assignments."""
    return [f"{field}={int(events[event])}" for event, field in EVENT_CONFIG_FIELDS.items()]
