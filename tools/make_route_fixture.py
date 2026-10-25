#!/usr/bin/env python3
"""Create a short, anonymized GPX route-shape fixture from a local route.

Source files are read only. Coordinates and elevations receive one-time random
constant offsets; identifying metadata, names, timestamps, and waypoints are
discarded. Output is staged, scanned, checked against source scalars, then
published as a new fixture directory.
"""
from __future__ import annotations

import argparse
import json
import math
import secrets
import shutil
import sys
from pathlib import Path
from xml.etree import ElementTree as ET

try:
    from privacy_scan import scan_session
except ModuleNotFoundError:  # package import in unit tests
    from tools.privacy_scan import scan_session


EARTH_RADIUS_METERS = 6_371_008.8
MINIMUM_POINT_SPACING_METERS = 1.0
GPX_NS = "http://www.topografix.com/GPX/1/1"
ET.register_namespace("", GPX_NS)


def _local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def _read_route(source: Path) -> tuple[list[dict[str, object]], ET.Element]:
    root = ET.parse(source).getroot()
    nodes = [node for node in root.iter() if _local_name(node.tag) == "trkpt"]
    if not nodes:
        nodes = [node for node in root.iter() if _local_name(node.tag) == "rtept"]
    points: list[dict[str, object]] = []
    for node in nodes:
        try:
            lat_text, lon_text = node.attrib["lat"], node.attrib["lon"]
            lat, lon = float(lat_text), float(lon_text)
        except (KeyError, TypeError, ValueError):
            continue
        if not math.isfinite(lat) or not math.isfinite(lon):
            continue
        elevation_node = next((child for child in node if _local_name(child.tag) == "ele"), None)
        elevation_text = elevation_node.text if elevation_node is not None else None
        elevation = None
        if elevation_text is not None:
            try:
                parsed = float(elevation_text)
            except ValueError:
                parsed = math.nan
            if math.isfinite(parsed):
                elevation = parsed
        points.append({
            "lat": lat,
            "lon": lon,
            "lat_text": lat_text,
            "lon_text": lon_text,
            "elevation": elevation,
            "elevation_text": elevation_text,
        })
    if not points:
        raise ValueError("source contains no valid route points")
    return points, root


def _pair_distance(a: dict[str, object], b: dict[str, object]) -> float:
    lat_a, lat_b = float(a["lat"]), float(b["lat"])
    lon_a, lon_b = float(a["lon"]), float(b["lon"])
    east = math.radians(lon_b - lon_a) * math.cos(math.radians(lat_a)) * EARTH_RADIUS_METERS
    north = math.radians(lat_b - lat_a) * EARTH_RADIUS_METERS
    return math.hypot(east, north)


def _filtered_route(points: list[dict[str, object]]) -> tuple[list[dict[str, object]], list[float]]:
    filtered: list[dict[str, object]] = []
    for point in points:
        if not filtered or _pair_distance(filtered[-1], point) >= MINIMUM_POINT_SPACING_METERS:
            filtered.append(point)
    if len(filtered) == 1:
        filtered = [filtered[0], dict(filtered[0])]
    origin_lat, origin_lon = float(filtered[0]["lat"]), float(filtered[0]["lon"])
    projected = [
        (
            math.radians(float(point["lon"]) - origin_lon) * math.cos(math.radians(origin_lat)) * EARTH_RADIUS_METERS,
            math.radians(float(point["lat"]) - origin_lat) * EARTH_RADIUS_METERS,
        )
        for point in filtered
    ]
    cumulative = [0.0]
    for (east_a, north_a), (east_b, north_b) in zip(projected, projected[1:]):
        cumulative.append(cumulative[-1] + math.hypot(east_b - east_a, north_b - north_a))
    return filtered, cumulative


def _nearest_index(cumulative: list[float], requested: float) -> int:
    return min(range(len(cumulative)), key=lambda index: abs(cumulative[index] - requested))


def _pick_offsets(points: list[dict[str, object]]) -> tuple[float, float, float]:
    rng = secrets.SystemRandom()
    lats = [float(point["lat"]) for point in points]
    lons = [float(point["lon"]) for point in points]
    for _ in range(500):
        lat_offset = rng.uniform(-0.001, 0.001)
        lon_offset = rng.choice((-1.0, 1.0)) * rng.uniform(35.0, 70.0)
        shifted_lats = [lat + lat_offset for lat in lats]
        shifted_lons = [(lon + lon_offset + 180.0) % 360.0 - 180.0 for lon in lons]
        if any(not -90.0 <= lat <= 90.0 for lat in shifted_lats):
            continue
        # At least one coordinate of every source point leaves the scanner's
        # regional identifying box. The small latitude translation also keeps
        # the RouteModel ENU distance axis stable while changing every source
        # latitude scalar.
        if all(not (33.0 <= lat <= 39.0 and 124.0 <= lon <= 132.0)
               for lat, lon in zip(shifted_lats, shifted_lons)):
            altitude_offset = rng.uniform(10_000.0, 100_000.0)
            return lat_offset, lon_offset, altitude_offset
    raise RuntimeError("could not choose a safe one-time offset")


def _format_number(value: float) -> str:
    return f"{value:.8f}".rstrip("0").rstrip(".") or "0"


def _source_scalars(points: list[dict[str, object]]) -> tuple[set[float], set[float]]:
    coordinates = {float(point[key]) for point in points for key in ("lat", "lon")}
    elevations = {
        float(point["elevation"])
        for point in points
        if point["elevation"] is not None
    }
    return coordinates, elevations


