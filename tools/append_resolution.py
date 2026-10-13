#!/usr/bin/env python3
"""Build a separate resolution record without modifying the failure record."""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
from pathlib import Path

try:
    from check_commit_trailer import TRAILER
except ModuleNotFoundError:  # imported as tools.append_resolution
    from tools.check_commit_trailer import TRAILER


class RecordLookupError(Exception):
    """The failure record could not be checked reliably."""


def record_exists(run_id: str, repository: str, token: str) -> bool:
    """Check whether records/<run_id>.json exists in the private log repo."""
    url = f"https://api.github.com/repos/{repository}/contents/records/{run_id}.json"
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            if response.status == 200:
                return True
            raise RecordLookupError(f"GitHub returned HTTP {response.status}")
    except urllib.error.HTTPError as exc:
        if exc.code == 404:
            return False
        raise RecordLookupError(f"GitHub returned HTTP {exc.code}") from exc
    except (urllib.error.URLError, TimeoutError) as exc:
        raise RecordLookupError(str(exc)) from exc


def push_resolution(path: Path, remote_path: str) -> subprocess.CompletedProcess:
    """Use the established immutable writer for a resolution record."""
    return subprocess.run(
        [sys.executable, "tools/push_failure_record.py", str(path), "--path", remote_path],
        text=True,
        encoding="utf-8",
        errors="replace",
        capture_output=True,
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--head-sha", required=True)
    parser.add_argument("--token-present", choices=("yes", "no"), required=True)
    parser.add_argument("--before-sha", default=None)
    parser.add_argument("--range-file", type=Path, default=None)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    token = os.environ.get("FAILURE_LOG_TOKEN", "")
    if args.token_present != "yes" or not token:
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
    if before_sha and set(str(before_sha)) == {"0"}:
        before_sha = None
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

    repository = os.environ.get("FAILURE_LOG_REPOSITORY") or "twenione/tnapp-failure-log"
    records = []
    missing: list[str] = []
    undetermined: list[tuple[str, str]] = []
    with tempfile.TemporaryDirectory(prefix="tnapp-resolution-") as temp:
        for failed_run_id, source_sha in candidates:
            try:
                exists = record_exists(failed_run_id, repository, token)
            except RecordLookupError as exc:
                undetermined.append((failed_run_id, str(exc)))
                continue
            if not exists:
                missing.append(failed_run_id)
                continue
            record = {
                "schema_version": "failrec-resolution-1.0.0",
                "run_id": failed_run_id,
                "resolved_by_commit": args.head_sha,
                "resolved_run_id": args.run_id,
                "resolved_at": dt.datetime.now(dt.timezone.utc).isoformat(),
            }
            path = Path(temp) / f"resolution-{failed_run_id}-{args.head_sha}.json"
            path.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            push = push_resolution(path, f"resolutions/{failed_run_id}-{args.head_sha}.json")
            print(push.stdout, end="")
            print(push.stderr, end="")
            if push.returncode:
                return push.returncode
            records.append(record)

    print(json.dumps({"appended": len(records), "records": records}, sort_keys=True))
    for run_id in missing:
        print(f"FAIL: failure record for run {run_id} does not exist")
    for run_id, error in undetermined:
        print(f"FAIL: failure record for run {run_id} could not be verified: {error}")
    if missing:
        return 1
    if undetermined:
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
