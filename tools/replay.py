#!/usr/bin/env python3
"""Replay a session through the compiled :core-guide engine."""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
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
    print("D-033 replay contract=PASS")
    return 0


def replay(root: Path, cli: Path, strict: bool, overrides: list[str] | None = None) -> int:
    if not (root / "events.ndjson").is_file() or not (root / "route.gpx").is_file():
        raise FileNotFoundError(f"session requires events.ndjson and route.gpx: {root}")
    trace = invoke(cli, root, overrides=overrides)
    comparisons = [item for item in trace if item.get("kind") == "guide"]
    mismatches: list[str] = []
    for item in comparisons:
        recorded = item.get("recorded_decision", "")
        actual = item.get("decision", "")
        status = "MATCH" if recorded == actual else "MISMATCH"
        print(
            f"guide seq={item.get('seq')} {status} actual={json.dumps(actual, sort_keys=True)} "
            f"recorded={json.dumps(recorded, sort_keys=True)} reason_rule={item.get('reason_rule')} "
            f"loc_index={item.get('loc_index')}"
        )
        if status == "MISMATCH":
            mismatches.append(f"seq {item.get('seq')}")
    print(f"RESULT comparisons={len(comparisons)} mismatches={len(mismatches)} strict={strict}")
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
            print(f"ERROR: contract probe failed: {exc}", file=sys.stderr)
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
        print(f"ERROR: replay failed: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
