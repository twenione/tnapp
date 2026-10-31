#!/usr/bin/env python3
"""Build deterministic synthetic sessions for TASK-080 event-discard coverage."""
from __future__ import annotations

import hashlib
import json
import math
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PROPERTY_ROOT = ROOT / "testdata" / "sessions" / "property"
EVENTS = tuple(f"E{index}" for index in range(1, 9))
LAT0 = 10.0
LON0 = 20.0
METERS_PER_DEGREE = 111_195.0
BASE_SYNTHETIC = int(datetime(2000, 1, 1, tzinfo=timezone.utc).timestamp())


def route_gpx(peaks_meters: tuple[int, ...] = (500,), waypoints_meters: tuple[int, ...] = (700,)) -> str:
    points = []
    for distance in range(0, 1601, 50):
        elevation = max(0.0, *(80.0 - abs(distance - peak) * 0.16 for peak in peaks_meters))
        latitude = LAT0 + distance / METERS_PER_DEGREE
        points.append(f'<trkpt lat="{latitude:.10f}" lon="{LON0:.10f}"><ele>{elevation:.1f}</ele></trkpt>')
    waypoints = "".join(
        f'<wpt lat="{LAT0 + distance / METERS_PER_DEGREE:.10f}" lon="{LON0:.10f}"><name>Synthetic landmark {index}</name></wpt>'
        for index, distance in enumerate(waypoints_meters)
    )
    return (
        '<gpx version="1.1" creator="synthetic-property">'
        f'{waypoints}<trk><trkseg>{"".join(points)}</trkseg></trk></gpx>\n'
    )


def event_settings(enabled: set[str]) -> dict[str, bool]:
    return {event: event in enabled for event in EVENTS}


def solar_epoch_seconds(timestamp_seconds: int, latitude: float, longitude: float, *, sunrise: bool) -> float:
    """Mirror Engine.sunriseEpochSeconds/sunsetEpochSeconds from the synthetic timestamp."""
    instant = datetime.fromtimestamp(timestamp_seconds, timezone.utc)
    local_date = (instant + timedelta(seconds=int(longitude * 240.0))).date()
    day_of_year = local_date.timetuple().tm_yday
    gamma = 2.0 * math.pi / 365.0 * (day_of_year - 1 + 0.5)
    declination = (
        0.006918 - 0.399912 * math.cos(gamma) + 0.070257 * math.sin(gamma)
        - 0.006758 * math.cos(2 * gamma) + 0.000907 * math.sin(2 * gamma)
    )
    equation = 229.18 * (
        0.000075 + 0.001868 * math.cos(gamma) - 0.032077 * math.sin(gamma)
        - 0.014615 * math.cos(2 * gamma) - 0.040849 * math.sin(2 * gamma)
    )
    zenith = math.radians(90.833)
    latitude_radians = math.radians(latitude)
    cos_hour = (
        math.cos(zenith) - math.sin(latitude_radians) * math.sin(declination)
    ) / (math.cos(latitude_radians) * math.cos(declination))
    hour_angle_minutes = math.degrees(math.acos(cos_hour)) * 4.0
    if sunrise:
        solar_minutes_utc = 720.0 - 4.0 * longitude - hour_angle_minutes - equation
    else:
        solar_minutes_utc = 720.0 - 4.0 * longitude + hour_angle_minutes - equation
    midnight = int(datetime.combine(local_date, datetime.min.time(), timezone.utc).timestamp())
    return midnight + solar_minutes_utc * 60.0


