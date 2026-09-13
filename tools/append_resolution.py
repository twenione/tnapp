#!/usr/bin/env python3
"""Build a separate resolution record without modifying the failure record."""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import tempfile
import subprocess
from pathlib import Path

try:
    from check_commit_trailer import TRAILER
except ModuleNotFoundError:  # imported as tools.append_resolution
    from tools.check_commit_trailer import TRAILER


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--head-sha", required=True)
    parser.add_argument("--token-present", choices=("yes", "no"), required=True)
    args = parser.parse_args()
    if args.token_present != "yes":
        print("ERROR: FAILURE_LOG_TOKEN is not available", flush=True)
        return 2
    result = subprocess.run(["git", "log", "-1", "--format=%B", args.head_sha], text=True, capture_output=True)
    if result.returncode:
        print(result.stderr, end="")
        return result.returncode
    trailer = TRAILER.search(result.stdout)
    if not trailer:
        print("NOOP: HEAD has no Failed-Run trailer; no resolution appended")
        return 0
    failed_run_id = trailer.group(1)
    record = {
        "schema_version": "failrec-resolution-1.0.0",
        "run_id": failed_run_id,
        "resolved_by_commit": args.head_sha,
        "resolved_run_id": args.run_id,
        "resolved_at": dt.datetime.now(dt.timezone.utc).isoformat(),
    }
    # Write a temporary local object, then append it as a separate immutable
    # path. The original failure record is never read or modified.
    with tempfile.TemporaryDirectory(prefix="tnapp-resolution-") as temp:
        path = Path(temp) / "resolution.json"
        path.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        push = subprocess.run(["python", "tools/push_failure_record.py", str(path), "--path", f"resolutions/{failed_run_id}-{args.head_sha}.json"], text=True, capture_output=True)
        print(push.stdout, end="")
        print(push.stderr, end="")
        if push.returncode:
            return push.returncode
    print(json.dumps(record, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
