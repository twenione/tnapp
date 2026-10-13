#!/usr/bin/env python3
"""Offline negative controls for resolution record existence checks."""
from __future__ import annotations

import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

from append_resolution import RecordLookupError, main


def run_main(messages, exists=None, token_present="yes"):
    with tempfile.TemporaryDirectory() as temporary:
        range_file = Path(temporary) / "range.json"
        range_file.write_text(json.dumps({"before": "a" * 40, "commits": []}), encoding="utf-8")
        argv = [
            "append_resolution.py", "--run-id", "900", "--head-sha", "b" * 40,
            "--token-present", token_present, "--range-file", str(range_file),
        ]
        git_messages = "".join(f"{sha}\0{message}\0" for sha, message in messages)
        git_result = subprocess.CompletedProcess(["git"], 0, stdout=git_messages, stderr="")
        stdout = io.StringIO()
        with patch.object(sys, "argv", argv), patch.dict(os.environ, {"FAILURE_LOG_TOKEN": "offline-test-token"}), \
             patch("append_resolution.subprocess.run", return_value=git_result), \
             patch("append_resolution.record_exists", side_effect=exists or []) as lookup, \
             patch("append_resolution.push_resolution", return_value=subprocess.CompletedProcess(["push"], 0, stdout="pushed\n", stderr="")) as push, \
             redirect_stdout(stdout):
            result = main()
        return result, stdout.getvalue(), lookup, push


class AppendResolutionTest(unittest.TestCase):
    def test_a1_all_existing_records_are_appended(self):
        result, output, lookup, push = run_main([
            ("1" * 40, "fix(build): first\n\nFailed-Run: 101\n"),
            ("2" * 40, "fix(build): second\n\nFailed-Run: 202\n"),
        ], [True, True])
        self.assertEqual(result, 0)
        self.assertEqual(lookup.call_count, 2)
        self.assertEqual(push.call_count, 2)
        self.assertIn('"appended": 2', output)

    def test_a2_only_existing_record_is_appended_before_missing_failure(self):
        result, output, lookup, push = run_main([
            ("1" * 40, "fix(build): present\n\nFailed-Run: 101\n"),
            ("2" * 40, "fix(build): missing\n\nFailed-Run: 202\n"),
        ], [True, False])
        self.assertEqual(result, 1)
        self.assertEqual(lookup.call_count, 2)
        self.assertEqual(push.call_count, 1)
        self.assertIn('"appended": 1', output)
        self.assertIn("202", output)

    def test_a3_missing_record_is_not_appended(self):
        result, output, _lookup, push = run_main([("1" * 40, "fix(build): missing\n\nFailed-Run: 101\n")], [False])
        self.assertEqual(result, 1)
        self.assertEqual(push.call_count, 0)
        self.assertIn('"appended": 0', output)

    def test_a4_unavailable_record_is_not_appended_and_is_undetermined(self):
        result, output, _lookup, push = run_main([
            ("1" * 40, "fix(build): present\n\nFailed-Run: 101\n"),
            ("2" * 40, "fix(build): unavailable\n\nFailed-Run: 202\n"),
        ], [True, RecordLookupError("HTTP 503")])
        self.assertEqual(result, 2)
        self.assertEqual(push.call_count, 1)
        self.assertIn("could not be verified", output)

    def test_a5_no_trailers_is_noop_without_lookup(self):
        result, output, lookup, push = run_main([], [])
        self.assertEqual(result, 0)
        lookup.assert_not_called()
        push.assert_not_called()
        self.assertIn("NOOP", output)

    def test_a6_missing_token_keeps_existing_exit_code(self):
        result, output, lookup, push = run_main([], [], token_present="no")
        self.assertEqual(result, 2)
        lookup.assert_not_called()
        push.assert_not_called()
        self.assertIn("FAILURE_LOG_TOKEN", output)

    def test_a7_resolution_uses_only_first_trailer_per_commit(self):
        result, output, lookup, push = run_main([
            ("1" * 40, "fix(build): multiple\n\nFailed-Run: 101\nFailed-Run: 202\n"),
        ], [True])
        self.assertEqual(result, 0)
        self.assertEqual(lookup.call_count, 1)
        self.assertEqual(lookup.call_args.args[0], "101")
        self.assertEqual(push.call_count, 1)
        self.assertIn('"run_id": "101"', output)


if __name__ == "__main__":
    unittest.main()
