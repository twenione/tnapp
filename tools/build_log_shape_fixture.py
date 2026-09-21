#!/usr/bin/env python3
"""Build the checked-in log-shape fixture from an external session export.

The caller supplies a temporary source directory.  The builder keeps the first
601 location/guide pairs (the first 600 seconds), preserves the source event
ordering and the first 1,202 envelope events, reverses the GPX with the same
point-only transform used by ``RouteOrientation.reverseGpx``, then delegates
coordinate/time anonymization to ``anonymize_fixture``.  The compiled engine
is run against the anonymized output and its decision/rule stream is written
back into the guide events before the final replay check.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import tempfile
from collections import Counter
from pathlib import Path
from xml.etree import ElementTree as ET

from anonymize_fixture import anonymize


PAIR_COUNT = 601
REMOVED_SYS_KINDS = {"voice.recovered", "voice.on-route"}


def _load_events(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def _reverse_gpx(source: Path, target: Path) -> None:
    root = ET.fromstring(source.read_text(encoding="utf-8"))
    trk_points = [
        (float(node.attrib["lat"]), float(node.attrib["lon"]))
        for node in root.iter()
        if node.tag.rsplit("}", 1)[-1] == "trkpt"
    ]
    rte_points = [
        (float(node.attrib["lat"]), float(node.attrib["lon"]))
        for node in root.iter()
        if node.tag.rsplit("}", 1)[-1] == "rtept"
    ]
    points = trk_points or rte_points
    if len(points) < 2:
        raise ValueError("source route must contain at least two points")
    body = [
        '<gpx version="1.1" creator="TrailNav"><trk><trkseg>',
        *(f'<trkpt lat="{lat}" lon="{lon}"/>' for lat, lon in reversed(points)),
        "</trkseg></trk></gpx>",
    ]
    target.write_text("".join(body), encoding="utf-8")


def _cut_source(source: Path, target: Path) -> None:
    events = _load_events(source / "events.ndjson")
    loc = [event for event in events if event.get("stream") == "loc"]
    guide = [event for event in events if event.get("stream") == "guide"]
    if len(loc) < PAIR_COUNT or len(guide) < PAIR_COUNT:
        raise ValueError("source does not contain 601 location/guide pairs")
    selected_loc = loc[:PAIR_COUNT]
    selected_guide = guide[:PAIR_COUNT]
    final_t = float(selected_loc[-1]["t"])
    selected_envelope = [
        event for event in events
        if event.get("stream") == "envelope" and float(event["t"]) <= final_t
    ]
    source_sys = [
        event for event in events
        if event.get("stream") == "sys" and float(event["t"]) <= final_t
    ]
    selected_sys = [event for event in source_sys if event.get("kind") not in REMOVED_SYS_KINDS]
    removed = Counter(event.get("kind", "unknown") for event in source_sys if event.get("kind") in REMOVED_SYS_KINDS)
    if len(selected_envelope) != 1202 or len(selected_sys) != 16:
        raise ValueError(
            f"unexpected cut shape: envelopes={len(selected_envelope)} sys={len(selected_sys)} removed={dict(removed)}"
        )
    selected_seqs = {
        int(event["seq"])
        for event in [*selected_loc, *selected_guide, *selected_envelope, *selected_sys]
    }
    source_seq_by_guide = {
        int(event["seq"]): int(selected_loc[index]["seq"])
        for index, event in enumerate(selected_guide)
    }
    kept: list[dict] = []
    for event in events:
        seq = int(event["seq"])
        if seq not in selected_seqs:
            continue
        copied = json.loads(json.dumps(event))
        if copied.get("stream") == "guide":
            copied["src_seq"] = source_seq_by_guide[seq]
        kept.append(copied)
    target.mkdir(parents=True, exist_ok=True)
    (target / "events.ndjson").write_text(
        "\n".join(json.dumps(event, ensure_ascii=False, sort_keys=True) for event in kept) + "\n",
        encoding="utf-8",
    )
    manifest = json.loads((source / "manifest.json").read_text(encoding="utf-8"))
    manifest["fixture_purpose"] = "log-shape-regression"
    manifest.setdefault("route", {})["elevation_use"] = {"used": False, "reason": "absent"}
    manifest["route"]["waypoint_count"] = 0
    manifest["removed_events"] = {f"sys.{kind}": count for kind, count in sorted(removed.items())}
    (target / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    _reverse_gpx(source / "route.gpx", target / "route.gpx")


def _regenerate_guides(session: Path, cli: Path) -> None:
    command = [str(cli), "--route", str(session / "route.gpx"), "--session", str(session), "--emit-every-frame"]
    if cli.suffix.lower() in {".bat", ".cmd"}:
        command = ["cmd", "/c", *command]
    result = subprocess.run(command, check=False, capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip() or "engine CLI failed")
    outputs = [json.loads(line) for line in result.stdout.splitlines() if line.strip()]
    events = _load_events(session / "events.ndjson")
    guide_events = [event for event in events if event.get("stream") == "guide"]
    if len(outputs) != len(guide_events):
        raise ValueError(f"engine output count {len(outputs)} != guide count {len(guide_events)}")
    for event, output in zip(guide_events, outputs):
        if int(output["source_seq"]) != int(event["src_seq"]):
            raise ValueError("engine source sequence does not match guide source sequence")
        event["decision"] = output["decision"]
        details = {}
        if output.get("distance_m") is not None:
            details["distanceMeters"] = str(output["distance_m"])
        event["reason"] = {
            "rule": output["reason_rule"],
            "thresholds": {},
            "alternatives_considered": [],
            "details": details,
        }
        event["output_text"] = ""
    (session / "events.ndjson").write_text(
        "\n".join(json.dumps(event, ensure_ascii=False, sort_keys=True) for event in events) + "\n",
        encoding="utf-8",
    )


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--cli", type=Path, required=True)
    args = parser.parse_args(argv[1:])
    with tempfile.TemporaryDirectory(prefix="tnapp-log-shape-") as raw_dir_name:
        raw_dir = Path(raw_dir_name)
        _cut_source(args.source, raw_dir)
        anonymize(raw_dir, args.output)
    _regenerate_guides(args.output, args.cli)
    print("PASS: 600-second log-shape fixture built and regenerated through the compiled engine")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
