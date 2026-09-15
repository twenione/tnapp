#!/usr/bin/env python3
"""Summarise failure records using the D-027 trigger denominator.

The private failure-log repository can run this tool against a checked-out
copy.  It never writes records and deliberately reports pull-request triggers
separately from the push denominator used for resolution coverage.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path


def records(root: Path) -> list[dict]:
    result: list[dict] = []
    for path in sorted(root.rglob("*.json")):
        try:
            value = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        if isinstance(value, dict) and value.get("schema_version", "").startswith("failrec-"):
            result.append(value)
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    values = records(args.root)
    failures = [item for item in values if not item["schema_version"].startswith("failrec-resolution-")]
    resolutions = [item for item in values if item["schema_version"].startswith("failrec-resolution-")]
    push_failures = [item for item in failures if item.get("trigger_event") == "push"]
    pull_failures = [item for item in failures if item.get("trigger_event") == "pull_request"]
    resolved_ids = {item.get("run_id") for item in resolutions}
    resolved_push = [item for item in push_failures if item.get("run_id") in resolved_ids]
    print(f"failures_total={len(failures)}")
    print(f"failures_push={len(push_failures)}")
    print(f"failures_pull_request={len(pull_failures)}")
    print(f"resolutions_total={len(resolutions)}")
    print(f"resolved_push={len(resolved_push)}")
    if not push_failures:
        print("resolution_coverage_push=undefined")
    else:
        print(f"resolution_coverage_push={len(resolved_push) / len(push_failures):.4f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
