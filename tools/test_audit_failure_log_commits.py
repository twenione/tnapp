#!/usr/bin/env python3
"""Offline mutation cases for the failure-log commit audit."""
from __future__ import annotations

import json
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def commit(sha: str, message: str, files: list[dict], *, identity=True, exists=True, workflow="preserve-failure") -> dict:
    person = {"name": "tnapp-failure-recorder", "email": "failure-recorder@tnapp.invalid"} if identity else {"name": "twenione", "email": "twenione@gmail.com"}
    return {"sha": sha, "message": message, "author": person, "committer": person, "files": files, "workflow_run": {"exists": exists, "workflow": workflow}}


def run(path: Path, expected: int) -> bool:
    result = subprocess.run([sys.executable, str(ROOT / "tools" / "audit_failure_log_commits.py"), "--input", str(path)], capture_output=True, text=True)
    print(result.stdout, end="")
    return result.returncode == expected


def main() -> int:
    root = ROOT / "testdata" / "failure_extractor" / ".tmp-audit-commits"
    shutil.rmtree(root, ignore_errors=True)
    root.mkdir()
    failures = 0
    try:
        valid_message = "ci: append failrec-2.0.2 1\n\nWorkflow: preserve-failure\nWorkflow-Run: https://github.com/twenione/tnapp/actions/runs/1"
        legacy = {"sha": "legacy", "message": "manual", "files": [{"filename": "records/old.json", "status": "added"}]}
        cases = {
            "valid": [commit("valid", valid_message, [{"filename": "records/1.json", "status": "added"}]), legacy],
            "identity": [commit("identity", valid_message, [{"filename": "records/1.json", "status": "added"}], identity=False), legacy],
            "missing-run": [commit("missing", valid_message, [{"filename": "records/1.json", "status": "added"}], exists=False), legacy],
            "modified": [commit("modified", valid_message, [{"filename": "records/1.json", "status": "modified"}]), legacy],
            "readme-wrong": [commit("readme-wrong", valid_message.replace("preserve-failure", "failure-log-readme"), [{"filename": "records/1.json", "status": "added"}], workflow="failure-log-readme"), legacy],
            "legacy-only": [legacy],
        }
        for name, values in cases.items():
            path = root / f"{name}.json"
            path.write_text(json.dumps(values), encoding="utf-8")
            failures += not run(path, 0 if name in {"valid", "legacy-only"} else 1)
        print(f"audit_commit_cases=6 failures={failures}")
        return 1 if failures else 0
    finally:
        shutil.rmtree(root, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
