#!/usr/bin/env python3
"""Executable R0 tests for the failure-signature extractor (D-047)."""
from __future__ import annotations

import json
import io
import shutil
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

from preserve_failure_record import (
    EMPTY_LOG_SHA256,
    ERROR_LINE,
    SCHEMA_VERSION,
    empty_log_mismatches,
    failed_job_log_text,
    first_meaningful_line,
    is_action_input_echo,
    log_is_empty,
    selection_rule_fingerprint,
    signature_is_run_echo,
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

    def test_what_went_wrong_cases(self) -> None:
        prefix = "2026-09-21T00:00:00.0000000Z "
        self.assertEqual(
            first_meaningful_line("\n".join([
                prefix + "* What went wrong:",
                "",
                prefix + "Plugin [id: 'x'] was not found",
                prefix + "##[error]Process completed with exit code 1.",
            ])),
            prefix + "Plugin [id: 'x'] was not found",
        )
        self.assertEqual(
            first_meaningful_line("\n".join([
                prefix + "* What went wrong:",
                prefix + "##[warning]continuation marker",
                prefix + "##[error]Process completed with exit code 1.",
            ])),
            prefix + "##[error]Process completed with exit code 1.",
        )
        self.assertEqual(
            first_meaningful_line("\n".join([
                prefix + "* What went wrong:",
                prefix + "##[error]Process completed with exit code 1.",
            ])),
            prefix + "##[error]Process completed with exit code 1.",
        )
        self.assertEqual(
            first_meaningful_line("\n".join([
                prefix + "##[group]Run previous",
                prefix + "* What went wrong:",
                prefix + "old failure",
                prefix + "##[endgroup]",
                prefix + "##[error]Process completed with exit code 1.",
            ])),
            prefix + "##[error]Process completed with exit code 1.",
        )

    def test_position_based_echo_classifier(self) -> None:
        echo = "2026-09-21T00:00:00.0000000Z dependency-graph-continue-on-failure: true"
        inside = "\n".join([
            "2026-09-21T00:00:00.0000000Z ##[group]Run setup",
            echo,
            "2026-09-21T00:00:00.0000000Z ##[endgroup]",
        ])
        self.assertTrue(signature_is_run_echo(inside, echo))
        self.assertTrue(is_action_input_echo(inside, echo))
        self.assertFalse(signature_is_run_echo(echo, echo))
        self.assertFalse(signature_is_run_echo(inside + "\n" + echo, echo))

    def test_cli_warning_branch_executes(self) -> None:
        directory = str(FIXTURES / ".tmp-cli-warning")
        shutil.rmtree(directory, ignore_errors=True)
        Path(directory).mkdir()
        try:
            root = Path(directory)
            logs = root / "logs"
            logs.mkdir()
            (logs / "job-main.log").write_text("echo\n", encoding="utf-8")
            out = root / "out.json"
            argv = [
                "preserve_failure_record.py", "--logs", str(logs), "--out", str(out),
                "--run-id", "1", "--commit-sha", "abc", "--branch", "main",
                "--created-at", "2026-09-21T00:00:00Z", "--runner-image-version", "image",
            ]
            stdout = io.StringIO()
            with patch.object(sys, "argv", argv), patch("preserve_failure_record.first_meaningful_line", return_value="key: value"), patch("preserve_failure_record.is_action_input_echo", return_value=True), patch("preserve_failure_record.toolchain", return_value={}), patch("sys.stdout", stdout):
                from preserve_failure_record import main
                self.assertEqual(main(), 0)
            self.assertIn("::warning::error_signature matched", stdout.getvalue())
            self.assertEqual(json.loads(out.read_text(encoding="utf-8"))["schema_version"], SCHEMA_VERSION)
        finally:
            shutil.rmtree(directory, ignore_errors=True)

    def test_cli_actual_gradle_fixture_uses_202(self) -> None:
        directory = FIXTURES / ".tmp-cli-actual"
        shutil.rmtree(directory, ignore_errors=True)
        (directory / "logs").mkdir(parents=True)
        shutil.copy(FIXTURES / "08_gradle_what_went_wrong.log", directory / "logs" / "job-main.log")
        try:
            argv = [
                "preserve_failure_record.py", "--logs", str(directory / "logs"), "--out", str(directory / "record.json"),
                "--run-id", "actual-08", "--commit-sha", "abc", "--branch", "main", "--created-at", "2026-09-21T00:00:00Z",
                "--runner-image-version", "synthetic", "--trigger-event", "push", "--repo-root", str(ROOT),
            ]
            with patch.object(sys, "argv", argv):
                from preserve_failure_record import main
                self.assertEqual(main(), 0)
            record = json.loads((directory / "record.json").read_text(encoding="utf-8"))
            self.assertEqual(record["schema_version"], SCHEMA_VERSION)
            self.assertEqual(record["error_signature"], json.loads((FIXTURES / "manifest.json").read_text(encoding="utf-8"))["08_gradle_what_went_wrong"])
        finally:
            shutil.rmtree(directory, ignore_errors=True)

    def test_rule_fingerprint_mutations_change(self) -> None:
        self.assertNotEqual(selection_rule_fingerprint(), selection_rule_fingerprint(inline_cases=(("changed", "case"),)))


class FailureRecordGuardTest(unittest.TestCase):
    def _run_record(self, logs_exist: bool, log_text: str | None) -> tuple[int, str, Path]:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        logs = root / "logs"
        if logs_exist:
            logs.mkdir()
            if log_text is not None:
                (logs / "job-main.log").write_text(log_text, encoding="utf-8")
        output = root / "record.json"
        argv = [
            "preserve_failure_record.py", "--logs", str(logs), "--out", str(output),
            "--run-id", "7", "--commit-sha", "abc", "--branch", "main",
            "--created-at", "2026-10-13T00:00:00Z", "--runner-image-version", "test",
        ]
        stdout = io.StringIO()
        with patch.object(sys, "argv", argv), patch("preserve_failure_record.toolchain", return_value={}), redirect_stdout(stdout):
            from preserve_failure_record import main
            result = main()
        return result, stdout.getvalue(), output

    def test_empty_log_inputs_are_rejected_without_output(self) -> None:
        for name, logs_exist, log_text in (
            ("whitespace", True, "  \n\t"),
            ("zero_bytes", True, ""),
            ("missing_directory", False, None),
        ):
            with self.subTest(case=name):
                result, output, record_path = self._run_record(logs_exist, log_text)
                self.assertEqual(result, 1)
                self.assertIn("FAIL: refusing to write an empty failure log", output)
                self.assertFalse(record_path.exists())

    def test_nonempty_log_is_still_written(self) -> None:
        result, _output, record_path = self._run_record(True, "FAIL: synthetic failure\n")
        self.assertEqual(result, 0)
        self.assertTrue(record_path.is_file())

    def test_empty_log_digest_is_independently_correct(self) -> None:
        self.assertEqual(
            EMPTY_LOG_SHA256,
            "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        )

    def test_empty_log_classifier_checks_both_branches(self) -> None:
        self.assertTrue(log_is_empty("x", EMPTY_LOG_SHA256))
        self.assertTrue(log_is_empty("   \n", "sha256:" + "d" * 64))
        self.assertFalse(log_is_empty("x", "sha256:" + "d" * 64))
        self.assertEqual(empty_log_mismatches(log_is_empty), [])
        self.assertTrue(empty_log_mismatches(lambda _log, _digest: False))
        self.assertTrue(empty_log_mismatches(lambda _log, digest: digest == EMPTY_LOG_SHA256))
        self.assertTrue(empty_log_mismatches(lambda log, _digest: not log.strip()))

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
