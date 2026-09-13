#!/usr/bin/env python3
"""Fetch failed GitHub Actions job logs as data for the trusted recorder."""
from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.error
import urllib.request
from urllib.parse import urlparse
from pathlib import Path


class AuthRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Keep the workflow token when GitHub redirects a log download."""

    def redirect_request(self, request, file, code, msg, headers, newurl):
        redirected = super().redirect_request(request, file, code, msg, headers, newurl)
        if redirected is not None:
            old_host = urlparse(request.full_url).hostname or ""
            new_host = urlparse(newurl).hostname or ""
            authorization = request.headers.get("Authorization")
            # Keep the bearer only across GitHub API hosts. GitHub redirects
            # log downloads to a signed blob URL where Authorization breaks
            # the signature and must be omitted.
            if authorization and new_host.endswith("github.com") and new_host == old_host:
                redirected.add_header("Authorization", authorization)
        return redirected


OPENER = urllib.request.build_opener(AuthRedirectHandler)


def request_bytes(url: str, token: str) -> bytes:
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    with OPENER.open(request, timeout=30) as response:
        return response.read()


def safe_name(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]+", "-", value).strip("-") or "job"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--token", required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    jobs_url = f"https://api.github.com/repos/{args.repository}/actions/runs/{args.run_id}/jobs?per_page=100"
    try:
        jobs = json.loads(request_bytes(jobs_url, args.token).decode("utf-8"))
    except (OSError, urllib.error.URLError, json.JSONDecodeError) as exc:
        print(f"FAIL: unable to list workflow jobs: {exc}", file=sys.stderr)
        return 1

    failed_jobs = [job for job in jobs.get("jobs", []) if job.get("conclusion") == "failure"]
    args.out.mkdir(parents=True, exist_ok=True)
    fetched = 0
    for job in failed_jobs:
        job_id = str(job.get("id", "unknown"))
        name = safe_name(str(job.get("name", "job")))
        log_url = f"https://api.github.com/repos/{args.repository}/actions/jobs/{job_id}/logs"
        try:
            log = request_bytes(log_url, args.token)
        except (OSError, urllib.error.URLError) as exc:
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