def fixture(
    name: str,
    frames: list[tuple[int, float]],
    enabled: set[str],
    *,
    changes: list[dict] | None = None,
    config: dict | None = None,
    seed_projected_meters: float | None = None,
    seed_ema_delta_meters: float | None = None,
    waypoint_meters: tuple[int, ...] = (700,),
    peaks_meters: tuple[int, ...] = (500,),
    offroute_frames: set[int] | None = None,
    offset_meters: float = 60.0,
) -> None:
    directory = PROPERTY_ROOT / name
    directory.mkdir(parents=True, exist_ok=True)
    route = route_gpx(peaks_meters, waypoint_meters)
    (directory / "route.gpx").write_text(route, encoding="utf-8", newline="\n")
    engine: dict = {
        "config": {
            "implementation": "core-guide",
            "config_hash": "sha256:task080-synthetic",
            "events": event_settings(enabled),
            **(config or {}),
        },
        "rng_seed": 0,
        "config_changes": changes or [],
    }
    if seed_projected_meters is not None:
        engine["property_seed_projected_meters"] = seed_projected_meters
    if seed_ema_delta_meters is not None:
        engine["property_seed_ema_delta_meters"] = seed_ema_delta_meters
    route_hash = hashlib.sha256(route.encode("utf-8")).hexdigest()
    manifest = {
        "session_id": str(uuid.uuid5(uuid.NAMESPACE_URL, f"trailnav:task080:{name}")),
        "schema_version": "0.1.0-draft",
        "started_at_wall": "2000-01-01T00:00:00Z",
        "app": {"version": "synthetic", "build": 0, "code_hash": "git:unknown"},
        "engine": engine,
        "route": {
            "gpx_id": f"synthetic-{name}",
            "gpx_hash": f"sha256:{route_hash}",
            "point_count": 33,
            "elevation_used": True,
            "elevation_reason": "ok",
            "waypoint_count": len(waypoint_meters),
        },
        "clock": {"monotonic_source": "synthetic-sequence", "timestamp_unit": "seconds", "seq_order": "append"},
        "privacy": {"upload_default": False},
        "device": {"model": "synthetic", "os": "test", "sensors": ["gps"]},
    }
    (directory / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n"
    )
    offroute_frames = offroute_frames or set()
    rows: list[dict] = []
    seq = 0
    for frame_index, (timestamp_ms, projected_meters) in enumerate(frames):
        offset = offset_meters if frame_index in offroute_frames else 0.0
        latitude = LAT0 + projected_meters / METERS_PER_DEGREE
        longitude = LON0 + offset / (METERS_PER_DEGREE * math.cos(math.radians(LAT0)))
        timestamp_seconds = timestamp_ms / 1000.0
        rows.append({
            "seq": seq, "t": timestamp_seconds, "stream": "envelope",
            "event_stream": "loc", "event_type": "location",
        })
        seq += 1
        location_seq = seq
        rows.append({
            "seq": seq, "t": timestamp_seconds, "stream": "loc",
            "lat": latitude, "lon": longitude, "accuracy": 5.0,
            "speed_mps": 1.0, "bearing_deg": 0.0, "provider": "synthetic",
        })
        seq += 1
        rows.append({
            "seq": seq, "t": timestamp_seconds, "stream": "guide", "src_seq": location_seq,
            "decision": "CONTINUE", "engine": "core-guide",
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
        "e4_disabled_pending", [(0, 0), (1_000, 100), (2_000, 250), (3_000, 400), (4_000, 420)],
        {"E1", "E4"}, changes=[{"frame_index": 4, "event": "E4", "value": False}],
        config={"eventMinIntervalSeconds": 60.0, "milestoneIntervalMeters": 250.0, "peakAnnounceLeadMeters": 150.0},
    )
    fixture(
        "e4_reverse_pending", [(0, 0), (1_000, 100), (2_000, 250), (3_000, 400), (4_000, 390), (5_000, 370), (6_000, 350), (7_000, 330)],
        {"E1", "E4"}, config={"eventMinIntervalSeconds": 60.0, "milestoneIntervalMeters": 250.0, "peakAnnounceLeadMeters": 150.0},
    )
    fixture("e6_disabled", [(0, 600)], {"E1"}, config={"waypointAnnounceLeadMeters": 200.0})
    fixture("e4_direction_unknown", [(0, 400)], {"E4"}, config={"peakAnnounceLeadMeters": 150.0})
    fixture("e6_direction_unknown", [(0, 600)], {"E6"}, config={"waypointAnnounceLeadMeters": 200.0})
    fixture("e6_offroute", [(0, 400), (1_000, 480), (30_000, 550)], {"E6"}, config={"waypointAnnounceLeadMeters": 200.0}, offroute_frames={1, 2})
    fixture("e6_reverse", [(1_000, 600)], {"E6"}, config={"waypointAnnounceLeadMeters": 200.0}, seed_projected_meters=800.0)
    fixture("e6_reverse_in_window", [(1_000, 600)], {"E6"}, config={"waypointAnnounceLeadMeters": 200.0}, seed_projected_meters=500.0, seed_ema_delta_meters=-1_000.0, peaks_meters=(1200,))
    fixture(
        "e6_interval", [(0, 0), (1_000, 100), (2_000, 250), (3_000, 400), (4_000, 500), (5_000, 550)],
        {"E1", "E6"}, config={"eventMinIntervalSeconds": 60.0, "milestoneIntervalMeters": 250.0, "waypointAnnounceLeadMeters": 200.0},
    )
    fixture("e6_priority", [(0, 400), (500, 500), (1_000, 650)], {"E3", "E6"}, config={"waypointAnnounceLeadMeters": 100.0})
    changes = [{"frame_index": 1, "event": event, "value": False} for event in EVENTS] + [
        {"frame_index": 4, "event": event, "value": True} for event in EVENTS
    ]
    fixture(
        "all_events_off_on", [(0, 0), (1_000, 200), (2_000, 400), (3_000, 600), (4_000, 800), (5_000, 1_000), (6_000, 1_200)],
        set(EVENTS), changes=changes,
    )

    fixture("e1_disabled", [(0, 0), (1_000, 250)], set(), config={"milestoneEnabled": False, "milestoneIntervalMeters": 100.0})
    fixture("e1_offroute", [(0, 0), (1_000, 50), (2_000, 100)], {"E1"}, config={"milestoneIntervalMeters": 100.0, "offRouteEnterDwellSeconds": 0.5}, offroute_frames={1, 2})
    fixture("e1_reverse", [(1_000, 200)], {"E1"}, config={"milestoneIntervalMeters": 100.0}, seed_projected_meters=100.0, seed_ema_delta_meters=-1_000.0)
    fixture("e1_interval_priority", [(0, 0), (1_000, 100), (2_000, 200)], {"E1", "E6"}, config={"eventMinIntervalSeconds": 60.0, "milestoneIntervalMeters": 100.0, "waypointAnnounceLeadMeters": 100.0}, waypoint_meters=(200,))
    fixture("e1_priority", [(0, 0), (1_000, 100)], {"E1", "E6"}, config={"eventMinIntervalSeconds": 0.0, "milestoneIntervalMeters": 100.0, "waypointAnnounceLeadMeters": 100.0}, waypoint_meters=(150,))
    fixture("e1_jump", [(0, 0), (1_000, 350)], {"E1"}, config={"milestoneIntervalMeters": 100.0})
    fixture("e1_direction_unknown_attempt", [(0, 200)], {"E1"}, config={"milestoneIntervalMeters": 100.0})

    fixture("e2_disabled", [(0, 0), (1_000, 0)], set(), config={"elapsedEnabled": False, "elapsedAnnounceIntervalSeconds": 1.0})
    fixture("e2_offroute", [(0, 0), (1_000, 50), (2_000, 50)], {"E2"}, config={"elapsedAnnounceIntervalSeconds": 2.0, "offRouteEnterDwellSeconds": 0.5}, offroute_frames={1, 2})
    fixture("e2_interval", [(0, 0), (1_000, 100), (2_000, 200)], {"E2", "E6"}, config={"eventMinIntervalSeconds": 60.0, "elapsedAnnounceIntervalSeconds": 2.0, "waypointAnnounceLeadMeters": 100.0, "property_seed_projected_meters": 0.0, "property_seed_last_periodic_event_at": 0.0}, waypoint_meters=(200,))
    fixture("e2_priority", [(0, 0), (1_000, 100)], {"E1", "E2"}, config={"milestoneIntervalMeters": 100.0, "elapsedAnnounceIntervalSeconds": 1.0})
    fixture("e2_jump", [(0, 0), (3_000, 0)], {"E2"}, config={"elapsedAnnounceIntervalSeconds": 1.0})
    fixture("e2_spoken", [(0, 0), (1_000, 0)], {"E2"}, config={"elapsedAnnounceIntervalSeconds": 1.0})

    fixture("e3_disabled", [(0, 0), (1_000, 100)], set(), config={"remainingEnabled": False, "remainingAnnounceMeters": [1550.0]})
    fixture("e3_reverse", [(1_000, 700)], {"E3", "E4"}, seed_projected_meters=500.0, seed_ema_delta_meters=-1_000.0, peaks_meters=(800,))
    fixture("e3_direction_unknown_attempt", [(0, 700)], {"E3"})
    e3_frames = [(0, 0), (1_000, 100), (2_000, 300), (3_000, 500), (4_000, 700), (62_000, 700)]
    fixture("e3_pending_then_spoken", e3_frames, {"E1", "E3"}, config={"eventMinIntervalSeconds": 60.0, "milestoneIntervalMeters": 100.0})
    fixture("e3_session_end_pending", e3_frames[:5], {"E1", "E3"}, config={"eventMinIntervalSeconds": 60.0, "milestoneIntervalMeters": 100.0})

    fixture("e4_jump", [(1_000, 700)], {"E4"}, config={"peakAnnounceLeadMeters": 100.0}, seed_projected_meters=300.0, peaks_meters=(500,))
    fixture("e6_jump", [(1_000, 900)], {"E6"}, config={"waypointAnnounceLeadMeters": 100.0}, seed_projected_meters=500.0, waypoint_meters=(650,), peaks_meters=(1200,))
    fixture(
        "first_match_excludes_then_jumps", [(0, 400), (1_000, 900), (2_000, 1_200), (3_000, 1_400)], {"E4", "E6"},
        config={"peakAnnounceLeadMeters": 40.0, "waypointAnnounceLeadMeters": 40.0},
        waypoint_meters=(100, 700), peaks_meters=(300, 900),
    )
    fixture("e4_passed", [(0, 0), (1_000, 300), (2_000, 400), (62_000, 600)], {"E1", "E4"}, config={"eventMinIntervalSeconds": 60.0, "milestoneIntervalMeters": 300.0, "peakAnnounceLeadMeters": 150.0, "property_seed_last_periodic_event_at": 0.0})

    sunrise = int(solar_epoch_seconds(BASE_SYNTHETIC, LAT0, LON0, sunrise=True))
    sunset = int(solar_epoch_seconds(BASE_SYNTHETIC, LAT0, LON0, sunrise=False))
    fixture("e8_disabled", [((sunrise-40*60)*1000, 0), ((sunrise-20*60)*1000, 0)], set(), config={"sunriseEnabled": False, "sunriseAnnounceMinutes": [30]})
    fixture("e8_offroute", [((sunrise-40*60)*1000, 0), ((sunrise-35*60)*1000, 0), ((sunrise-20*60)*1000, 0)], {"E8"}, config={"sunriseAnnounceMinutes": [30], "offRouteEnterDwellSeconds": 1.0}, offroute_frames={1, 2})
    fixture("e8_interval", [((sunrise-40*60)*1000, 0), ((sunrise-35*60)*1000, 100), ((sunrise-20*60)*1000, 200)], {"E1", "E8"}, config={"eventMinIntervalSeconds": 3600.0, "milestoneIntervalMeters": 100.0, "sunriseAnnounceMinutes": [30]})
    fixture("e8_priority", [((sunrise-40*60)*1000, 0), ((sunrise-35*60)*1000, 100), ((sunrise-20*60)*1000, 300)], {"E3", "E8"}, config={"eventMinIntervalSeconds": 0.0, "remainingAnnounceMeters": [1400.0], "sunriseAnnounceMinutes": [30]})
    fixture("e8_spoken", [((sunrise-40*60)*1000, 0), ((sunrise-20*60)*1000, 0)], {"E8"}, config={"sunriseAnnounceMinutes": [30]})

    fixture("e7_spoken", [((sunset-40*60)*1000, 0), ((sunset-20*60)*1000, 0)], {"E7"}, config={"sunsetAnnounceMinutes": [30]})
    fixture("e7_disabled_pending", [((sunset-40*60)*1000, 0), ((sunset-35*60)*1000, 100), ((sunset-20*60)*1000, 200)], {"E1", "E7"}, changes=[{"frame_index": 2, "event": "E7", "value": False}], config={"eventMinIntervalSeconds": 3600.0, "milestoneIntervalMeters": 100.0, "sunsetAnnounceMinutes": [30]})

    print(f"generated {len(list(PROPERTY_ROOT.iterdir()))} deterministic property sessions at {PROPERTY_ROOT.relative_to(ROOT)}")
    print(f"solar_offsets_seconds sunrise={sunrise} sunset={sunset} algorithm=Engine_NOAA_localDate_2000-01-01")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
