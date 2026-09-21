#!/usr/bin/env python3
"""Audit append commits without mutating the private failure-log repository."""
from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

from failure_log_identity import RECORDER_EMAIL, RECORDER_NAME

KNOWN_MANUAL = "e19bf27d74d3e6872017def95a6d6ce1bd1f0827"
WORKFLOW_RUN = re.compile(r"(?m)^Workflow-Run:\s*https://github\.com/[^/]+/[^/]+/actions/runs/(\d+)\s*$")
WORKFLOW = re.compile(r"(?m)^Workflow:\s*(\S+)\s*$")
ALLOWED_WORKFLOWS = {"preserve-failure", "failure-log-readme"}


def audit(values: list[dict]) -> int:
    ordered = list(reversed(values))
    cutoff = next((index for index, item in enumerate(ordered) if WORKFLOW_RUN.search(str(item.get("message", "")))), None)
    if cutoff is None:
        for item in ordered:
            print(f"{item.get('sha','')}: legacy")
        print("AUDIT commits=0 anomalies=0 cutoff=none")
        return 0
    anomalies = 0
    checked = 0
    for index, item in enumerate(ordered):
        sha = str(item.get("sha", ""))
        message = str(item.get("message", ""))
        if index < cutoff:
            print(f"{sha}: legacy")
            continue
        if sha == KNOWN_MANUAL:
            print(f"{sha}: known-manual")
            continue
        checked += 1
        reasons: list[str] = []
        author = item.get("author") or {}
        committer = item.get("committer") or {}
        expected = {"name": RECORDER_NAME, "email": RECORDER_EMAIL}
        if {"name": author.get("name"), "email": author.get("email")} != expected:
            reasons.append("author/committer identity")
        if {"name": committer.get("name"), "email": committer.get("email")} != expected:
            reasons.append("author/committer identity")
        workflow = WORKFLOW.search(message)
        run = WORKFLOW_RUN.search(message)
        run_info = item.get("workflow_run")
        if not workflow or workflow.group(1) not in ALLOWED_WORKFLOWS:
            reasons.append("workflow not allowed")
        if not run or not isinstance(run_info, dict) or not run_info.get("exists"):
            reasons.append("workflow run missing")
        elif str(run_info.get("workflow")) != workflow.group(1):
            reasons.append("workflow name mismatch")
        expected_path = "README.md" if workflow and workflow.group(1) == "failure-log-readme" else None
        for change in item.get("files", []):
            path = str(change.get("filename", ""))
            status = str(change.get("status", ""))
            if expected_path:
                if path != expected_path or status != "modified":
                    reasons.append("file outside README.md modified")
            elif not (path.startswith("records/") or path.startswith("resolutions/")) or status != "added":
                reasons.append("file outside append-only paths")
        if reasons:
            anomalies += 1
            print(f"{sha}: ANOMALY " + "; ".join(dict.fromkeys(reasons)))
        else:
            print(f"{sha}: OK")
    print(f"AUDIT commits={checked} anomalies={anomalies} cutoff=workflow-run")
    return 1 if anomalies else 0


def online(repository: str, token: str, limit: int) -> list[dict]:
    request = urllib.request.Request(
        f"https://api.github.com/repos/{repository}/commits?per_page={limit}",
        headers={"Accept": "application/vnd.github+json", "Authorization": f"Bearer {token}", "X-GitHub-Api-Version": "2022-11-28"},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        commits = json.loads(response.read().decode("utf-8"))
    values = []
    for commit in commits:
        detail = commit.get("commit", {})
        values.append({
            "sha": commit.get("sha"),
            "message": detail.get("message", ""),
            "author": detail.get("author", {}),
            "committer": detail.get("committer", {}),
            "files": [{"filename": item.get("filename"), "status": item.get("status")} for item in commit.get("files", [])],
            "workflow_run": {"exists": False},
        })
    return values


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path)
    parser.add_argument("--repository", default="twenione/tnapp-failure-log")
    parser.add_argument("--limit", type=int, default=50)
    args = parser.parse_args(argv[1:])
    try:
        if args.input:
            values = json.loads(args.input.read_text(encoding="utf-8"))
        else:
            import os
            values = online(args.repository, os.environ["GH_TOKEN"], args.limit)
        if not isinstance(values, list):
            raise ValueError("input must be a commit list")
        return audit(values)
    except (OSError, KeyError, ValueError, json.JSONDecodeError, urllib.error.URLError) as exc:
        print(f"FAIL: audit input unavailable: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
