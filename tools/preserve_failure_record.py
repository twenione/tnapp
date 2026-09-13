#!/usr/bin/env python3
"""Create an immutable, sanitized failure record from downloaded log data."""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
from pathlib import Path

MASKING_RULESET_VERSION = "mask-1.0.0"
SECRET = re.compile(r"(?i)(?:gh[pousr]_[A-Za-z0-9_\-]{20,}|github_pat_[A-Za-z0-9_\-]{20,}|(?:token|secret|password|api[_-]?key)\s*[:=]\s*[^\s]+)")


def sanitize(text: str) -> str:
    return SECRET.sub("[REDACTED]", text)


def first_meaningful_line(text: str) -> str:
    for line in text.splitlines():
        value = line.strip()
        if value and not value.startswith("#"):
            return sanitize(value)
    return "no meaningful error line found"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--logs", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--commit-sha", required=True)
    parser.add_argument("--branch", required=True)
    parser.add_argument("--created-at", required=True)
    parser.add_argument("--runner-image-version", required=True)
    args = parser.parse_args()
    log_files = sorted(p for p in args.logs.rglob("*") if p.is_file()) if args.logs.exists() else []
    raw = "\n".join(p.read_text(encoding="utf-8", errors="replace") for p in log_files)
    sanitized_log = sanitize(raw)
    log_ref = f"logs/{args.run_id}.log"
    record = {
        "schema_version": "failrec-1.0.0",
        "run_id": args.run_id,
        "commit_sha": args.commit_sha,
        "branch": args.branch,
        "created_at": args.created_at or dt.datetime.now(dt.timezone.utc).isoformat(),
        "runner_image_version": args.runner_image_version,
        "toolchain": {"agp": "", "gradle": "", "jdk": "", "kotlin": "", "ndk": ""},
        "failed_task": "guard",
        "error_signature": first_meaningful_line(raw),
        "log_ref": log_ref,
        "masking_ruleset_version": MASKING_RULESET_VERSION,
        "log_sha256": "sha256:" + hashlib.sha256(sanitized_log.encode()).hexdigest(),
        "log": sanitized_log,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"record={args.out}")
    print(f"masking_ruleset_version={MASKING_RULESET_VERSION}")
    print(f"log_bytes={len(sanitized_log.encode())}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

