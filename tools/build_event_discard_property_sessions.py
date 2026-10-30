#!/usr/bin/env python3
"""Build deterministic synthetic sessions for TASK-078 property coverage."""
from __future__ import annotations

import hashlib
import json
import math
import uuid
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PROPERTY_ROOT = ROOT / "testdata" / "sessions" / "property"
EVENTS = tuple(f"E{index}" for index in range(1, 9))
LAT0 = 10.0
LON0 = 20.0
METERS_PER_DEGREE = 111_195.0


def route_gpx() -> str:
    points = []
    for distance in range(0, 1601, 50):
        latitude = LAT0 + distance / METERS_PER_DEGREE
        elevation = max(0.0, 80.0 - abs(distance - 500) * 0.16)
        points.append(f'<trkpt lat="{latitude:.10f}" lon="{LON0:.10f}"><ele>{elevation:.1f}</ele></trkpt>')
    waypoint_latitude = LAT0 + 700 / METERS_PER_DEGREE
    return (
        '<gpx version="1.1" creator="synthetic-property">'
        f'<wpt lat="{waypoint_latitude:.10f}" lon="{LON0:.10f}"><name>Synthetic landmark</name></wpt>'
        f'<trk><trkseg>{"".join(points)}</trkseg></trk></gpx>\n'
    )


def event_settings(enabled: set[str]) -> dict[str, bool]:
    return {event: event in enabled for event in EVENTS}


def fixture(name: str, frames: list[tuple[int, float]], enabled: set[str], *, changes: list[dict] | None = None,
            config: dict | None = None, seed_projected_meters: float | None = None) -> None:
    directory = PROPERTY_ROOT / name
    directory.mkdir(parents=True, exist_ok=True)
    route = route_gpx()
    (directory / "route.gpx").write_text(route, encoding="utf-8", newline="\n")
    engine: dict = {
        "config": {
            "implementation": "core-guide",
            "config_hash": "sha256:task078-synthetic",
            "events": event_settings(enabled),
            **(config or {}),
        },
        "rng_seed": 0,
        "config_changes": changes or [],
    }
    if seed_projected_meters is not None:
        engine["property_seed_projected_meters"] = seed_projected_meters
    route_hash = hashlib.sha256(route.encode("utf-8")).hexdigest()
    manifest = {
        "session_id": str(uuid.uuid5(uuid.NAMESPACE_URL, f"trailnav:task078:{name}")),
        "schema_version": "0.1.0-draft",
        "started_at_wall": "2026-10-30T00:00:00Z",
        "app": {"version": "synthetic", "build": 0, "code_hash": "git:unknown"},
        "engine": engine,
        "route": {
            "gpx_id": f"synthetic-{name}",
            "gpx_hash": f"sha256:{route_hash}",
            "point_count": 33,
            "elevation_used": True,
            "elevation_reason": "ok",
            "waypoint_count": 1,
        },
        "clock": {"monotonic_source": "synthetic-sequence", "timestamp_unit": "seconds", "seq_order": "append"},
        "privacy": {"upload_default": False},
        "device": {"model": "synthetic", "os": "test", "sensors": ["gps"]},
    }
    (directory / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n"
    )

    rows: list[dict] = []
    seq = 0
    for frame_index, (timestamp_ms, projected_meters) in enumerate(frames):
        offset_meters = 60.0 if name == "e6_offroute" and frame_index in {1, 2} else 0.0
        latitude = LAT0 + projected_meters / METERS_PER_DEGREE
        longitude = LON0 + offset_meters / (METERS_PER_DEGREE * math.cos(math.radians(LAT0)))
        timestamp_seconds = timestamp_ms / 1000.0
        rows.append({
            "seq": seq,
            "t": timestamp_seconds,
            "stream": "envelope",
            "event_stream": "loc",
            "event_type": "location",
        })
        seq += 1
        location_seq = seq
        rows.append({
            "seq": seq,
            "t": timestamp_seconds,
            "stream": "loc",
            "lat": latitude,
            "lon": longitude,
            "accuracy": 5.0,
            "speed_mps": 1.0,
            "bearing_deg": 0.0,
            "provider": "synthetic",
        })
        seq += 1
        rows.append({
            "seq": seq,
            "t": timestamp_seconds,
            "stream": "guide",
            "src_seq": location_seq,
            "decision": "CONTINUE",
            "engine": "core-guide",
            "inputs": {"frame_count": frame_index + 1, "rng_seed": 0},
            "reason": {"rule": "synthetic.property-session", "details": {}},
            "state_hash": "sha256:synthetic-property",
        })
        seq += 1
    (directory / "events.ndjson").write_text(
        "".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n" for row in rows),
        encoding="utf-8",
        newline="\n",
    )


def main() -> int:
    PROPERTY_ROOT.mkdir(parents=True, exist_ok=True)
    fixture(
        "e4_disabled_pending",
        [(0, 0), (1_000, 100), (2_000, 250), (3_000, 400), (4_000, 420)],
        {"E1", "E4"},
        changes=[{"frame_index": 4, "event": "E4", "value": False}],
        config={"eventMinIntervalSeconds": 60.0, "milestoneIntervalMeters": 250.0, "peakAnnounceLeadMeters": 150.0},
    )
    fixture(
        "e4_reverse_pending",
        [(0, 0), (1_000, 100), (2_000, 250), (3_000, 400), (4_000, 390), (5_000, 370), (6_000, 350), (7_000, 330)],
        {"E1", "E4"},
        config={"eventMinIntervalSeconds": 60.0, "milestoneIntervalMeters": 250.0, "peakAnnounceLeadMeters": 150.0},
    )
    fixture("e6_disabled", [(0, 600)], {"E1"}, config={"waypointAnnounceLeadMeters": 200.0})
    fixture("e4_direction_unknown", [(0, 400)], {"E4"}, config={"peakAnnounceLeadMeters": 150.0})
    fixture("e6_direction_unknown", [(0, 600)], {"E6"}, config={"waypointAnnounceLeadMeters": 200.0})
    fixture("e6_offroute", [(0, 400), (1_000, 480), (30_000, 550)], {"E6"}, config={"waypointAnnounceLeadMeters": 200.0})
    fixture(
        "e6_reverse",
        [(1_000, 600)],
        {"E6"},
        config={"waypointAnnounceLeadMeters": 200.0},
        seed_projected_meters=800.0,
    )
    fixture(
        "e6_interval",
        [(0, 0), (1_000, 100), (2_000, 250), (3_000, 400), (4_000, 500), (5_000, 550)],
        {"E1", "E6"},
        config={"eventMinIntervalSeconds": 60.0, "milestoneIntervalMeters": 250.0, "waypointAnnounceLeadMeters": 200.0},
    )
    fixture(
        "e6_priority",
        [(0, 400), (500, 500), (1_000, 650)],
        {"E3", "E6"},
        config={"waypointAnnounceLeadMeters": 100.0},
    )
    changes = [
        {"frame_index": 1, "event": event, "value": False} for event in EVENTS
    ] + [
        {"frame_index": 4, "event": event, "value": True} for event in EVENTS
    ]
    fixture(
        "all_events_off_on",
        [(0, 0), (1_000, 200), (2_000, 400), (3_000, 600), (4_000, 800), (5_000, 1_000), (6_000, 1_200)],
        set(EVENTS),
        changes=changes,
    )
    print(f"generated {len(list(PROPERTY_ROOT.iterdir()))} deterministic property sessions at {PROPERTY_ROOT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
