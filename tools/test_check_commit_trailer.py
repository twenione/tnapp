#!/usr/bin/env python3
"""Offline negative controls for Failed-Run trailer verification."""
from __future__ import annotations

import io
import os
import subprocess
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch

from check_commit_trailer import RunLookupError, check_messages, main


def evaluate(messages, lookup=None):
    output = io.StringIO()
    with redirect_stdout(output):
        result = check_messages(messages, lookup, "twenione/tnapp")
    return result, output.getvalue()


def git(root: Path, *args: str) -> str:
    result = subprocess.run(["git", *args], cwd=root, text=True, capture_output=True)
    if result.returncode:
        raise AssertionError(result.stderr)
    return result.stdout.strip()


def init_repo(root: Path) -> None:
    git(root, "init", "--quiet")
    git(root, "config", "user.name", "TASK-061 test")
    git(root, "config", "user.email", "task061@example.invalid")


def commit(root: Path, filename: str, subject: str, trailer: str | None = None) -> str:
    path = root / filename
    path.write_text(subject + "\n", encoding="utf-8")
    git(root, "add", filename)
    args = ["commit", "--quiet", "-m", subject]
    if trailer is not None:
        args += ["-m", f"Failed-Run: {trailer}"]
    git(root, *args)
    return git(root, "rev-parse", "HEAD")


class CheckCommitTrailerTest(unittest.TestCase):
    def test_t1_failure_run_is_accepted(self):
        result, _ = evaluate([("a" * 40, "fix(build): repair\n\nFailed-Run: 123\n")], lambda _run: "failure")
        self.assertEqual(result, 0)

    def test_t2_missing_run_is_rejected(self):
        result, output = evaluate([("a" * 40, "chore: record\n\nFailed-Run: 123\n")], lambda _run: None)
        self.assertEqual(result, 1)
        self.assertIn("does not exist", output)

    def test_t3_successful_run_is_rejected(self):
        result, output = evaluate([("a" * 40, "fix(build): repair\n\nFailed-Run: 123\n")], lambda _run: "success")
        self.assertEqual(result, 1)
        self.assertIn("success", output)

    def test_t4_running_run_is_rejected(self):
        result, _ = evaluate([("a" * 40, "fix(build): repair\n\nFailed-Run: 123\n")], lambda _run: "")
        self.assertEqual(result, 1)

    def test_t5_non_numeric_values_are_rejected(self):
        for value in ("abc", "https://example.invalid/actions/runs/12", "123abc"):
            with self.subTest(value=value):
                result, _ = evaluate([("a" * 40, f"chore: record\n\nFailed-Run: {value}\n")])
                self.assertEqual(result, 1)

    def test_t6_lookup_error_is_undetermined(self):
        def unavailable(_run):
            raise RunLookupError("HTTP 503")
        result, output = evaluate([("a" * 40, "fix(build): repair\n\nFailed-Run: 123\n")], unavailable)
        self.assertEqual(result, 2)
        self.assertIn("UNDETERMINED", output)

    def test_t7_build_fix_without_trailer_still_fails(self):
        result, _ = evaluate([("a" * 40, "fix(build): repair\n")])
        self.assertEqual(result, 1)

    def test_t8_other_prefix_trailer_is_still_verified(self):
        result, output = evaluate([("a" * 40, "chore: record\n\nFailed-Run: 123\n")], lambda _run: None)
        self.assertEqual(result, 1)
        self.assertIn("does not exist", output)

    def test_t9_fix_builds_without_trailer_preserves_known_gap(self):
        # D-120 §3: missing-trailer enforcement only applies to fix(build):.
        result, _ = evaluate([("a" * 40, "fix(builds): repair\n")])
        self.assertEqual(result, 0)

    def test_t10_duplicate_run_is_looked_up_once(self):
        calls = []
        result, _ = evaluate([
            ("a" * 40, "chore: first\n\nFailed-Run: 123\n"),
            ("b" * 40, "chore: second\n\nFailed-Run: 123\n"),
        ], lambda run: calls.append(run) or "failure")
        self.assertEqual(result, 0)
        self.assertEqual(calls, ["123"])

    def test_t11_all_trailers_are_checked(self):
        result, output = evaluate([("a" * 40, "chore: record\n\nFailed-Run: 123\nFailed-Run: 456\n")], lambda run: "failure" if run == "123" else None)
        self.assertEqual(result, 1)
        self.assertIn("Failed-Run 456", output)

    def test_t12_verify_without_token_does_not_skip_lookup(self):
        with patch.dict(os.environ, {}, clear=True), patch("check_commit_trailer.github_run_lookup") as lookup:
            output = io.StringIO()
            with redirect_stderr(output):
                result = main(["check_commit_trailer.py", "BASE", "HEAD", "--verify-runs", "--repository", "twenione/tnapp"])
        self.assertEqual(result, 2)
        lookup.assert_not_called()
        self.assertIn("GITHUB_TOKEN", output.getvalue())

    def test_t13_zero_base_checks_head_commit_only(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            init_repo(root)
            commit(root, "old.txt", "chore: old", "not-numeric")
            commit(root, "new.txt", "chore: latest")
            old_cwd = Path.cwd()
            try:
                os.chdir(root)
                with patch("check_commit_trailer.subprocess.run", wraps=subprocess.run) as run:
                    result = main(["check_commit_trailer.py", "0" * 40, "HEAD"])
            finally:
                os.chdir(old_cwd)
            self.assertEqual(result, 0)
            self.assertEqual(run.call_args.args[0][-2:], ["-1", "HEAD"])

    def test_t14_missing_base_returns_git_log_error(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            init_repo(root)
            commit(root, "file.txt", "chore: latest")
            old_cwd = Path.cwd()
            try:
                os.chdir(root)
                result = main(["check_commit_trailer.py", "f" * 40, "HEAD"])
            finally:
                os.chdir(old_cwd)
            self.assertEqual(result, 2)

    def test_t15_range_does_not_check_outside_commits(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            init_repo(root)
            commit(root, "one.txt", "chore: outside range", "not-numeric")
            base = commit(root, "two.txt", "chore: range base")
            head = commit(root, "three.txt", "chore: range head")
            old_cwd = Path.cwd()
            try:
                os.chdir(root)
                result = main(["check_commit_trailer.py", base, head])
            finally:
                os.chdir(old_cwd)
            self.assertEqual(result, 0)


if __name__ == "__main__":
    unittest.main()
