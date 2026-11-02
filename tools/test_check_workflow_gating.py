#!/usr/bin/env python3
"""Positive and negative controls for guard.yml tier gating."""
from __future__ import annotations

from pathlib import Path

import check_workflow_gating as checker


WORKFLOW = Path(".github/workflows/guard.yml")
FULL_GATE = "github.event_name == 'schedule' || github.event_name == 'workflow_dispatch' || (github.event_name == 'pull_request' && contains(github.event.pull_request.labels.*.name, 'run-campaign'))"


def replace_job_block(workflow: str, job_name: str, transform) -> str:
    lines = workflow.splitlines()
    start = next(index for index, line in enumerate(lines) if line == f"  {job_name}:")
    end = next(
        (index for index in range(start + 1, len(lines)) if line_is_job_header(lines[index])),
        len(lines),
    )
    return "\n".join(lines[:start] + transform(lines[start:end]) + lines[end:]) + "\n"


def line_is_job_header(line: str) -> bool:
    return len(line) > 2 and line.startswith("  ") and not line.startswith("   ") and line.endswith(":")


def _must_fail(workflow: str, fragment: str) -> None:
    failures = checker.check_workflow_text(workflow)
    assert any(fragment in failure for failure in failures), failures


def test_normal_workflow_passes() -> None:
    assert checker.check_workflow_text(WORKFLOW.read_text(encoding="utf-8")) == []


def test_missing_campaign_label_is_rejected() -> None:
    workflow = WORKFLOW.read_text(encoding="utf-8")
    broken = replace_job_block(workflow, "negative-control-engine-shard", lambda lines: [
        line.replace(" || (github.event_name == 'pull_request' && contains(github.event.pull_request.labels.*.name, 'run-campaign'))", "")
        for line in lines
    ])
    _must_fail(broken, "run-campaign")


def test_missing_manual_dispatch_is_rejected() -> None:
    workflow = WORKFLOW.read_text(encoding="utf-8")
    broken = replace_job_block(workflow, "negative-control-engine-shard", lambda lines: [
        line.replace("github.event_name == 'workflow_dispatch' || ", "") for line in lines
    ])
    _must_fail(broken, "workflow_dispatch")


def test_aggregate_requires_always() -> None:
    workflow = WORKFLOW.read_text(encoding="utf-8")
    broken = replace_job_block(workflow, "negative-control-engine", lambda lines: [
        line.replace("always() && ", "") for line in lines
    ])
    _must_fail(broken, "must include always()")


def test_aggregate_always_without_gate_is_rejected() -> None:
    workflow = WORKFLOW.read_text(encoding="utf-8")
    broken = replace_job_block(workflow, "negative-control-engine", lambda lines: [
        "    if: always()" if line.startswith("    if:") else line for line in lines
    ])
    _must_fail(broken, "run-campaign")


def test_full_gate_leak_into_fast_job_is_rejected() -> None:
    workflow = WORKFLOW.read_text(encoding="utf-8")
    broken = replace_job_block(workflow, "purity", lambda lines: [
        lines[0], "    if: " + FULL_GATE, *lines[1:]
    ])
    _must_fail(broken, "purity: full-validation gate leaked")


def test_missing_schedule_trigger_is_rejected() -> None:
    workflow = WORKFLOW.read_text(encoding="utf-8")
    broken = workflow.replace("  schedule:\n    - cron: '0 18 * * *'\n", "")
    _must_fail(broken, "schedule with a cron")


def test_missing_pr_sample_count_is_rejected() -> None:
    workflow = WORKFLOW.read_text(encoding="utf-8")
    broken = replace_job_block(workflow, "core-guide-build", lambda lines: [
        line for line in lines if "START_PHASE_GRID_N" not in line
    ])
    _must_fail(broken, "must explicitly pass START_PHASE_GRID_N")


def test_unrestricted_push_is_rejected() -> None:
    workflow = WORKFLOW.read_text(encoding="utf-8")
    broken = workflow.replace("  push:\n    branches: [main]\n", "  push:\n")
    _must_fail(broken, "push must be restricted to main")


def run_all() -> None:
    test_normal_workflow_passes()
    test_missing_campaign_label_is_rejected()
    test_missing_manual_dispatch_is_rejected()
    test_aggregate_requires_always()
    test_aggregate_always_without_gate_is_rejected()
    test_full_gate_leak_into_fast_job_is_rejected()
    test_missing_schedule_trigger_is_rejected()
    test_missing_pr_sample_count_is_rejected()
    test_unrestricted_push_is_rejected()


if __name__ == "__main__":
    run_all()
    print("PASS: workflow tier checker accepts correct gating and rejects all counterexamples")
