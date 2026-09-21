#!/usr/bin/env python3
"""Replay a session through the compiled :core-guide engine."""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from xml.etree import ElementTree as ET
from pathlib import Path


def resolve_cli(explicit: str | None) -> Path:
    candidates = []
    if explicit:
        candidates.append(Path(explicit))
    env_path = os.environ.get("TRAILNAV_ENGINE_CLI")
    if env_path:
        candidates.append(Path(env_path))
    candidates.extend([Path("replay/build/install/replay/bin/replay"), Path("replay/build/install/replay/bin/replay.bat")])
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    searched = ", ".join(str(path) for path in candidates)
    raise FileNotFoundError(f"compiled engine CLI not found; searched: {searched}")


def invoke(
    cli: Path,
    root: Path,
    *,
    every_frame: bool = False,
    overrides: list[str] | None = None,
) -> list[dict]:
    command = [str(cli), "--route", str(root / "route.gpx"), "--session", str(root)]
    if every_frame:
        command.append("--emit-every-frame")
    for override in overrides or []:
        command.extend(["--config", override])
    if cli.suffix.lower() in {".bat", ".cmd"}:
        command = ["cmd", "/c", *command]
    result = subprocess.run(command, check=False, capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip() or f"engine exited {result.returncode}")
    return [json.loads(line) for line in result.stdout.splitlines() if line.strip()]


def invoke_probe(cli: Path, overrides: list[str] | None = None) -> list[dict]:
    command = [str(cli), "--probe"]
    for override in overrides or []:
        command.extend(["--config", override])
    if cli.suffix.lower() in {".bat", ".cmd"}:
        command = ["cmd", "/c", *command]
    result = subprocess.run(command, check=False, capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip() or f"engine exited {result.returncode}")
    return [json.loads(line) for line in result.stdout.splitlines() if line.strip()]


def invoke_subsecond_probe(cli: Path) -> list[dict]:
    """Run the compiled engine's subsecond timestamp regression probe."""
    command = [str(cli), "--probe-subsecond"]
    if cli.suffix.lower() in {".bat", ".cmd"}:
        command = ["cmd", "/c", *command]
    result = subprocess.run(command, check=False, capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip() or f"engine exited {result.returncode}")
    return [json.loads(line) for line in result.stdout.splitlines() if line.strip()]


def route_elevation_use(route_path: Path) -> dict[str, object]:
    """Recompute the GPX-only elevation availability contract without using engine state."""
    root = ET.fromstring(route_path.read_text(encoding="utf-8"))
    points = [node for node in root.iter() if node.tag.rsplit("}", 1)[-1] in {"trkpt", "rtept"}]
    values = [next((child.text for child in node if child.tag.rsplit("}", 1)[-1] == "ele"), None) for node in points]
    present = sum(value is not None and value.strip() != "" for value in values)
    if not values or present == 0:
        reason = "absent"
    elif present != len(values):
        reason = "partial"
    else:
        try:
            numeric = [float(value) for value in values if value is not None]
            reason = "unstable" if any(abs(b - a) > 1000.0 for a, b in zip(numeric, numeric[1:])) else "ok"
        except ValueError:
            reason = "unstable"
    return {"used": reason == "ok", "reason": reason}


def details_match(recorded: object, actual: object) -> bool:
    """Compare recorded reason details, allowing serialization-level float noise."""
    if not isinstance(recorded, dict) or not isinstance(actual, dict):
        return recorded == actual
    for key, value in recorded.items():
        if key not in actual:
            return False
        left, right = str(value), str(actual[key])
        try:
            # GPX projection and JVM/Python JSON serialization can differ by
            # a few millimetres; retain semantic detail comparison while
            # ignoring that representation noise.
            if abs(float(left) - float(right)) > 1.0:
                return False
        except ValueError:
            if left != right:
                return False
    return True


def contract_probe(cli: Path) -> int:
    """Check D-033 dwell and caller-config contracts through the real CLI."""
    dwell = invoke_probe(cli, ["offRouteEnterDwellSeconds=20"])
    dwell_decisions = [item.get("decision") for item in dwell]
    expected_dwell = ["CONTINUE", "CONTINUE", "OFF_ROUTE", "CONTINUE", "CONTINUE", "CONTINUE"]
    if dwell_decisions != expected_dwell:
        print(f"FAIL D-033 dwell contract actual={dwell_decisions} expected={expected_dwell}")
        return 1
    config = invoke_probe(cli, ["offRouteEnterDistMeters=100", "offRouteEnterDwellSeconds=0"])
    config_decisions = [item.get("decision") for item in config]
    if any(decision == "OFF_ROUTE" for decision in config_decisions):
        print(f"FAIL D-033 config contract actual={config_decisions}")
        return 1
    subsecond_decisions = [item.get("decision") for item in invoke_subsecond_probe(cli)]
    if subsecond_decisions != ["CONTINUE", "CONTINUE", "CONTINUE"]:
        print(f"FAIL elapsed-ms subsecond contract actual={subsecond_decisions}")
        return 1
    print("D-033 replay contract=PASS")
    return 0


