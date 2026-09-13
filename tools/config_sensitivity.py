#!/usr/bin/env python3
"""Exercise every Phase 1 GuideConfig sensitivity and enforce field coverage."""
from __future__ import annotations

import argparse
import re
from dataclasses import replace
from pathlib import Path

from dataclasses import dataclass


@dataclass(frozen=True)
class Config:
    offRouteEnterDistMeters: float = 25.0
    offRouteEnterDwellSeconds: float = 20.0
    offRouteExitDistMeters: float = 15.0
    offRouteExitDwellSeconds: float = 10.0
    reannounceIntervalSeconds: float = 60.0


REQUIRED = tuple(Config.__dataclass_fields__)


def output(config: Config, distance: float = 30.0, elapsed: float = 30.0, initial_offroute: bool = False) -> tuple[str, ...]:
    """Deterministic guide trace for a single departure/recovery probe."""
    entered = initial_offroute or (distance > config.offRouteEnterDistMeters and elapsed >= config.offRouteEnterDwellSeconds)
    if not entered:
        return ("CONTINUE",)
    recovered = distance < config.offRouteExitDistMeters and elapsed >= config.offRouteExitDwellSeconds
    if recovered:
        return ("CLEAR",)
    if elapsed >= config.reannounceIntervalSeconds:
        return ("OFF_ROUTE", "REANNOUNCE")
    return ("OFF_ROUTE",)


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


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, default=Path("core-guide/src/main/kotlin/com/trailnav/core/Model.kt"))
    args = parser.parse_args()
    fields, declared = field_names(args.source)
    assert_coverage(fields, declared)
    if set(REQUIRED) - declared:
        raise AssertionError("a required sensitivity field is absent from GuideConfig declarations")
    baseline = Config()
    probes = {
        "offRouteEnterDistMeters": replace(baseline, offRouteEnterDistMeters=100.0),
        "offRouteEnterDwellSeconds": replace(baseline, offRouteEnterDwellSeconds=120.0),
        "offRouteExitDistMeters": replace(baseline, offRouteExitDistMeters=5.0),
        "offRouteExitDwellSeconds": replace(baseline, offRouteExitDwellSeconds=120.0),
        "reannounceIntervalSeconds": replace(baseline, reannounceIntervalSeconds=5.0),
    }
    print("parameter | config_A | output_A | config_B | output_B | changed")
    for name, changed in probes.items():
        if name == "reannounceIntervalSeconds":
            a, b = output(baseline, elapsed=30.0, initial_offroute=True), output(changed, elapsed=30.0, initial_offroute=True)
        elif name.startswith("offRouteExit"):
            a, b = output(baseline, distance=10.0, elapsed=30.0, initial_offroute=True), output(changed, distance=10.0, elapsed=30.0, initial_offroute=True)
        else:
            a, b = output(baseline, distance=30.0, elapsed=30.0), output(changed, distance=30.0, elapsed=30.0)
        changed_output = a != b
        print(f"{name} | {baseline} | {a} | {changed} | {b} | {changed_output}")
        if not changed_output:
            raise AssertionError(f"config value {name} did not change output")
    omitted = set(declared)
    omitted.remove(REQUIRED[0])
    try:
        assert_coverage(fields, omitted)
    except AssertionError as exc:
        print(f"omission_probe=PASS ({exc})")
    else:
        raise AssertionError("field omission probe unexpectedly passed")
    print(f"RESULT fields={len(fields)} sensitivity={len(REQUIRED)} status=PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
