#!/usr/bin/env python3
"""Check GuideConfig coverage and behavioural sensitivity via the real engine."""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

from replay import invoke, resolve_cli

REQUIRED = (
    "offRouteEnterDistMeters",
    "offRouteEnterDwellSeconds",
    "offRouteExitDistMeters",
    "offRouteExitDwellSeconds",
    "reannounceIntervalSeconds",
)


def field_names(source: Path) -> tuple[set[str], set[str]]:
    text = source.read_text(encoding="utf-8")
    block = re.search(r"data class GuideConfig\((.*?)\)\s*\{", text, re.S)
    if not block:
        raise ValueError("GuideConfig declaration not found")
    fields = set(re.findall(r"val\s+([A-Za-z][A-Za-z0-9_]*)\s*:", block.group(1)))
    required_block = re.search(r"sensitivityRequired\s*:\s*List<String>\s*=\s*listOf\((.*?)\)\s*", text, re.S)
    excluded_block = re.search(r"sensitivityExcluded\s*:\s*Map<String, String>\s*=\s*mapOf\((.*?)\)\s*\n", text, re.S)
    required = set(re.findall(r'"([A-Za-z][A-Za-z0-9_]*)"', required_block.group(1))) if required_block else set()
    excluded = set(re.findall(r'"([A-Za-z][A-Za-z0-9_]*)"\s+to', excluded_block.group(1))) if excluded_block else set()
    return fields, required | excluded


def assert_coverage(fields: set[str], declared: set[str]) -> None:
    missing = fields - declared
    unknown = declared - fields
    if missing or unknown:
        raise AssertionError(f"config field coverage mismatch missing={sorted(missing)} unknown={sorted(unknown)}")


def engine_output(cli: Path, overrides: dict[str, float]) -> tuple[tuple[str, str], ...]:
    command_root = Path(".")
    command = [str(cli), "--probe"]
    for name, value in overrides.items():
        command.extend(["--config", f"{name}={value}"])
    if cli.suffix.lower() in {".bat", ".cmd"}:
        command = ["cmd", "/c", *command]
    import subprocess

    result = subprocess.run(command, cwd=command_root, check=False, capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip() or f"engine exited {result.returncode}")
    frames = [json.loads(line) for line in result.stdout.splitlines() if line.strip()]
    return tuple((str(frame.get("decision", "")), str(frame.get("reason_rule", ""))) for frame in frames)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, default=Path("core-guide/src/main/kotlin/com/trailnav/core/Model.kt"))
    parser.add_argument("--cli", help="path to installed compiled engine CLI")
    args = parser.parse_args()
    fields, declared = field_names(args.source)
    assert_coverage(fields, declared)
    if set(REQUIRED) - declared:
        raise AssertionError("a required sensitivity field is absent from GuideConfig declarations")
    cli = resolve_cli(args.cli)
    baseline = engine_output(cli, {})
    probes = {
        "offRouteEnterDistMeters": {"offRouteEnterDistMeters": 100.0},
        "offRouteEnterDwellSeconds": {"offRouteEnterDwellSeconds": 120.0},
        "offRouteExitDistMeters": {"offRouteExitDistMeters": 5.0},
        "offRouteExitDwellSeconds": {"offRouteExitDwellSeconds": 120.0},
        "reannounceIntervalSeconds": {"reannounceIntervalSeconds": 5.0},
    }
    print("parameter | baseline_engine_trace | changed_engine_trace | changed")
    for name, overrides in probes.items():
        changed = engine_output(cli, overrides)
        did_change = baseline != changed
        print(f"{name} | {json.dumps(baseline, sort_keys=True)} | {json.dumps(changed, sort_keys=True)} | {did_change}")
        if not did_change:
            raise AssertionError(f"config value {name} did not change actual engine output")
    omitted = set(declared)
    omitted.remove(REQUIRED[0])
    try:
        assert_coverage(fields, omitted)
    except AssertionError as exc:
        print(f"omission_probe=PASS ({exc})")
    else:
        raise AssertionError("field omission probe unexpectedly passed")
    print(f"RESULT fields={len(fields)} sensitivity={len(REQUIRED)} engine_cli={cli} status=PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
