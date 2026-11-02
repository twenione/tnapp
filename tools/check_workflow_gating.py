#!/usr/bin/env python3
"""Check that guard.yml keeps PR and full-validation tiers correctly gated."""
from __future__ import annotations

import argparse
import re
from pathlib import Path


FULL_GATE_PARTS = (
    "github.event_name == 'schedule'",
    "github.event_name == 'workflow_dispatch'",
    "contains(github.event.pull_request.labels.*.name, 'run-campaign')",
)
FULL_JOBS = ("negative-control-engine-shard", "negative-control-engine", "start-phase-grid-full")
FAST_JOBS = (
    "purity",
    "session-format",
    "commit-trailer",
    "negative-control",
    "phase1-accuracy",
    "config-sensitivity",
    "replay",
    "replay-determinism",
    "core-guide-build",
)
JOB_HEADER = re.compile(r"^  ([A-Za-z0-9_-]+):$")


def _top_level_section(lines: list[str], name: str) -> list[str] | None:
    start = next((i for i, line in enumerate(lines) if line == f"{name}:"), None)
    if start is None:
        # SCOPE: No trigger section exists, so there is no section body to return.
        return None
    # SCOPE: A top-level YAML key starts the next section.
    end = next((i for i in range(start + 1, len(lines)) if lines[i] and not lines[i].startswith(" ")), len(lines))
    return lines[start + 1:end]


def _job_blocks(workflow: str) -> dict[str, list[str]]:
    lines = workflow.splitlines()
    jobs_start = next((i for i, line in enumerate(lines) if line == "jobs:"), None)
    if jobs_start is None:
        # SCOPE: A missing jobs section has no job blocks to return.
        return {}
    blocks: dict[str, list[str]] = {}
    current: str | None = None
    block: list[str] = []
    for line in lines[jobs_start + 1:]:
        # SCOPE: The top-level jobs section ends before another unindented YAML key.
        if line and not line.startswith(" "):
            break
        header = JOB_HEADER.fullmatch(line)
        if header:
            if current is not None:
                blocks[current] = block
            current = header.group(1)
            block = []
        elif current is not None:
            block.append(line)
    if current is not None:
        blocks[current] = block
    return blocks


def _condition(block: list[str]) -> str:
    for line in block:
        match = re.match(r"^    if:\s*(.*?)\s*$", line)
        if match:
            return match.group(1)
    return ""


def check_workflow_text(workflow: str) -> list[str]:
    failures: list[str] = []
    lines = workflow.splitlines()
    triggers = _top_level_section(lines, "on")
    if triggers is None:
        failures.append("missing on trigger section")
        triggers = []
    trigger_text = "\n".join(triggers)
    if not re.search(r"(?m)^  schedule:\s*$", trigger_text) or not re.search(r"(?m)^\s+- cron:\s*['\"]?[^'\"\s]+", trigger_text):
        failures.append("on must include schedule with a cron")
    if not re.search(r"(?m)^  workflow_dispatch:\s*$", trigger_text):
        failures.append("on must include workflow_dispatch")
    pull_request = re.search(r"(?ms)^  pull_request:\s*\n(.*?)(?=^  [A-Za-z0-9_-]+:|\Z)", trigger_text)
    if pull_request is None or not re.search(r"(?m)^    types:.*\blabeled\b", pull_request.group(1)):
        failures.append("pull_request must include labeled in types")
    push = re.search(r"(?ms)^  push:\s*\n(.*?)(?=^  [A-Za-z0-9_-]+:|\Z)", trigger_text)
    if push is None or not re.search(r"(?m)^    branches:\s*\[\s*main\s*\]", push.group(1)):
        failures.append("push must be restricted to main")

    jobs = _job_blocks(workflow)
    for job in FULL_JOBS:
        block = jobs.get(job)
        if block is None:
            failures.append(f"missing full-validation job {job}")
            # SCOPE: A missing job cannot have a gate condition to validate.
            continue
        condition = _condition(block)
        for required in FULL_GATE_PARTS:
            if required not in condition:
                failures.append(f"{job}: if is missing {required}")
        if job == "negative-control-engine" and "always()" not in condition:
            failures.append("negative-control-engine: if must include always()")

    for job in FAST_JOBS:
        block = jobs.get(job)
        if block is None:
            failures.append(f"missing fast job {job}")
            # SCOPE: A missing job cannot leak a gate condition.
            continue
        condition = _condition(block)
        if "run-campaign" in condition or "schedule" in condition:
            failures.append(f"{job}: full-validation gate leaked into fast job if")

    core_build = "\n".join(jobs.get("core-guide-build", []))
    if "START_PHASE_GRID_N" not in core_build or "-PstartPhaseGridSampleCount=\"$START_PHASE_GRID_N\"" not in core_build:
        failures.append("core-guide-build must explicitly pass START_PHASE_GRID_N")
    if "check_grid_effective_n.py" not in core_build:
        failures.append("core-guide-build must verify the effective grid N")
    return failures


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("workflow", type=Path, nargs="?", default=Path(".github/workflows/guard.yml"))
    args = parser.parse_args()
    try:
        workflow = args.workflow.read_text(encoding="utf-8")
    except OSError as exc:
        print(f"FAIL: cannot read {args.workflow}: {exc}")
        return 1
    failures = check_workflow_text(workflow)
    if failures:
        print("\n".join(f"FAIL: {item}" for item in failures))
        return 1
    print(f"PASS: {args.workflow} keeps PR and full-validation gates separated")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
