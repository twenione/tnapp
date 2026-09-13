#!/usr/bin/env python3
"""Create an immutable, sanitized failure record from downloaded log data."""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path

MASKING_RULESET_VERSION = "mask-1.0.0"
SECRET = re.compile(r"(?i)(?:gh[pousr]_[A-Za-z0-9_\-]{20,}|github_pat_[A-Za-z0-9_\-]{20,}|(?:token|secret|password|api[_-]?key)\s*[:=]\s*[^\s]+)")
ERROR_LINE = re.compile(r"(?i)(?:##\[error\]|\b(?:fail(?:ed|ure)?|error|fatal)\s*:|\b(?:exception|traceback)\b)")
EMPTY_LOG_SHA256 = "sha256:e3b0c44298fc1c149af4c8996fb92427ae41e4649b934ca495991b7852b855"
ALLOWED_SOURCES = {"runtime", "declared", "unavailable"}


def sanitize(text: str) -> str:
    return SECRET.sub("[REDACTED]", text)


def first_meaningful_line(text: str) -> str:
    for line in text.splitlines():
        if line.strip() and ERROR_LINE.search(line):
            return sanitize(line)
    for line in text.splitlines():
        value = line.strip()
        if value and not value.startswith("#"):
            return sanitize(line)
    return "no meaningful error line found"


def run_version(command: list[str], cwd: Path) -> str:
    """Return a version string from a trusted runtime command, if available."""
    try:
        result = subprocess.run(
            command,
            cwd=cwd,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=30,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return ""
    output = result.stdout + "\n" + result.stderr
    patterns = (
        r"(?im)^\s*Gradle\s+([0-9]+(?:\.[0-9]+)+(?:[-+][^\s]+)?)",
        r"(?im)^\s*(?:openjdk|java|openj9)\s+version\s+\"([^\"]+)\"",
        r"(?im)^\s*java(?:\.version)?\s+([0-9]+(?:\.[0-9]+)*(?:_[0-9]+)?)",
    )
    for pattern in patterns:
        match = re.search(pattern, output)
        if match:
            return match.group(1)
    return ""


def declared_version(repo_root: Path, patterns: list[str]) -> str:
    """Find a declared version in trusted build/configuration files."""
    candidates = [
        *repo_root.rglob("*.gradle"),
        *repo_root.rglob("*.gradle.kts"),
        *repo_root.rglob("*.toml"),
        *repo_root.rglob("gradle-wrapper.properties"),
    ]
    for path in sorted(set(candidates)):
        if any(part in {".git", ".gradle", "build"} for part in path.parts):
            continue
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        for pattern in patterns:
            match = re.search(pattern, text)
            if match:
                return match.group(1)
    return ""


def toolchain(repo_root: Path) -> dict[str, dict[str, str]]:
    """Collect runtime and declared toolchain facts with explicit provenance."""
    gradle_runtime = run_version(["./gradlew", "--version"], repo_root)
    if not gradle_runtime and os.name == "nt":
        gradle_runtime = run_version(["gradlew.bat", "--version"], repo_root)
    if not gradle_runtime:
        gradle_runtime = run_version(["gradle", "--version"], repo_root)
    jdk_runtime = run_version(["java", "-version"], repo_root)
    agp_declared = declared_version(
        repo_root,
        [
            r"(?im)com\.android\.(?:application|library)\s*(?:version\s*[= ]\s*[\"']|\(\s*\"?)([0-9]+(?:\.[0-9]+)+)",
            r"(?im)agp\s*=\s*[\"']([0-9]+(?:\.[0-9]+)+)[\"']",
        ],
    )
    kotlin_declared = declared_version(
        repo_root,
        [
            r"(?im)org\.jetbrains\.kotlin[^\n]*?version\s*[= ]\s*[\"']([0-9]+(?:\.[0-9]+)+)[\"']",
            r"(?im)kotlin\s*=\s*[\"']([0-9]+(?:\.[0-9]+)+)[\"']",
        ],
    )
    ndk_declared = declared_version(
        repo_root,
        [r"(?im)ndkVersion\s*[= ]\s*[\"']([^\"']+)[\"']"],
    )

    def fact(value: str, source: str) -> dict[str, str]:
        if source not in ALLOWED_SOURCES:
            raise ValueError(f"unsupported toolchain source: {source}")
        return {"value": value, "source": source}

    return {
        "gradle": fact(gradle_runtime, "runtime" if gradle_runtime else "unavailable"),
        "jdk": fact(jdk_runtime, "runtime" if jdk_runtime else "unavailable"),
        "agp": fact(agp_declared, "declared" if agp_declared else "unavailable"),
        "kotlin": fact(kotlin_declared, "declared" if kotlin_declared else "unavailable"),
        "ndk": fact(ndk_declared, "declared" if ndk_declared else "unavailable"),
    }


def failed_jobs(log_root: Path) -> list[str]:
    metadata = log_root / "failed_jobs.json"
    if metadata.is_file():
        try:
            data = json.loads(metadata.read_text(encoding="utf-8"))
            values = data.get("failed_jobs", data) if isinstance(data, dict) else data
            if isinstance(values, list):
                return [str(value) for value in values if str(value).strip()]
        except (OSError, json.JSONDecodeError):
            pass
    names = []
    for path in sorted(log_root.glob("job-*.log")):
        match = re.match(r"job-[^-]+-(.+)\.log$", path.name)
        if match:
            names.append(match.group(1))
    return names


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--logs", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--commit-sha", required=True)
    parser.add_argument("--branch", required=True)
    parser.add_argument("--created-at", required=True)
    parser.add_argument("--runner-image-version", required=True)
    parser.add_argument("--repo-root", type=Path, default=Path("."))
    args = parser.parse_args()
    log_files = (
        sorted(
            p
            for p in args.logs.rglob("*")
            if p.is_file() and p.name != "failed_jobs.json" and p.suffix.lower() in {".log", ".txt"}
        )
        if args.logs.exists()
        else []
    )
    raw = "\n".join(p.read_text(encoding="utf-8", errors="replace") for p in log_files)
    sanitized_log = sanitize(raw)
    log_sha256 = "sha256:" + hashlib.sha256(sanitized_log.encode()).hexdigest()
    if not sanitized_log.strip() or log_sha256 == EMPTY_LOG_SHA256:
        print("FAIL: refusing to write an empty failure log", flush=True)
        return 1
    jobs = failed_jobs(args.logs)
    record = {
        "schema_version": "failrec-2.0.0",
        "run_id": args.run_id,
        "commit_sha": args.commit_sha,
        "branch": args.branch,
        "created_at": args.created_at or dt.datetime.now(dt.timezone.utc).isoformat(),
        "runner_image_version": args.runner_image_version,
        "toolchain": toolchain(args.repo_root.resolve()),
        "failed_jobs": jobs,
        "error_signature": first_meaningful_line(raw),
        "masking_ruleset_version": MASKING_RULESET_VERSION,
        "log_sha256": log_sha256,
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

