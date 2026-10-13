#!/usr/bin/env python3
"""Offline tests for failure metrics and trigger-event records."""
from __future__ import annotations

import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

from failure_metrics import main as metrics_main
from preserve_failure_record import main as preserve_main


def failure(run_id: str, event: str | None = "push", schema: str = "failrec-2.0.3") -> dict:
    item = {"schema_version": schema, "run_id": run_id}
    if event is not None:
        item["trigger_event"] = event
    return item


def resolution(run_id: str) -> dict:
    return {"schema_version": "failrec-resolution-1.0.0", "run_id": run_id}


def run_metrics(values: list[dict]) -> dict[str, str]:
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        for index, value in enumerate(values):
            (root / f"{index}.json").write_text(json.dumps(value), encoding="utf-8")
        output = io.StringIO()
        with patch.object(sys, "argv", ["failure_metrics.py", str(root)]), redirect_stdout(output):
            result = metrics_main()
        if result != 0:
            raise AssertionError(f"failure_metrics.main returned {result}")
        return dict(line.split("=", 1) for line in output.getvalue().splitlines())


def run_preserve_record(event: str | None) -> dict:
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        logs = root / "logs"
        logs.mkdir()
        (logs / "job-main.log").write_text("FAIL: synthetic test failure\n", encoding="utf-8")
        out = root / "record.json"
        argv = [
            "preserve_failure_record.py", "--logs", str(logs), "--out", str(out),
            "--run-id", "700", "--commit-sha", "abc", "--branch", "main",
            "--created-at", "2026-10-13T00:00:00Z", "--runner-image-version", "test",
        ]
        if event is not None:
            argv.extend(["--trigger-event", event])
        with patch.object(sys, "argv", argv), patch("preserve_failure_record.toolchain", return_value={}), redirect_stdout(io.StringIO()):
            if preserve_main() != 0:
                raise AssertionError("preserve_failure_record.main did not write the synthetic record")
        return json.loads(out.read_text(encoding="utf-8"))


class FailureMetricsTest(unittest.TestCase):
    def test_f1_counts_push_pull_and_push_resolution_coverage(self):
        summary = run_metrics([
            failure("push-1"), failure("push-2"), failure("pr-1", "pull_request"), resolution("push-1"),
        ])
        self.assertEqual(summary["failures_push"], "2")
        self.assertEqual(summary["failures_pull_request"], "1")
        self.assertEqual(summary["resolved_push"], "1")
        self.assertEqual(summary["resolution_coverage_push"], "0.5000")

    def test_f2_pull_request_only_has_undefined_push_coverage(self):
        summary = run_metrics([failure("pr-1", "pull_request")])
        self.assertEqual(summary["failures_push"], "0")
        self.assertEqual(summary["resolution_coverage_push"], "undefined")

    def test_f3_missing_trigger_event_only_counts_in_total(self):
        summary = run_metrics([failure("unknown", None)])
        self.assertEqual(summary["failures_total"], "1")
        self.assertEqual(summary["failures_push"], "0")
        self.assertEqual(summary["failures_pull_request"], "0")

    def test_f4_pull_request_resolution_does_not_count_as_push_resolution(self):
        summary = run_metrics([failure("pr-1", "pull_request"), resolution("pr-1")])
        self.assertEqual(summary["resolved_push"], "0")

    def test_f5_new_schema_is_included_and_legacy_schema_is_excluded(self):
        summary = run_metrics([failure("new", schema="failrec-2.0.3"), failure("legacy", schema="failrec-1.0.0")])
        self.assertEqual(summary["failures_total"], "1")
        self.assertEqual(summary["failures_push"], "1")

    def test_f6_orphan_resolution_has_no_effect(self):
        summary = run_metrics([failure("push-1"), resolution("orphan")])
        self.assertEqual(summary["resolved_push"], "0")

    def test_f7_preserved_records_capture_trigger_event(self):
        self.assertEqual(run_preserve_record("push")["trigger_event"], "push")
        self.assertEqual(run_preserve_record(None)["trigger_event"], "unknown")


if __name__ == "__main__":
    unittest.main()
