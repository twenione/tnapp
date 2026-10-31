#!/usr/bin/env python3
"""Negative controls for exclusion and scope marker coverage."""
from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CHECKER = ROOT / "tools" / "check_exclusion_references.py"
EMPTY_EXCEPTION_DATA = (
    '{"unrecorded_allowlist":[],"structurally_unreachable":[],"expected_failures":[],'
    '"observation_exempt":[],"withdrawn_decisions":[]}\n'
)



class ExclusionReferenceTests(unittest.TestCase):
    def run_checker(self, files: dict[str, str], *, decisions: bool = True) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory(prefix="exclusion-reference-") as temporary:
            root = Path(temporary)
            if decisions:
                (root / "docs").mkdir(parents=True, exist_ok=True)
                (root / "docs" / "DECISIONS.md").write_text("## D-001. Test decision\n", encoding="utf-8")
            for name, content in files.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")
            exception_path = root / "tools" / "known_exceptions.json"
            if "tools/known_exceptions.json" not in files:
                exception_path.parent.mkdir(parents=True, exist_ok=True)
                exception_path.write_text(EMPTY_EXCEPTION_DATA, encoding="utf-8")
            return subprocess.run(
                [sys.executable, str(CHECKER), "--root", str(root)],
                capture_output=True,
                text=True,
                encoding="utf-8",
                check=False,
            )

    def test_documented_python_and_workflow_candidates_pass(self):
        result = self.run_checker({
            "tools/check_sample.py": (
                "# EXCLUDE: D-001\n"
                "EXCLUDE_PATHS = ['docs/']\n"
                "def inspect(paths, path):\n"
                "    for item in paths:\n"
                "        if path.startswith('docs/'): # EXCLUDE: D-001\n"
                "            # SCOPE: The loop continues to the next supplied path.\n"
                "            continue\n"
                "    if path is None:\n"
                "        # SCOPE: None means the optional path was absent.\n"
                "        return None\n"
                "    try:\n"
                "        int(path)\n"
                "    except ValueError:\n"
                "        # SCOPE: A malformed scalar is handled as invalid input.\n"
                "        pass\n"
            ),
            ".github/workflows/guard.yml": (
                "jobs:\n"
                "  example:\n"
                "    steps:\n"
                "      - run: |\n"
                "          continue # EXCLUDE: D-001\n"
            ),
            "tools/test_noise.py": (
                "def ignored_test_input(path):\n"
                "    if path.startswith('ignored/'):\n"
                "        return None\n"
            ),
        })
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("EXCLUDE 3, SCOPE 3", result.stdout)

    def test_each_python_candidate_pattern_rejects_unmarked_input(self):
        candidates = {
            "a continue": "def inspect(items):\n    for item in items:\n        continue\n",
            "b conditional return": "def inspect(value):\n    if value is None:\n        return None\n",
            "c prefix call": "def inspect(path):\n    if path.startswith('skip/'):\n        pass\n",
            "d named setting": "EXEMPT_PATHS = []\n",
            "e exception pass": "def inspect():\n    try:\n        int('x')\n    except ValueError:\n        pass\n",
        }
        for label, source in candidates.items():
            with self.subTest(pattern=label):
                result = self.run_checker({"tools/check_probe.py": source})
                self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
                self.assertIn("missing adjacent", result.stdout)

    def test_workflow_continue_paths_ignore_branches_ignore_and_if_skip_fail(self):
        candidates = {
            "continue": "jobs:\n  x:\n    steps:\n      - run: |\n          continue\n",
            "paths-ignore": "on:\n  push:\n    paths-ignore:\n      - docs/**\n",
            "branches-ignore": "on:\n  push:\n    branches-ignore:\n      - temp/**\n",
            "if-skip": "jobs:\n  x:\n    if: contains(github.ref, 'skip')\n",
        }
        for label, source in candidates.items():
            with self.subTest(pattern=label):
                result = self.run_checker({".github/workflows/guard.yml": source})
                self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
                self.assertIn("missing adjacent", result.stdout)

    def test_nonexistent_decision_reference_fails(self):
        result = self.run_checker({
            "tools/check_probe.py": "# EXCLUDE: D-999\nEXCLUDE_PATHS = []\n",
        })
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("D-999 is not a heading", result.stdout)

    def test_exclude_without_decision_number_fails(self):
        result = self.run_checker({
            "tools/check_probe.py": "# EXCLUDE: decision pending\nEXCLUDE_PATHS = []\n",
        })
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("EXCLUDE marker must contain D-nnn", result.stdout)

    def test_empty_or_placeholder_scope_fails(self):
        for marker in ("# SCOPE:", "# SCOPE: <한 줄 사유>"):
            with self.subTest(marker=marker):
                result = self.run_checker({
                    "tools/check_probe.py": f"def inspect(value):\n    if value is None:\n        {marker}\n        return None\n",
                })
                self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
                self.assertIn("SCOPE marker requires", result.stdout)

    def test_missing_decision_document_fails(self):
        result = self.run_checker({
            "tools/check_probe.py": "# SCOPE: A documented scope.\ndef inspect(value):\n    if value is None:\n        return None\n",
        }, decisions=False)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("decision document is missing", result.stdout)

    def test_detached_marker_fails(self):
        result = self.run_checker({
            "tools/check_probe.py": (
                "# SCOPE: This marker is too far from its candidate.\n"
                "VALUE = 1\n\n"
                "def inspect(value):\n"
                "    if value is None:\n"
                "        return None\n"
            ),
        })
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("missing adjacent", result.stdout)


    def test_known_exception_data_rejects_missing_and_invalid_decision_references(self):
        cases = (
            ("missing", '{"unrecorded_allowlist":[{"event":"E1","reason":"disabled","note":"x"}],"expected_failures":[],"observation_exempt":[],"withdrawn_decisions":[]}', "decision must be D-nnn"),
            ("unknown", '{"unrecorded_allowlist":[{"event":"E1","reason":"disabled","decision":"D-999","note":"x"}],"expected_failures":[],"observation_exempt":[],"withdrawn_decisions":[]}', "D-999 has no heading"),
            ("withdrawn", '{"unrecorded_allowlist":[{"event":"E1","reason":"disabled","decision":"D-001","note":"x"}],"expected_failures":[],"observation_exempt":[],"withdrawn_decisions":["D-001"]}', "references withdrawn decision D-001"),
            ("malformed", "{", "invalid JSON"),
            ("duplicate", '{"unrecorded_allowlist":[{"event":"E1","reason":"disabled","decision":"D-001","note":"a"},{"event":"E1","reason":"disabled","decision":"D-001","note":"b"}],"expected_failures":[],"observation_exempt":[],"withdrawn_decisions":[]}', "duplicate (event, reason)"),
        )
        for label, content, expected in cases:
            with self.subTest(case=label):
                result = self.run_checker({"tools/known_exceptions.json": content})
                self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
                self.assertIn(expected, result.stdout)

    def test_empty_known_exception_lists_pass(self):
        result = self.run_checker({"tools/known_exceptions.json": EMPTY_EXCEPTION_DATA})
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_structurally_unreachable_requires_decision_reference_and_no_duplicates(self):
        cases = (
            ("missing decision", '{"event":"E1","reason":"direction-unknown","note":"x"}', "decision must be D-nnn"),
            ("unknown decision", '{"event":"E1","reason":"direction-unknown","decision":"D-999","note":"x"}', "D-999 has no heading"),
            ("withdrawn decision", '{"event":"E1","reason":"direction-unknown","decision":"D-001","note":"x"}', "references withdrawn decision D-001"),
            ("duplicate pair", '{"event":"E1","reason":"direction-unknown","decision":"D-001","note":"x"},{"event":"E1","reason":"direction-unknown","decision":"D-001","note":"y"}', "duplicate (event, reason)"),
        )
        for label, item, expected in cases:
            with self.subTest(case=label):
                withdrawn = '["D-001"]' if label == "withdrawn decision" else "[]"
                if label == "duplicate pair":
                    entries = "[" + item + "]"
                elif label == "missing decision":
                    entries = "[" + item + "]"
                else:
                    entries = "[" + item + "]"
                data = (
                    '{"unrecorded_allowlist":[],"structurally_unreachable":' + entries +
                    ',"expected_failures":[],"observation_exempt":[],"withdrawn_decisions":' + withdrawn + '}'
                )
                result = self.run_checker({"tools/known_exceptions.json": data})
                self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
                self.assertIn(expected, result.stdout)

    def test_structurally_unreachable_requires_its_list_key(self):
        data = '{"unrecorded_allowlist":[],"expected_failures":[],"observation_exempt":[],"withdrawn_decisions":[]}'
        result = self.run_checker({"tools/known_exceptions.json": data})
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("missing list key(s): structurally_unreachable", result.stdout)

    def test_structurally_unreachable_valid_entry_and_empty_list_pass(self):
        valid = (
            '{"unrecorded_allowlist":[],"structurally_unreachable":['
            '{"event":"E1","reason":"direction-unknown","decision":"D-001","note":"structural"}],'
            '"expected_failures":[],"observation_exempt":[],"withdrawn_decisions":[]} '
        )
        for data in (EMPTY_EXCEPTION_DATA, valid):
            with self.subTest(data=data):
                result = self.run_checker({"tools/known_exceptions.json": data})
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
    def test_new_scanner_is_discovered_by_glob(self):
        result = self.run_checker({
            "tools/check_new.py": "def inspect(path):\n    if path.startswith('hidden/'):\n        pass\n",
        })
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("tools/check_new.py", result.stdout)


if __name__ == "__main__":
    unittest.main()
