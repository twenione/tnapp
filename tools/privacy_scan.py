#!/usr/bin/env python3
"""Fail-closed scan for identifying coordinates and real-world timestamps.

The scanner reports only field-kind counts. It never prints offending values,
paths, or coordinates, so it is safe to run in CI against synthetic fixtures.
The field names intentionally include nested stream context (for example
``guide.inputs.lat``) so a newly observed shape cannot be mistaken for a
covered top-level location field (D-046).
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from xml.etree import ElementTree as ET


KOREA_LAT = (33.0, 39.0)
KOREA_LON = (124.0, 132.0)
YEAR_2026_SECONDS = (
    datetime(2026, 1, 1, tzinfo=timezone.utc).timestamp(),
    datetime(2027, 1, 1, tzinfo=timezone.utc).timestamp(),
)
YEAR_2026_MILLIS = (YEAR_2026_SECONDS[0] * 1000.0, YEAR_2026_SECONDS[1] * 1000.0)
ISO_2026 = re.compile(r"^2026(?:-|T|$)")
FIELD_KINDS = frozenset({
    "loc.lat", "loc.lon", "loc.t",
    "guide.inputs.lat", "guide.inputs.lon", "guide.inputs.timestamp", "guide.t",
    "envelope.t", "sys.t", "manifest.started_at_wall", "route.gpx.coordinate",
    "route.gpx.elevation", "route.gpx.waypoint_name",
})
STREAM_FIELDS = {
    "loc": {"lat", "lon", "t"},
    "guide": {"t"},
    "envelope": {"t"},
    "sys": {"t"},
}


def _numeric(value: object) -> float | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return float(value)
    return None


def _in_2026(value: object, *, milliseconds: bool = False) -> bool:
    number = _numeric(value)
    if number is None:
        return False
    low, high = YEAR_2026_MILLIS if milliseconds else YEAR_2026_SECONDS
    return low <= number <= high


def _kind_for(path: tuple[str, ...], key: str) -> str:
    if path and path[0] in {"loc", "guide", "envelope", "sys"}:
        if path[0] == "guide" and len(path) >= 2 and path[1] == "inputs":
            candidate = "guide.inputs." + key
        else:
            candidate = path[0] + "." + key
        if candidate in FIELD_KINDS and key in STREAM_FIELDS.get(path[0], set()) | ({"lat", "lon", "timestamp"} if path[0] == "guide" and len(path) >= 2 and path[1] == "inputs" else set()):
            return candidate
        return "unknown." + ".".join((*path, key))
    if key == "started_at_wall":
        return "manifest.started_at_wall"
    return "unknown." + ".".join((*path, key))


def _scan_object(value: object, path: tuple[str, ...], counts: Counter[str]) -> None:
    if isinstance(value, dict):
        lat_sibling = next((value[name] for name in ("lat", "latitude") if name in value), None)
        lon_sibling = next((value[name] for name in ("lon", "longitude") if name in value), None)
        has_coordinate_pair = _numeric(lat_sibling) is not None and _numeric(lon_sibling) is not None
        pair_in_korea = has_coordinate_pair and KOREA_LAT[0] <= float(lat_sibling) <= KOREA_LAT[1] and KOREA_LON[0] <= float(lon_sibling) <= KOREA_LON[1]
        for key, child in value.items():
            if key in {"lat", "latitude"} and _numeric(child) is not None and ((has_coordinate_pair and pair_in_korea) or (not has_coordinate_pair and KOREA_LAT[0] <= float(child) <= KOREA_LAT[1])):
                counts[_kind_for(path, "lat")] += 1
            elif key in {"lon", "longitude"} and _numeric(child) is not None and ((has_coordinate_pair and pair_in_korea) or (not has_coordinate_pair and KOREA_LON[0] <= float(child) <= KOREA_LON[1])):
                counts[_kind_for(path, "lon")] += 1
            elif key == "t" and _in_2026(child):
                counts[_kind_for(path, "t")] += 1
            elif key == "timestamp" and _in_2026(child, milliseconds=True):
                counts[_kind_for(path, "timestamp")] += 1
            elif key == "started_at_wall" and isinstance(child, str) and ISO_2026.match(child):
                counts["manifest.started_at_wall"] += 1
            _scan_object(child, (*path, key), counts)
    elif isinstance(value, list):
        for child in value:
            _scan_object(child, path, counts)


def scan_session(session: Path, route: Path | None = None) -> Counter[str]:
    counts: Counter[str] = Counter()
    manifest = session / "manifest.json"
    events = session / "events.ndjson"
    if manifest.is_file():
        _scan_object(json.loads(manifest.read_text(encoding="utf-8")), tuple(), counts)
    if events.is_file():
        with events.open(encoding="utf-8") as stream:
            for line in stream:
                if line.strip():
                    event = json.loads(line)
                    event_stream = event.get("stream") if isinstance(event, dict) else None
                    _scan_object(event, (event_stream,) if isinstance(event_stream, str) else tuple(), counts)
    route_path = route or session / "route.gpx"
    if route_path.is_file():
        root = ET.fromstring(route_path.read_text(encoding="utf-8"))
        marker_manifest = json.loads(manifest.read_text(encoding="utf-8")) if manifest.is_file() else {}
        elevation_marked = marker_manifest.get("route", {}).get("elevation_source") == "synthetic" or marker_manifest.get("elevation_source") == "synthetic"
        waypoint_marked = marker_manifest.get("route", {}).get("waypoint_names") == "synthetic" or marker_manifest.get("waypoint_names") == "synthetic"
        for point in root.iter():
            tag = point.tag.rsplit("}", 1)[-1]
            if tag not in {"trkpt", "rtept", "wpt"}:
                continue
            try:
                lat = float(point.attrib["lat"])
                lon = float(point.attrib["lon"])
            except (KeyError, TypeError, ValueError):
                continue
            if lat is not None and lon is not None and KOREA_LAT[0] <= lat <= KOREA_LAT[1] and KOREA_LON[0] <= lon <= KOREA_LON[1]:
                counts["route.gpx.coordinate"] += 1
            if tag == "wpt":
                name = next((child.text for child in point if child.tag.rsplit("}", 1)[-1] == "name"), None)
                if name and not waypoint_marked:
                    counts["route.gpx.waypoint_name"] += 1
            if not elevation_marked and any(child.tag.rsplit("}", 1)[-1] == "ele" and child.text for child in point):
                counts["route.gpx.elevation"] += 1
    return counts


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("session", type=Path)
    parser.add_argument("--route", type=Path)
    args = parser.parse_args(argv[1:])
    try:
        counts = scan_session(args.session, args.route)
    except (OSError, ValueError, ET.ParseError, json.JSONDecodeError) as exc:
        print(f"ERROR: privacy scan failed: {exc}", file=sys.stderr)
        return 2
    for kind in sorted(counts):
        print(f"{kind}: {counts[kind]}")
    if counts:
        print(f"FAIL: identifying values detected ({sum(counts.values())} fields)")
        return 1
    print("PASS: no identifying coordinates or 2026 timestamps detected")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
