#!/usr/bin/env python3
"""Executable R0 tests for the failure-signature extractor (D-047)."""
from __future__ import annotations

import json
import shutil
import unittest
from pathlib import Path

from preserve_failure_record import (
    ERROR_LINE,
    failed_job_log_text,
    first_meaningful_line,
)


ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "testdata" / "failure_extractor"


def legacy_first_meaningful_line(text: str) -> str:
    """The pre-R0 implementation, retained only for the negative control."""
    for line in text.splitlines():
        if line.strip() and (
            ERROR_LINE.search(line)
            or any(token in line.lower() for token in ("fail:", "failure:", "error:", "fatal:", "exception", "traceback"))
        ):
            return line
    return "no meaningful error line found"


class ExtractorTest(unittest.TestCase):
    def test_real_log_fixtures_choose_expected_priority(self) -> None:
        expected = json.loads((FIXTURES / "manifest.json").read_text(encoding="utf-8"))
        for name, wanted in expected.items():
            with self.subTest(fixture=name):
                actual = first_meaningful_line((FIXTURES / f"{name}.log").read_text(encoding="utf-8"))
                self.assertEqual(actual, wanted)

    def test_input_echo_only_uses_fallback(self) -> None:
        text = "\n".join(
            [
                "2026-09-21T00:00:00.0000000Z ##[group]Run gradle/actions/setup-gradle@v4",
                "2026-09-21T00:00:00.0000000Z   dependency-graph-continue-on-failure: true",
                "2026-09-21T00:00:00.0000000Z ##[endgroup]",
            ]
        )
        self.assertEqual(first_meaningful_line(text), "no meaningful error line found")

    def test_no_last_actions_error_uses_full_log(self) -> None:
        line = "2026-09-21T00:00:00.0000000Z e: file:///tmp/Main.kt:1:1 Unresolved reference 'x'."
        self.assertEqual(first_meaningful_line(line), line)

    def test_multiple_jobs_use_first_failed_job(self) -> None:
        root = FIXTURES / ".tmp-multiple-jobs"
        shutil.rmtree(root, ignore_errors=True)
        root.mkdir()
        try:
            (root / "failed_jobs.json").write_text(json.dumps({"failed_jobs": ["first", "second"]}), encoding="utf-8")
            (root / "job-1-first.log").write_text(
                "2026-09-21T00:00:00.0000000Z FAIL: first job\n", encoding="utf-8"
            )
            (root / "job-2-second.log").write_text(
                "2026-09-21T00:00:00.0000000Z FAIL: second job\n", encoding="utf-8"
            )
            self.assertEqual(
                first_meaningful_line(failed_job_log_text(root)),
                "2026-09-21T00:00:00.0000000Z FAIL: first job",
            )
        finally:
            shutil.rmtree(root, ignore_errors=True)


def run_legacy_negative_control() -> int:
    expected = json.loads((FIXTURES / "manifest.json").read_text(encoding="utf-8"))
    mismatches = 0
    for name, wanted in expected.items():
        actual = legacy_first_meaningful_line((FIXTURES / f"{name}.log").read_text(encoding="utf-8"))
        if actual != wanted:
            mismatches += 1
            print(f"MUTATION FAIL {name}: legacy={actual!r} expected={wanted!r}")
    print(f"MUTATION RESULT mismatches={mismatches} expected_nonzero=true")
    return 0 if mismatches else 1


if __name__ == "__main__":
    import sys

    if "--legacy-negative-control" in sys.argv:
        raise SystemExit(run_legacy_negative_control())
    unittest.main()
