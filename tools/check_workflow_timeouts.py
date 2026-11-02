#!/usr/bin/env python3
"""Require a bounded integer timeout on every job in guard.yml."""
from __future__ import annotations

import argparse
import re
from pathlib import Path


JOB_HEADER = re.compile(r"^  ([A-Za-z0-9_-]+):$")
TIMEOUT_LINE = re.compile(r"^    timeout-minutes:\s*(.*?)\s*(?:#.*)?$")


def check_workflow_text(workflow: str) -> tuple[list[str], list[tuple[str, int]]]:
    lines = workflow.splitlines()
    jobs_line = next((index for index, line in enumerate(lines) if line == "jobs:"), None)
    if jobs_line is None:
        return ["missing top-level jobs section"], []
    jobs: list[tuple[str, list[str]]] = []
    current_name: str | None = None
    current_lines: list[str] = []
    for line in lines[jobs_line + 1:]:
        header = JOB_HEADER.fullmatch(line)
        if header:
            if current_name is not None:
                jobs.append((current_name, current_lines))
            current_name = header.group(1)
            current_lines = []
        elif current_name is not None:
            if line and line[:1] != " ":
                break
            current_lines.append(line)
    if current_name is not None:
        jobs.append((current_name, current_lines))
    if not jobs:
        return ["no jobs found under jobs"], []

    failures: list[str] = []
    timeouts: list[tuple[str, int]] = []
    for name, block in jobs:
        values = [match.group(1).strip() for line in block if (match := TIMEOUT_LINE.fullmatch(line))]
        if len(values) != 1:
            failures.append(f"{name}: expected exactly one timeout-minutes, found {len(values)}")
        else:
            value = values[0]
            if not re.fullmatch(r"[0-9]+", value):
                failures.append(f"{name}: timeout-minutes must be an unquoted integer, got {value!r}")
            else:
                minutes = int(value)
                if not 1 <= minutes <= 360:
                    failures.append(f"{name}: timeout-minutes {minutes} outside 1..360")
                else:
                    timeouts.append((name, minutes))
    return failures, timeouts


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("workflow", type=Path, nargs="?", default=Path(".github/workflows/guard.yml"))
    args = parser.parse_args()
    try:
        source = args.workflow.read_text(encoding="utf-8")
    except OSError as exc:
        print(f"FAIL: cannot read {args.workflow}: {exc}")
        return 1
    failures, timeouts = check_workflow_text(source)
    if failures:
        print("\n".join(f"FAIL: {failure}" for failure in failures))
        return 1
    print(
        f"PASS: {args.workflow} has timeout-minutes for {len(timeouts)} jobs "
        f"(min={min(value for _, value in timeouts)}, max={max(value for _, value in timeouts)})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
