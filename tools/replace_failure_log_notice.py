#!/usr/bin/env python3
"""Build and, only inside Actions, replace the failrec-2.0.0 README notice."""
from __future__ import annotations

import argparse
import base64
import datetime as dt
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

from failure_log_identity import RECORDER_EMAIL, RECORDER_NAME
from preserve_failure_record import signature_is_run_echo

HEADING = "## failrec-2.0.0 error_signature notice"
WORKFLOW = "failure-log-readme"


def _has_forbidden_control(value: str) -> bool:
    return any((ord(char) < 32 and char != "\n") or ord(char) == 127 or char == "\ufffd" for char in value)


def read_records(root: Path) -> list[dict]:
    values = []
    for path in sorted(root.glob("*.json")):
        try:
            record = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        if isinstance(record, dict) and record.get("schema_version") == "failrec-2.0.0":
            values.append(record)
    return values


def aggregate(records: list[dict]) -> dict[str, object]:
    invalid: list[dict] = []
    valid: list[dict] = []
    for record in records:
        target = invalid if signature_is_run_echo(str(record.get("log", "")), str(record.get("error_signature", ""))) else valid
        target.append(record)
    ordered = sorted(records, key=lambda item: str(item.get("created_at", "")))
    return {
        "records": records,
        "invalid": invalid,
        "valid": valid,
        "first_created_at": ordered[0].get("created_at", "") if ordered else "",
        "last_created_at": ordered[-1].get("created_at", "") if ordered else "",
    }


def _ids(values: list[dict]) -> str:
    return ", ".join(str(item.get("run_id", "")) for item in sorted(values, key=lambda item: str(item.get("run_id", "")))) or "(none)"


def build_notice(readme: str, records: list[dict]) -> str:
    if readme.count(HEADING) != 1:
        raise ValueError("README must contain exactly one failrec-2.0.0 heading")
    prefix = readme.split(HEADING, 1)[0]
    if _has_forbidden_control(prefix):
        raise ValueError("README prefix contains a forbidden control character")
    data = aggregate(records)
    invalid = data["invalid"]
    valid = data["valid"]
    total = len(records)
    notice = "\n".join([
        HEADING,
        "",
        f"failrec-2.0.0 is append-only. Its error_signature is invalid for {len(invalid)} records and valid for {len(valid)} records.",
        f"Total records: {total}.",
        f"Invalid run_id list: {_ids(invalid)}",
        f"Valid run_id list: {_ids(valid)}",
        f"First created_at: {data['first_created_at']}",
        f"Last created_at: {data['last_created_at']}",
        "Invalid signatures selected a setup-gradle input echo line (dependency-graph-continue-on-failure: true); regex false positives and compiler-error lines did not match.",
        "Consumers must treat invalid signatures as invalid and recompute from log using tools/preserve_failure_record.py first_meaningful_line and the read-only tools/audit_failure_records.py.",
        "Version history: failrec-2.0.1 preserves the priority-table extractor; failrec-2.0.2 adds the * What went wrong: rule; failrec-2.0.0 is a closed set. failrec-1.0.0 has 5 records in a separate legacy schema.",
        "",
    ])
    result = prefix + notice
    if _has_forbidden_control(result):
        raise ValueError("README contains a forbidden control character")
    if not result.startswith(prefix) or any(not re.fullmatch(r"[^\n]*", str(item.get("created_at", ""))) for item in records):
        raise ValueError("README validation failed")
    return result


def _request(url: str, token: str, *, method: str = "GET", payload: dict | None = None) -> dict:
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    request = urllib.request.Request(url, data=data, method=method, headers={"Accept": "application/vnd.github+json", "Authorization": f"Bearer {token}", "X-GitHub-Api-Version": "2022-11-28", "Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read().decode("utf-8"))


def online(repository: str, token: str, *, apply: bool) -> int:
    base = f"https://api.github.com/repos/{repository}"
    readme_payload = _request(f"{base}/contents/README.md", token)
    readme = base64.b64decode(readme_payload["content"]).decode("utf-8")
    listing = _request(f"{base}/contents/records", token)
    records = []
    for item in listing:
        if not str(item.get("name", "")).endswith(".json"):
            continue
        payload = _request(str(item["url"]), token)
        records.append(json.loads(base64.b64decode(payload["content"]).decode("utf-8")))
    generated = build_notice(readme, records)
    if generated == readme:
        print("NOOP: README notice already current")
        return 0
    if not apply:
        print(f"DRY-RUN: records={len(records)} bytes={len(generated.encode('utf-8'))}")
        return 0
    if os.environ.get("GITHUB_ACTIONS", "").lower() != "true":
        print("ERROR: README writes are allowed only inside GitHub Actions", file=sys.stderr)
        return 2
    run_id = os.environ.get("GITHUB_RUN_ID", "")
    repository_name = os.environ.get("GITHUB_REPOSITORY", "")
    workflow = os.environ.get("GITHUB_WORKFLOW", WORKFLOW)
    if not run_id or not repository_name or workflow != WORKFLOW:
        print("ERROR: workflow identity is incomplete", file=sys.stderr)
        return 2
    message = f"ci: replace failrec-2.0.0 signature notice\n\nWorkflow: {WORKFLOW}\nWorkflow-Run: https://github.com/{repository_name}/actions/runs/{run_id}"
    result = _request(f"{base}/contents/README.md", token, method="PUT", payload={"message": message, "content": base64.b64encode(generated.encode()).decode(), "sha": readme_payload["sha"], "author": {"name": RECORDER_NAME, "email": RECORDER_EMAIL}, "committer": {"name": RECORDER_NAME, "email": RECORDER_EMAIL}})
    commit_sha = result.get("commit", {}).get("sha", "")
    checked = _request(f"{base}/contents/README.md", token)
    verified = base64.b64decode(checked["content"]).decode("utf-8") == generated
    commit = _request(f"https://api.github.com/repos/{repository}/commits/{commit_sha}", token)
    files = [item.get("filename") for item in commit.get("files", [])]
    if not verified or files != ["README.md"]:
        print("FAIL: post-write README verification failed", file=sys.stderr)
        return 1
    print(f"updated=README.md records={len(records)} commit={commit_sha}")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--readme", type=Path)
    parser.add_argument("--records-root", type=Path)
    parser.add_argument("--repository", default="twenione/tnapp-failure-log")
    parser.add_argument("--token", default=os.environ.get("FAILURE_LOG_TOKEN", ""))
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args(argv[1:])
    try:
        if args.readme and args.records_root:
            current = args.readme.read_text(encoding="utf-8")
            generated = build_notice(current, read_records(args.records_root))
            if generated == current:
                print("NOOP: README notice already current")
                return 0
            if args.apply:
                if os.environ.get("GITHUB_ACTIONS", "").lower() != "true":
                    print("ERROR: README writes are allowed only inside GitHub Actions", file=sys.stderr)
                    return 2
                args.readme.write_text(generated, encoding="utf-8")
            else:
                print(f"DRY-RUN: records={len(read_records(args.records_root))} bytes={len(generated.encode('utf-8'))}")
            return 0
        if not args.token:
            print("ERROR: FAILURE_LOG_TOKEN is not available", file=sys.stderr)
            return 2
        return online(args.repository, args.token, apply=args.apply)
    except (OSError, ValueError, KeyError, json.JSONDecodeError, urllib.error.URLError) as exc:
        print(f"FAIL: README generation failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
