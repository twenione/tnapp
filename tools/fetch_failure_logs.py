#!/usr/bin/env python3
"""Fetch failed GitHub Actions job logs as data for the trusted recorder."""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path


def request_bytes(url: str, token: str) -> bytes:
    environment = os.environ.copy()
    environment["GH_TOKEN"] = token
    result = subprocess.run(
        ["gh", "api", "--allow-escape-sequences", url],
        env=environment,
        capture_output=True,
        timeout=30,
    )
    if result.returncode:
        detail = result.stderr.decode("utf-8", errors="replace").strip()
        raise RuntimeError(detail or f"gh api exited {result.returncode}")
    return result.stdout


def safe_name(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]+", "-", value).strip("-") or "job"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--token", required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    jobs_url = f"repos/{args.repository}/actions/runs/{args.run_id}/jobs?per_page=100"
    try:
        jobs = json.loads(request_bytes(jobs_url, args.token).decode("utf-8"))
    except (OSError, RuntimeError, json.JSONDecodeError) as exc:
        print(f"FAIL: unable to list workflow jobs: {exc}", file=sys.stderr)
        return 1

    failed_jobs = [job for job in jobs.get("jobs", []) if job.get("conclusion") == "failure"]
    args.out.mkdir(parents=True, exist_ok=True)
    fetched = 0
    for job in failed_jobs:
        job_id = str(job.get("id", "unknown"))
        name = safe_name(str(job.get("name", "job")))
        log_url = f"repos/{args.repository}/actions/jobs/{job_id}/logs"
        try:
            log = request_bytes(log_url, args.token)
        except (OSError, RuntimeError) as exc:
            print(f"WARN: unable to fetch failed job log {job_id}: {exc}", file=sys.stderr)
            continue
        (args.out / f"job-{job_id}-{name}.log").write_bytes(log)
        fetched += 1

    if fetched == 0:
        print("FAIL: no failed job logs were fetched", file=sys.stderr)
        return 1
    print(f"fetched_failed_job_logs={fetched}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