def _build_xml(points: list[dict[str, object]], offsets: tuple[float, float, float]) -> bytes:
    lat_offset, lon_offset, elevation_offset = offsets
    root = ET.Element(f"{{{GPX_NS}}}gpx", {"version": "1.1", "creator": "TrailNav synthetic route fixture"})
    track = ET.SubElement(root, f"{{{GPX_NS}}}trk")
    segment = ET.SubElement(track, f"{{{GPX_NS}}}trkseg")
    for point in points:
        lat = float(point["lat"]) + lat_offset
        lon = (float(point["lon"]) + lon_offset + 180.0) % 360.0 - 180.0
        node = ET.SubElement(segment, f"{{{GPX_NS}}}trkpt", {"lat": _format_number(lat), "lon": _format_number(lon)})
        if point["elevation"] is not None:
            ET.SubElement(node, f"{{{GPX_NS}}}ele").text = _format_number(float(point["elevation"]) + elevation_offset)
    return ET.tostring(root, encoding="utf-8", xml_declaration=True) + b"\n"


def _validate_output(xml_bytes: bytes, source_points: list[dict[str, object]]) -> None:
    root = ET.fromstring(xml_bytes)
    output_points = [node for node in root.iter() if _local_name(node.tag) == "trkpt"]
    if any(_local_name(node.tag) in {"wpt", "rtept"} for node in root.iter()):
        raise ValueError("output contains a non-track route point")
    forbidden_tags = {"name", "time", "metadata", "extensions", "waypoint"}
    if any(_local_name(node.tag) in forbidden_tags for node in root.iter()):
        raise ValueError("output contains identifying metadata")
    source_coordinates, source_elevations = _source_scalars(source_points)
    output_coordinates: set[float] = set()
    output_elevations: set[float] = set()
    for point in output_points:
        output_coordinates.update((float(point.attrib["lat"]), float(point.attrib["lon"])))
        for child in point:
            if _local_name(child.tag) == "ele" and child.text:
                output_elevations.add(float(child.text))
    if source_coordinates & output_coordinates:
        raise ValueError("source coordinate scalar remains in output")
    if source_elevations & output_elevations:
        raise ValueError("source elevation scalar remains in output")
    if len(output_points) != len(source_points):
        raise ValueError("output point count changed after clipping")


def make_fixture(source: Path, start_m: float, end_m: float, output: Path) -> tuple[int, float]:
    if not source.is_file():
        raise FileNotFoundError("source GPX is not a file")
    if output.exists():
        raise FileExistsError("output fixture already exists")
    if start_m < 0 or end_m <= start_m:
        raise ValueError("distance range must be non-negative and increasing")
    parsed, _ = _read_route(source)
    route, cumulative = _filtered_route(parsed)
    if end_m > cumulative[-1]:
        raise ValueError("requested distance exceeds source route length")
    first, last = _nearest_index(cumulative, start_m), _nearest_index(cumulative, end_m)
    if last <= first:
        raise ValueError("distance range selects fewer than two route points")
    selected = route[first:last + 1]
    selected_length = cumulative[last] - cumulative[first]
    if selected_length > 1_000.0:
        raise ValueError("fixture segment must be shorter than 1 km")

    offsets = _pick_offsets(selected)
    xml_bytes = _build_xml(selected, offsets)
    _validate_output(xml_bytes, selected)
    output.parent.mkdir(parents=True, exist_ok=True)
    staging = output.parent / f".{output.name}.staging-{secrets.token_hex(8)}"
    staging.mkdir()
    try:
        (staging / "route.gpx").write_bytes(xml_bytes)
        output_points, _ = _read_route(staging / "route.gpx")
        filtered_output, output_cumulative = _filtered_route(output_points)
        if len(filtered_output) != len(selected):
            raise ValueError("anonymization changed the parser's 1 m point-filter result")
        output_length = output_cumulative[-1]
        (staging / "manifest.json").write_text(json.dumps({
            "schema_version": 1,
            "fixture_kind": "anonymized-real-route-shape",
            "source_distance_start_m": round(cumulative[first], 2),
            "source_distance_end_m": round(cumulative[last], 2),
            "requested_distance_start_m": round(start_m, 2),
            "requested_distance_end_m": round(end_m, 2),
            "point_count": len(selected),
            "clipped_length_m": round(selected_length, 2),
            "output_length_m": round(output_length, 6),
            "route": {"elevation_source": "synthetic", "waypoint_names": "synthetic"},
        }, indent=2) + "\n", encoding="utf-8")
        counts = scan_session(staging)
        if counts:
            raise ValueError("anonymized output was rejected by privacy scan")
        _validate_output((staging / "route.gpx").read_bytes(), selected)
        staging.replace(output)
    except BaseException:
        shutil.rmtree(staging, ignore_errors=True)
        raise
    return len(selected), selected_length


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--start-m", required=True, type=float)
    parser.add_argument("--end-m", required=True, type=float)
    parser.add_argument("--out", required=True, type=Path, help="new fixture directory containing route.gpx and manifest.json")
    args = parser.parse_args(argv)
    try:
        point_count, length = make_fixture(args.source, args.start_m, args.end_m, args.out)
    except (OSError, ValueError, ET.ParseError, RuntimeError) as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1
    print(f"PASS: source_alias={args.source.name[:12]} points={point_count} length_m={length:.2f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
