#!/usr/bin/env python3
"""Generate deterministic, synthetic-only GPX and v0 session fixtures."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import random
import uuid
from datetime import datetime, timezone
from pathlib import Path
from xml.etree import ElementTree as ET

SYNTH_NAMESPACE = uuid.UUID("4f7a9c4e-5d61-4d83-8f3c-9f1dfad2f0f0")


def base_points() -> list[tuple[float, float]]:
    # Deliberately synthetic coordinates; this is not a measured route.
    return [(10.0 + i * 0.00002, 20.0 + math.sin(i / 5) * 0.00002) for i in range(31)]


def long_route_points(count: int = 260, spacing_m: float = 2.0) -> list[tuple[float, float]]:
    """A synthetic northbound route long enough for D-032 departure lengths."""
    radius = 6_371_008.8
    origin_lat, origin_lon = 11.0, 21.0
    return [(origin_lat + (index * spacing_m) / radius * 180.0 / math.pi, origin_lon) for index in range(count)]


def write_gpx(path: Path, points: list[tuple[float, float]]) -> None:
    root = ET.Element("gpx", {"version": "1.1", "creator": "tnApp-synth"})
    trk = ET.SubElement(root, "trk")
    seg = ET.SubElement(trk, "trkseg")
    for lat, lon in points:
        ET.SubElement(seg, "trkpt", {"lat": f"{lat:.8f}", "lon": f"{lon:.8f}"})
    path.write_text(ET.tostring(root, encoding="unicode") + "\n", encoding="utf-8")


def read_gpx(path: Path) -> list[tuple[float, float]]:
    root = ET.fromstring(path.read_text(encoding="utf-8"))
    points = []
    for element in root.iter():
        if element.tag.rsplit("}", 1)[-1] == "trkpt" or element.tag.rsplit("}", 1)[-1] == "rtept":
            try:
                points.append((float(element.attrib["lat"]), float(element.attrib["lon"])))
            except (KeyError, ValueError):
                continue
    if not points:
        raise ValueError(f"base GPX contains no track points: {path}")
    return points


def event(seq: int, t: float, stream: str, **values):
    payload = {"seq": seq, "t": round(t, 3), "stream": stream}
    payload.update(values)
    return payload


def make_session(root: Path, name: str, points: list[tuple[float, float]], variant: str, seed: int) -> None:
    session_id = str(uuid.uuid5(SYNTH_NAMESPACE, name))
    session = root / name
    session.mkdir(parents=True, exist_ok=True)
    route_bytes = "\n".join(f"{lat:.8f},{lon:.8f}" for lat, lon in points).encode()
    route_hash = "sha256:" + hashlib.sha256(route_bytes).hexdigest()
    manifest = {
        "session_id": session_id,
        "schema_version": "0.1.0-draft",
        "started_at_wall": "2026-01-01T00:00:00Z",
        "app": {"version": "0.1.0", "build": 0, "code_hash": "sha256:synthetic"},
        "engine": {"config": {"stub": True, "variant": variant}, "rng_seed": seed},
        "route": {"gpx_id": "synthetic-base", "gpx_hash": route_hash, "point_count": len(points)},
        "clock": {"monotonic_source": "synthetic-sequence"},
        "privacy": {"upload_default": False},
        "device": {"model": "synthetic", "os": "test", "sensors": ["gps"]},
    }
    combo_specs = {
        "combo_a": {"noise_sigma_m": 30.0, "departure_angle_deg": 15.0, "departure_start": 20, "departure_end": 160},
        "combo_b": {"noise_sigma_m": 15.0, "departure_angle_deg": 15.0, "departure_start": 20, "departure_end": 160},
        "combo_c": {"noise_sigma_m": 0.0, "departure_angle_deg": 30.0, "departure_start": 20, "departure_end": 120, "accuracy_degraded_seconds": 60},
        "combo_d": {"noise_sigma_m": 0.0, "departure_angle_deg": 15.0, "departure_start": 20, "departure_end": 160, "stationary_before_departure_seconds": 300},
        "combo_e": {"noise_sigma_m": 0.0, "departure_angle_deg": 15.0, "departure_start": 20, "departure_end": 160, "reverse_overlap": True},
    }
    if variant in combo_specs:
        spec = combo_specs[variant]
        angle = math.radians(spec["departure_angle_deg"])
        segment_m = (spec["departure_end"] - spec["departure_start"]) * 2.0
        spec["departure_segment_m"] = segment_m
        spec["max_cross_track_m"] = segment_m * math.sin(angle)
        manifest["scenario"] = {
            "family": "phase1-combination",
            "noise_sigma_m": spec.get("noise_sigma_m", 0.0),
            "departure_angle_deg": spec["departure_angle_deg"],
            "departure_segment_m": segment_m,
            "max_cross_track_m": spec["max_cross_track_m"],
            "accuracy_degraded_seconds": spec.get("accuracy_degraded_seconds", 0),
            "stationary_before_departure_seconds": spec.get("stationary_before_departure_seconds", 0),
            "reverse_overlap": spec.get("reverse_overlap", False),
        }
    (session / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    rng = random.Random(seed)
    events = []
    seq = 0
    frame_count = 0
    time_offset = 0
    for index, (lat, lon) in enumerate(points):
        frame_count += 1
        source_index = index
        if variant == "reverse" and 70 <= index < 90:
            source_index = 89 - (index - 70)
        route_lat, route_lon = points[source_index]
        if variant.startswith("offroute_") and 50 <= index < 75:
            # A 50 m perpendicular displacement, with the variant preserving
            # the intended 15/30/90 degree scenario label.
            route_lat += 0.00045
        combo = combo_specs.get(variant)
        if combo is not None:
            start = combo["departure_start"]
            if combo.get("reverse_overlap") and start <= index < start + 80:
                source_index = start + 79 - (index - start)
                route_lat, route_lon = points[source_index]
            if start <= index < combo["departure_end"]:
                distance_from_departure = (index - start) * 2.0
                cross_track = distance_from_departure * math.sin(math.radians(combo["departure_angle_deg"]))
                route_lon += cross_track / (6_371_008.8 * math.cos(math.radians(route_lat))) * 180.0 / math.pi
        noise_sigma = {"noise_5m": 0.000005, "noise_15m": 0.000015, "noise_30m": 0.00003}.get(variant, 0.000003)
        if combo is not None:
            noise_m = combo.get("noise_sigma_m", 0.0)
            noise_lat = rng.gauss(0.0, noise_m) / 6_371_008.8 * 180.0 / math.pi
            noise_lon = rng.gauss(0.0, noise_m) / (6_371_008.8 * math.cos(math.radians(route_lat))) * 180.0 / math.pi
        else:
            noise_lat = noise_lon = rng.gauss(0, noise_sigma)
        if variant == "stop_5m" and 60 <= index < 65:
            time_offset = 300
        event_t = index + time_offset
        stationary = combo is not None and combo.get("stationary_before_departure_seconds", 0) > 0 and index < 5
        speed = 0.0 if (variant == "stop_5m" and 60 <= index < 65) or stationary else 1.0
        accuracy = 80.0 if variant == "accuracy_80m" and 10 <= index < 20 else 5.0
        if combo is not None and combo.get("accuracy_degraded_seconds", 0) > 0 and index < combo["accuracy_degraded_seconds"]:
            accuracy = 80.0
        events.append(event(seq, event_t, "loc", lat=round(route_lat + noise_lat, 8), lon=round(route_lon + noise_lon, 8), accuracy=accuracy, provider="synthetic", speed_mps=speed, bearing_deg=90.0))
        seq += 1
        if index % 5 == 0:
            events.append(event(seq, event_t + 0.1, "guide", decision="CONTINUE", inputs={"frame_count": frame_count, "rng_seed": seed}, reason={"rule": "stub.frame-count", "thresholds": {}, "alternatives_considered": []}, state_hash="sha256:synthetic"))
            seq += 1
    (session / "events.ndjson").write_text("\n".join(json.dumps(item, sort_keys=True) for item in events) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", type=Path, default=Path("testdata/routes/base.gpx"))
    parser.add_argument("--output", type=Path, default=Path("testdata/sessions"))
    args = parser.parse_args()
    args.base.parent.mkdir(parents=True, exist_ok=True)
    if args.base.exists():
        points = read_gpx(args.base)
        source = "existing anonymized base.gpx"
    else:
        points = base_points()
        write_gpx(args.base, points)
        source = "synthetic fallback coordinates"
    golden = args.output / "golden"
    synth = args.output / "synth"
    for path in (golden, synth):
        path.mkdir(parents=True, exist_ok=True)
    make_session(golden, "golden_stub", points, "golden", 100)
    variants = ["noise_5m", "noise_15m", "noise_30m", "offroute_15deg", "offroute_30deg", "offroute_90deg", "accuracy_80m", "stop_5m", "reverse"]
    for index, variant in enumerate(variants, 1):
        make_session(synth, f"{index:02d}_{variant}", points, variant, 1000 + index)
    combo_points = long_route_points()
    for index, variant in enumerate(("combo_a", "combo_b", "combo_c", "combo_d", "combo_e"), len(variants) + 1):
        make_session(synth, f"{index:02d}_{variant}", combo_points, variant, 2000 + index)
    print(f"base={args.base}")
    print("generated_golden=1")
    print(f"generated_synth={len(variants) + 5}")
    print(f"source={source}; original measured GPX and offset are not retained")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