def replay(root: Path, cli: Path, strict: bool, overrides: list[str] | None = None) -> int:
    if not (root / "events.ndjson").is_file() or not (root / "route.gpx").is_file():
        raise FileNotFoundError(f"session requires events.ndjson and route.gpx: {root}")
    events = [json.loads(line) for line in (root / "events.ndjson").read_text(encoding="utf-8").splitlines() if line.strip()]
    loc_events = [event for event in events if event.get("stream") == "loc"]
    guide_events = [event for event in events if event.get("stream") == "guide"]
    frame_guide_events = [event for event in guide_events if "trigger" not in event]
    if len(loc_events) != len(frame_guide_events):
        print(f"FAIL: loc event count {len(loc_events)} does not match frame guide event count {len(frame_guide_events)}")
        return 1
    trace = invoke(cli, root, overrides=overrides)
    comparisons = [item for item in trace if item.get("kind") == "guide"]
    trigger_trace = [item for item in trace if item.get("kind") == "trigger"]
    if len(comparisons) != len(frame_guide_events):
        print(f"FAIL: engine comparison count {len(comparisons)} does not match frame guide event count {len(frame_guide_events)}")
        return 1
    mismatches: list[str] = []
    recorded_frame_guides = [event for event in guide_events if "trigger" not in event]
    recorded_triggers = [event for event in guide_events if "trigger" in event]
    if len(recorded_triggers) != len(trigger_trace):
        mismatches.append("trigger count")
        print(f"trigger count actual={len(trigger_trace)} recorded={len(recorded_triggers)}")
    loc_seq_set = {event.get("seq") for event in loc_events}
    for trigger in recorded_triggers:
        if trigger.get("src_seq") not in loc_seq_set:
            mismatches.append(f"trigger src_seq {trigger.get('src_seq')}")
    for index, item in enumerate(comparisons):
        expected_source_seq = loc_events[index].get("seq")
        if item.get("source_seq") != expected_source_seq:
            mismatches.append(f"src_seq index {index}")
            print(f"guide index={index} MISMATCH source_seq={item.get('source_seq')} expected={expected_source_seq}")
        recorded_source_seq = item.get("recorded_src_seq")
        if recorded_source_seq is not None and recorded_source_seq != expected_source_seq:
            mismatches.append(f"recorded src_seq index {index}")
            print(f"guide index={index} MISMATCH recorded_src_seq={recorded_source_seq} expected={expected_source_seq}")
        recorded_event = recorded_frame_guides[index] if index < len(recorded_frame_guides) else {}
        recorded = recorded_event.get("decision", item.get("recorded_decision", ""))
        actual = item.get("decision", "")
        recorded_rule = recorded_event.get("reason", {}).get("rule", "") if isinstance(recorded_event.get("reason"), dict) else ""
        actual_rule = item.get("reason_rule", "")
        recorded_details = recorded_event.get("reason", {}).get("details", {}) if isinstance(recorded_event.get("reason"), dict) else {}
        actual_details = item.get("reason_details", {})
        detail_ok = details_match(recorded_details, actual_details)
        status = "MATCH" if recorded == actual and recorded_rule == actual_rule and detail_ok else "MISMATCH"
        print(
            f"guide seq={item.get('seq')} {status} actual={json.dumps(actual, sort_keys=True)} "
            f"recorded={json.dumps(recorded, sort_keys=True)} reason_rule={actual_rule} recorded_rule={recorded_rule} "
            f"loc_index={item.get('loc_index')}"
        )
        if status == "MISMATCH":
            mismatches.append(f"seq {item.get('seq')}")
    manifest_path = root / "manifest.json"
    if manifest_path.is_file():
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        recorded_use = manifest.get("route", {}).get("elevation_use") or {
            "used": manifest.get("route", {}).get("elevation_used"),
            "reason": manifest.get("route", {}).get("elevation_reason"),
        }
        if recorded_use.get("reason"):
            actual_use = route_elevation_use(root / "route.gpx")
            if recorded_use.get("reason") != actual_use["reason"] or bool(recorded_use.get("used")) != bool(actual_use["used"]):
                mismatches.append("manifest elevation_use")
                print(f"elevation_use MISMATCH actual={actual_use} recorded={recorded_use}")
    print(f"RESULT comparisons={len(comparisons)} triggers={len(trigger_trace)} mismatches={len(mismatches)} strict={strict}")
    if mismatches and strict:
        print("FAIL: " + ", ".join(mismatches))
        return 1
    if mismatches:
        print("WARN: mismatches ignored without --strict")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("session_dir", type=Path, nargs="?")
    parser.add_argument("--cli", help="path to the installed compiled engine CLI")
    parser.add_argument("--strict", action="store_true")
    parser.add_argument("--config", action="append", default=[], metavar="NAME=VALUE")
    parser.add_argument("--contract", action="store_true", help="run the D-033 engine contract probe")
    args = parser.parse_args(argv[1:])
    if args.contract:
        try:
            return contract_probe(resolve_cli(args.cli))
        except (OSError, RuntimeError, json.JSONDecodeError) as exc:
            print(f"FAIL: contract probe failed: {exc}", file=sys.stderr)
            return 2
    if args.session_dir is None:
        print("ERROR: session directory is required unless --contract is used", file=sys.stderr)
        return 2
    if not args.session_dir.is_dir():
        print(f"ERROR: session directory missing: {args.session_dir}", file=sys.stderr)
        return 2
    try:
        return replay(args.session_dir, resolve_cli(args.cli), args.strict, args.config)
    except (OSError, RuntimeError, json.JSONDecodeError) as exc:
        print(f"FAIL: replay failed: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
