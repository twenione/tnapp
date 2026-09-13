#!/usr/bin/env python3
"""Append a record to the private failure-log repository from a trusted job.

The token is read only from the workflow environment. No PR code is checked out
or executed by this script, and each record is written to a new immutable path.
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path


def upload(path: Path, remote_path: str, token: str, repository: str) -> int:
    record = json.loads(path.read_text(encoding="utf-8"))
    required = {"schema_version", "run_id"}
    missing = sorted(required - record.keys())
    if missing:
        print("FAIL: missing record fields: " + ", ".join(missing), file=sys.stderr)
        return 1
    body = json.dumps(record, ensure_ascii=False, indent=2).encode("utf-8")
    api = f"https://api.github.com/repos/{repository}/contents/{remote_path}"
    request = urllib.request.Request(
        api,
        data=json.dumps({"message": f"ci: append {record['schema_version']} {record['run_id']}", "content": base64.b64encode(body).decode("ascii")}).encode("utf-8"),
        headers={"Accept": "application/vnd.github+json", "Authorization": f"Bearer {token}", "X-GitHub-Api-Version": "2022-11-28", "Content-Type": "application/json"},
        method="PUT",
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except (urllib.error.HTTPError, urllib.error.URLError) as exc:
        print(f"FAIL: failure-log append request failed: {exc}", file=sys.stderr)
        return 1
    commit_sha = payload.get("commit", {}).get("sha", "")
    print(f"append_path={remote_path}")
    print(f"append_commit_sha={commit_sha}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("record", type=Path)
    parser.add_argument("--path", default=None, help="immutable path inside the failure-log repository")
    parser.add_argument("--repository", default=os.environ.get("FAILURE_LOG_REPOSITORY", "twenione/tnapp-failure-log"))
    args = parser.parse_args()
    token = os.environ.get("FAILURE_LOG_TOKEN", "")
    if not token:
        print("ERROR: FAILURE_LOG_TOKEN is not available", file=sys.stderr)
        return 2
    record = json.loads(args.record.read_text(encoding="utf-8"))
    remote_path = args.path or f"records/{record['run_id']}.json"
    return upload(args.record, remote_path, token, args.repository)


if __name__ == "__main__":
    raise SystemExit(main())
