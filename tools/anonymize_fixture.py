#!/usr/bin/env python3
"""Create a non-identifying log-shape fixture from a local session.

The source is read only from a caller-supplied temporary directory. Random
coordinate/time offsets are generated inside this process, never accepted as
arguments and never printed. The output is staged, scanned, and atomically
published only after every required field kind has been transformed and no
source scalar remains verbatim (D-046).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import secrets
import shutil
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from xml.etree import ElementTree as ET

try:
    from privacy_scan import scan_session
except ModuleNotFoundError:  # package import in unit tests
    from tools.privacy_scan import scan_session


FIXTURE_NAMESPACE = uuid.UUID("e1c96d62-c24b-4f13-bf1c-1b61d8b8e57f")
REQUIRED_KINDS = {
    "loc.lat", "loc.lon", "loc.t", "guide.inputs.lat", "guide.inputs.lon",
    "guide.inputs.timestamp", "guide.t", "envelope.t", "sys.t",
    "manifest.started_at_wall", "route.gpx.coordinate",
}


def _normalize_longitude(value: float) -> float:
    """Return a longitude in the GPX/API half-open range [-180, 180)."""
    normalized = (value + 180.0) % 360.0 - 180.0
    return 0.0 if normalized == 0.0 else normalized


def _checked_latitude(value: float) -> float:
    if not -90.0 <= value <= 90.0:
        raise ValueError("anonymized latitude is outside [-90, 90]")
    return value


def _pick_offsets() -> tuple[float, float, float]:
    rng = secrets.SystemRandom()
    for _ in range(100):
        lat_offset = rng.uniform(-1.0, 1.0)
        lon_offset = rng.choice((-1.0, 1.0)) * rng.uniform(35.0, 70.0)
        if not (124.0 <= 127.0 + lon_offset <= 132.0):
            return lat_offset, lon_offset, -rng.uniform(180_000_000.0, 260_000_000.0)
    raise RuntimeError("could not choose a safe one-time offset")


def _shift_number(value: object, offset: float) -> object:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return value
    shifted = float(value) + offset
    return int(round(shifted)) if isinstance(value, int) else round(shifted, 6)


def _transform_json(value: object, path: tuple[str, ...], lat_offset: float, lon_offset: float, time_offset: float) -> object:
    if isinstance(value, list):
        return [_transform_json(item, path, lat_offset, lon_offset, time_offset) for item in value]
    if not isinstance(value, dict):
        return value
    result: dict[str, object] = {}
    stream = path[0] if path and path[0] in {"loc", "guide", "envelope", "sys"} else None
    for key, child in value.items():
        if key in {"lat", "latitude"} and isinstance(child, (int, float)) and not isinstance(child, bool):
            result[key] = _checked_latitude(float(child) + lat_offset)
        elif key in {"lon", "longitude"} and isinstance(child, (int, float)) and not isinstance(child, bool):
            result[key] = _normalize_longitude(float(child) + lon_offset)
        elif key == "t" and isinstance(child, (int, float)) and not isinstance(child, bool):
            result[key] = _shift_number(child, time_offset)
        elif key == "timestamp" and isinstance(child, (int, float)) and not isinstance(child, bool):
            result[key] = _shift_number(child, time_offset * 1000.0)
        elif key == "started_at_wall" and isinstance(child, str):
            parsed = datetime.fromisoformat(child.replace("Z", "+00:00"))
            result[key] = (parsed + timedelta(seconds=time_offset)).astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
        elif stream == "guide" and key == "state_hash":
            seq = value.get("src_seq", value.get("seq", 0))
            result[key] = "sha256:" + hashlib.sha256(f"fixture-state-{seq}".encode()).hexdigest()
        elif stream == "guide" and key == "output_text":
            result[key] = ""
        else:
            result[key] = _transform_json(child, (*path, key), lat_offset, lon_offset, time_offset)
    return result


def _transform_route(source: Path, target: Path, lat_offset: float, lon_offset: float) -> bytes:
    root = ET.fromstring(source.read_text(encoding="utf-8"))
    for point in root.iter():
        if point.tag.rsplit("}", 1)[-1] in {"trkpt", "rtept", "wpt"}:
            lat = _checked_latitude(float(point.attrib["lat"]) + lat_offset)
            lon = _normalize_longitude(float(point.attrib["lon"]) + lon_offset)
            point.set("lat", f"{lat:.8f}")
            point.set("lon", f"{lon:.8f}")
    data = ET.tostring(root, encoding="utf-8") + b"\n"
    target.write_bytes(data)
    return data


SENSITIVE_SCALAR_KEYS = {
    "lat", "lon", "latitude", "longitude", "t", "timestamp", "started_at_wall", "gpx_hash",
}


def _sensitive_scalars(value: object, key: str | None = None) -> set[str]:
    values: set[str] = set()
    if isinstance(value, dict):
        for child_key, child in value.items():
            values.update(_sensitive_scalars(child, child_key))
    elif isinstance(value, list):
        for child in value:
            values.update(_sensitive_scalars(child, key))
    elif key in SENSITIVE_SCALAR_KEYS and value is not None:
        values.add(str(value))
    return values


def _scalar_values(session: Path) -> set[str]:
    """Return only transformed source scalars, excluding stable schema text.

    Structural strings such as ``matching.stationary`` and fixed identifiers
    intentionally remain unchanged in a fixture.  Comparing every whitespace
    token therefore rejects valid output.  The fail-closed intersection is
    limited to fields that the anonymizer is required to transform.
    """
    values: set[str] = set()
    manifest_path = session / "manifest.json"
    if manifest_path.is_file():
        values.update(_sensitive_scalars(json.loads(manifest_path.read_text(encoding="utf-8"))))
    events_path = session / "events.ndjson"
    if events_path.is_file():
        for line in events_path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                values.update(_sensitive_scalars(json.loads(line)))
    route_path = session / "route.gpx"
    if route_path.is_file():
        root = ET.fromstring(route_path.read_text(encoding="utf-8"))
        for point in root.iter():
            if point.tag.rsplit("}", 1)[-1] in {"trkpt", "rtept", "wpt"}:
                values.update(point.attrib.get(name, "") for name in ("lat", "lon"))
    return {value for value in values if value}


def anonymize(source: Path, output: Path) -> None:
    route_source = source / "route.gpx"
    if not route_source.is_file():
        raise FileNotFoundError("source route.gpx is required")
    before = scan_session(source)
    missing = sorted(kind for kind in REQUIRED_KINDS if before.get(kind, 0) == 0)
    if missing:
        raise ValueError("source is missing required field kinds: " + ", ".join(missing))
    lat_offset, lon_offset, time_offset = _pick_offsets()
    source_scalars = _scalar_values(source)
    output.parent.mkdir(parents=True, exist_ok=True)
    staging = output.parent / f".{output.name}.staging-{secrets.token_hex(8)}"
    staging.mkdir(parents=True)
    try:
        manifest = json.loads((source / "manifest.json").read_text(encoding="utf-8"))
        events = [json.loads(line) for line in (source / "events.ndjson").read_text(encoding="utf-8").splitlines() if line.strip()]
        transformed_manifest = _transform_json(manifest, tuple(), lat_offset, lon_offset, time_offset)
        transformed_events = []
        for event in events:
            stream = event.get("stream") if isinstance(event, dict) else None
            transformed_events.append(_transform_json(event, (stream,) if isinstance(stream, str) else tuple(), lat_offset, lon_offset, time_offset))
        transformed_manifest["session_id"] = str(uuid.uuid5(FIXTURE_NAMESPACE, output.name + secrets.token_hex(8)))
        transformed_manifest["golden_status"] = "regenerated-after-D-043"
        transformed_manifest["fixture_purpose"] = "log-shape-regression"
        transformed_manifest["derived_src_seq"] = True
        transformed_manifest["guide_output_text"] = "cleared"
        route_bytes = _transform_route(route_source, staging / "route.gpx", lat_offset, lon_offset)
        route_meta = transformed_manifest.setdefault("route", {})
        if isinstance(route_meta, dict):
            route_meta["gpx_hash"] = "sha256:" + hashlib.sha256(route_bytes).hexdigest()
        (staging / "manifest.json").write_text(json.dumps(transformed_manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        (staging / "events.ndjson").write_text("\n".join(json.dumps(event, ensure_ascii=False, sort_keys=True) for event in transformed_events) + "\n", encoding="utf-8")
        after = scan_session(staging)
        if after:
            summary = ", ".join(f"{kind}:{count}" for kind, count in sorted(after.items()))
            raise ValueError("anonymized output still contains identifying fields: " + summary)
        if source_scalars & _scalar_values(staging):
            raise ValueError("source scalar value survived anonymization")
        if output.exists():
            shutil.rmtree(output)
        staging.replace(output)
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        raise


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv[1:])
    try:
        anonymize(args.source, args.output)
    except (OSError, ValueError, ET.ParseError, json.JSONDecodeError) as exc:
        print(f"ERROR: anonymization refused: {exc}")
        return 1
    print("PASS: anonymized fixture written; source values and identifying fields were removed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(__import__("sys").argv))
