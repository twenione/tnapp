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
        noise = rng.gauss(0, {"noise_5m": 0.000005, "noise_15m": 0.000015, "noise_30m": 0.00003}.get(variant, 0.000003))
        if variant == "stop_5m" and 60 <= index < 65:
            time_offset = 300
        event_t = index + time_offset
        speed = 0.0 if variant == "stop_5m" and 60 <= index < 65 else 1.0
        events.append(event(seq, event_t, "loc", lat=round(route_lat + noise, 8), lon=round(route_lon + noise, 8), accuracy=80.0 if variant == "accuracy_80m" and 10 <= index < 20 else 5.0, provider="synthetic", speed_mps=speed, bearing_deg=90.0))
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
    print(f"base={args.base}")
    print("generated_golden=1")
    print(f"generated_synth={len(variants)}")
    print(f"source={source}; original measured GPX and offset are not retained")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
