#!/usr/bin/env python3
"""Create a deterministic, non-identifying log-shape fixture from a local session.

Offsets are required arguments so a source-specific location or time shift is
never hidden in the repository.  The source file itself is not copied.
"""
from __future__ import annotations

import argparse
import json
import uuid
from pathlib import Path
from xml.etree import ElementTree as ET


FIXTURE_NAMESPACE = uuid.UUID("e1c96d62-c24b-4f13-bf1c-1b61d8b8e57f")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--lat-offset", type=float, required=True)
    parser.add_argument("--lon-offset", type=float, required=True)
    parser.add_argument("--time-offset", type=float, required=True)
    args = parser.parse_args()
    source = args.source
    output = args.output
    output.mkdir(parents=True, exist_ok=True)

    route = ET.fromstring((source / "route.gpx").read_text(encoding="utf-8"))
    for point in route.iter():
        if point.tag.rsplit("}", 1)[-1] in {"trkpt", "rtept"}:
            point.set("lat", f"{float(point.attrib['lat']) + args.lat_offset:.8f}")
            point.set("lon", f"{float(point.attrib['lon']) + args.lon_offset:.8f}")
    (output / "route.gpx").write_text(ET.tostring(route, encoding="unicode") + "\n", encoding="utf-8")

    manifest = json.loads((source / "manifest.json").read_text(encoding="utf-8"))
    manifest["session_id"] = str(uuid.uuid5(FIXTURE_NAMESPACE, output.name))
    manifest["started_at_wall"] = "2026-01-01T00:00:00Z"
    manifest.setdefault("app", {})["code_hash"] = "git:unknown"
    manifest["golden_status"] = "regenerated-after-D-043"
    manifest["fixture_purpose"] = "log-shape-regression"
    manifest["derived_src_seq"] = True

    events = [
        json.loads(line)
        for line in (source / "events.ndjson").read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    loc_events = [event for event in events if event.get("stream") == "loc"]
    loc_seq = [event["seq"] for event in loc_events]
    guide_index = 0
    for event in events:
        if "t" in event:
            event["t"] = round(float(event["t"]) + args.time_offset, 3)
        if event.get("stream") == "loc":
            event["lat"] = round(float(event["lat"]) + args.lat_offset, 8)
            event["lon"] = round(float(event["lon"]) + args.lon_offset, 8)
        elif event.get("stream") == "guide":
            event["src_seq"] = loc_seq[guide_index]
            guide_index += 1
    if len(loc_events) != guide_index:
        raise ValueError("source must contain one guide event per loc event")

    # This event is later in file order but belongs to a different stream.
    # It proves that the validator does not impose a false global t order.
    events.append({
        "seq": max(event["seq"] for event in events) + 1,
        "t": round(min(float(event["t"]) for event in events if event.get("stream") == "loc") - 1.0, 3),
        "stream": "sys",
        "kind": "fixture.cross-stream-flush",
        "battery_pct": 90.0,
        "details": {"source": "fixture"},
    })
    (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / "events.ndjson").write_text(
        "\n".join(json.dumps(event, ensure_ascii=False, sort_keys=True) for event in events) + "\n",
        encoding="utf-8",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
