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
    parser.add_argument("--before-sha", default=None)
    parser.add_argument("--range-file", type=Path, default=None)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    if args.token_present != "yes":
        print("ERROR: FAILURE_LOG_TOKEN is not available", flush=True)
        return 2
    commit_messages: list[tuple[str, str]] = []
    before_sha = args.before_sha
    if args.range_file and args.range_file.is_file():
        try:
            payload = json.loads(args.range_file.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            print(f"ERROR: invalid push range metadata: {exc}", flush=True)
            return 1
        before_sha = before_sha or payload.get("before")
        for item in payload.get("commits", []):
            if isinstance(item, dict) and item.get("id"):
                commit_messages.append((str(item["id"]), str(item.get("message", ""))))
    # A push payload's before..after range is authoritative when available. It
    # also covers merge commits whose trailer lives on a non-HEAD parent.
    if before_sha:
        result = subprocess.run(["git", "log", "--reverse", "--format=%H%x00%B%x00", f"{before_sha}..{args.head_sha}"], text=True, encoding="utf-8", errors="replace", capture_output=True)
        if result.returncode:
            if not commit_messages:
                print(result.stderr, end="")
                return result.returncode
        else:
            parts = result.stdout.split("\x00")
            commit_messages = []
            for index in range(0, len(parts) - 1, 2):
                if parts[index].strip():
                    commit_messages.append((parts[index].strip(), parts[index + 1]))
    if not commit_messages:
        revision = args.head_sha
        result = subprocess.run(["git", "log", "--reverse", "--format=%H%x00%B%x00", revision], text=True, encoding="utf-8", errors="replace", capture_output=True)
        if result.returncode:
            print(result.stderr, end="")
            return result.returncode
        parts = result.stdout.split("\x00")
        for index in range(0, len(parts) - 1, 2):
            if parts[index].strip():
                commit_messages.append((parts[index].strip(), parts[index + 1]))
    candidates = []
    seen = set()
    for commit_sha, message in commit_messages:
        trailer = TRAILER.search(message)
        if trailer:
            failed_run_id = trailer.group(1)
            if failed_run_id not in seen:
                seen.add(failed_run_id)
                candidates.append((failed_run_id, commit_sha))
    if not candidates:
        print("NOOP: push commit range has no Failed-Run trailer; no resolution appended")
        return 0
    if args.dry_run:
        print(json.dumps({"candidates": [{"run_id": run_id, "source_commit": sha} for run_id, sha in candidates]}, sort_keys=True))
        return 0
    records = []
    with tempfile.TemporaryDirectory(prefix="tnapp-resolution-") as temp:
        for failed_run_id, source_sha in candidates:
            record = {
                "schema_version": "failrec-resolution-1.0.0",
                "run_id": failed_run_id,
                "resolved_by_commit": args.head_sha,
                "resolved_run_id": args.run_id,
                "resolved_at": dt.datetime.now(dt.timezone.utc).isoformat(),
            }
            path = Path(temp) / f"resolution-{failed_run_id}-{args.head_sha}.json"
            path.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            push = subprocess.run(["python", "tools/push_failure_record.py", str(path), "--path", f"resolutions/{failed_run_id}-{args.head_sha}.json"], text=True, encoding="utf-8", errors="replace", capture_output=True)
            print(push.stdout, end="")
            print(push.stderr, end="")
            if push.returncode:
                return push.returncode
            records.append(record)
    print(json.dumps({"appended": len(records), "records": records}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
