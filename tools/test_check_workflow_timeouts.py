#!/usr/bin/env python3
"""Positive and negative controls for guard.yml timeout enforcement."""
from __future__ import annotations

from pathlib import Path

import check_workflow_timeouts as checker


WORKFLOW = Path(".github/workflows/guard.yml")


def replace_job_block(workflow: str, job_name: str, transform) -> str:
    lines = workflow.splitlines()
    start = next(index for index, line in enumerate(lines) if line == f"  {job_name}:")
    end = next(
        (index for index in range(start + 1, len(lines)) if line_is_job_header(lines[index])),
        len(lines),
    )
    updated = transform(lines[start:end])
    return "\n".join(lines[:start] + updated + lines[end:]) + "\n"


def line_is_job_header(line: str) -> bool:
    return len(line) > 2 and line.startswith("  ") and not line.startswith("   ") and line.endswith(":")


def test_normal_workflow_passes() -> None:
    failures, timeouts = checker.check_workflow_text(WORKFLOW.read_text(encoding="utf-8"))
    assert failures == []
    assert len(timeouts) == 11


def test_missing_timeout_is_rejected() -> None:
    workflow = WORKFLOW.read_text(encoding="utf-8")
    broken = replace_job_block(
        workflow,
        "purity",
        lambda lines: [line for line in lines if line != "    timeout-minutes: 15"],
    )
    failures, _ = checker.check_workflow_text(broken)
    assert any("purity: expected exactly one" in failure for failure in failures)


def test_string_zero_negative_and_over_limit_timeouts_are_rejected() -> None:
    workflow = WORKFLOW.read_text(encoding="utf-8")
    for value in ('"15"', "0", "-1", "361"):
        broken = replace_job_block(
            workflow,
            "purity",
            lambda lines, value=value: [
                f"    timeout-minutes: {value}" if line == "    timeout-minutes: 15" else line
                for line in lines
            ],
        )
        failures, _ = checker.check_workflow_text(broken)
        assert failures, value


def run_all() -> None:
    test_normal_workflow_passes()
    test_missing_timeout_is_rejected()
    test_string_zero_negative_and_over_limit_timeouts_are_rejected()


if __name__ == "__main__":
    run_all()
    print("PASS: timeout checker accepts normal jobs and rejects missing/invalid bounds")
