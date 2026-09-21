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
KOTLIN_COMPILE_LINE = re.compile(r"(?i)^\s*e:\s+(?:file:///|/).+")
TEST_FAILURE_LINE = re.compile(r"(?i)^\s*[^\r\n>]+>\s+[^\r\n]+\s+FAILED\s*$")
EXPLICIT_FAILURE_LINE = re.compile(r"(?i)(?:\bFAIL\s*:|\bTraceback\b|\b[A-Za-z_$][\w$]*(?:Exception|Error)\b|\bAssertionError\b)")
ERROR_LINE = re.compile(r"(?i)##\[error\]")
GRADLE_FAILURE_LINE = re.compile(r"(?i)^\s*>\s*Task\s+\S+\s+FAILED\s*$")
EXIT_FAILURE_LINE = re.compile(r"(?i)##\[error\]\s*Process completed with exit code\s+\d+\.")
ACTION_INPUT_LINE = re.compile(r"^\s*[A-Za-z][A-Za-z0-9_.-]*:\s+\S")
EMPTY_LOG_SHA256 = "sha256:e3b0c44298fc1c149af4c8996fb92427ae41e4649b934ca495991b7852b855"
ALLOWED_SOURCES = {"runtime", "declared", "unavailable"}
SCHEMA_VERSION = "failrec-2.0.2"
SELECTION_PRIORITY_PATTERNS = (
    ("kotlin_compile", KOTLIN_COMPILE_LINE),
    ("test_failure", TEST_FAILURE_LINE),
    ("explicit_failure", EXPLICIT_FAILURE_LINE),
    ("actions_error", ERROR_LINE),
    ("gradle_failure", GRADLE_FAILURE_LINE),
    ("process_exit", EXIT_FAILURE_LINE),
)
INLINE_RULE_CASES = (
    ("what_went_wrong_next_nonempty", "first non-empty line after marker"),
    ("what_went_wrong_marker_candidate", "skip ##[ marker"),
    ("what_went_wrong_outside_scope", "ignore outside failed step"),
)


def sanitize(text: str) -> str:
    return SECRET.sub("[REDACTED]", text)


def _without_run_echo(lines: list[str]) -> list[str]:
    result: list[str] = []
    in_run_echo = False
    for line in lines:
        if "##[group]Run " in line:
            in_run_echo = True
            continue
        if in_run_echo:
            if "##[endgroup]" in line:
                in_run_echo = False
            continue
        result.append(line)
    return result


def _step_scope(lines: list[str]) -> list[str]:
    """Limit selection to the failed step before the last Actions error line."""
    error_indexes = [index for index, line in enumerate(lines) if ERROR_LINE.search(line)]
    if not error_indexes:
        return _without_run_echo(lines)
    last_error = error_indexes[-1]
    endgroups = [index for index in range(last_error + 1) if "##[endgroup]" in lines[index]]
    start = (endgroups[-1] + 1) if endgroups else 0
    return _without_run_echo(lines[start : last_error + 1])


def _line_matches(line: str, pattern: re.Pattern[str]) -> bool:
    # Actions timestamps and the log stream prefix are part of the preserved
    # signature, but must not affect the classifier.
    value = re.sub(r"^\d{4}-\d\d-\d\dT[^ ]+Z\s+", "", line)
    return bool(pattern.search(value))


def _candidate_lines(lines: list[str], pattern: re.Pattern[str]) -> list[str]:
    return [line for line in lines if line.strip() and _line_matches(line, pattern)]


def _what_went_wrong_candidate(lines: list[str]) -> str | None:
    """Return the first useful line after a failed step's Gradle marker."""
    for index, line in enumerate(lines):
        if not _line_matches(line, re.compile(r"^\s*\*\s*What went wrong:\s*$")):
            continue
        for candidate in lines[index + 1 :]:
            if not candidate.strip():
                continue
            value = re.sub(r"^\d{4}-\d\d-\d\dT[^ ]+Z\s+", "", candidate).lstrip()
            if value.startswith("##["):
                break
            return candidate
    return None


def selection_rule_fingerprint(
    *,
    priority_patterns: tuple[tuple[str, re.Pattern[str]], ...] = SELECTION_PRIORITY_PATTERNS,
    inline_cases: tuple[tuple[str, str], ...] = INLINE_RULE_CASES,
) -> str:
    payload = {
        "priority": [(name, pattern.pattern, pattern.flags) for name, pattern in priority_patterns],
        "inline_cases": list(inline_cases),
    }
    return hashlib.sha256(json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def first_meaningful_line(text: str) -> str:
    """Choose the first error line using the D-047 priority table.

    The action input echo is deliberately removed before matching. Priority is
    Kotlin/Java compile, test failure, explicit tool failure, Actions error,
    Gradle task failure, then process-exit error. The original line is returned
    unchanged except for the established secret masking rule.
    """
    lines = _step_scope(text.splitlines())
    priorities = tuple(pattern for _, pattern in SELECTION_PRIORITY_PATTERNS[:5])
    for pattern in priorities:
        for line in _candidate_lines(lines, pattern):
            if pattern is ERROR_LINE and EXIT_FAILURE_LINE.search(line):
                continue
            return sanitize(line)
    candidate = _what_went_wrong_candidate(lines)
    if candidate is not None:
        return sanitize(candidate)
    for line in _candidate_lines(lines, EXIT_FAILURE_LINE):
        return sanitize(line)
    for line in lines:
        value = line.strip()
        if value and not value.startswith("#") and not ACTION_INPUT_LINE.match(value):
            return sanitize(line)
    return "no meaningful error line found"


def signature_is_run_echo(log: str, signature: str) -> bool:
    """Return true only when the signature occurs exclusively in a Run echo group."""
    lines = log.splitlines()
    matches = [index for index, line in enumerate(lines) if line == signature or sanitize(line) == signature]
    if not matches:
        return False
    in_group = False
    grouped: set[int] = set()
    for index, line in enumerate(lines):
        if "##[group]Run " in line:
            in_group = True
        elif in_group and "##[endgroup]" in line:
            in_group = False
        elif in_group and index in matches:
            grouped.add(index)
    return len(grouped) == len(matches)


def is_action_input_echo(log: str, signature: str | None = None) -> bool:
    """Compatibility wrapper for the position-based echo check."""
    if signature is None:
        signature = log
        log = signature
    return signature_is_run_echo(log, signature)


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


def failed_job_log_text(log_root: Path) -> str:
    """Read only the first failed job when metadata identifies job logs.

    A workflow can fail in several jobs. D-047 fixes the first failed job as
    the signature source so the record remains deterministic across retries.
    """
    log_files = sorted(
        p for p in log_root.rglob("*")
        if p.is_file() and p.name != "failed_jobs.json" and p.suffix.lower() in {".log", ".txt"}
    ) if log_root.exists() else []
    jobs = failed_jobs(log_root)
    if jobs:
        for job in jobs:
            matches = [path for path in log_files if path.stem.endswith("-" + job) or path.stem == job]
            if matches:
                return "\n".join(path.read_text(encoding="utf-8", errors="replace") for path in matches)
    return "\n".join(path.read_text(encoding="utf-8", errors="replace") for path in log_files)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--logs", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--commit-sha", required=True)
    parser.add_argument("--branch", required=True)
    parser.add_argument("--created-at", required=True)
    parser.add_argument("--runner-image-version", required=True)
    parser.add_argument("--trigger-event", default="unknown")
    parser.add_argument("--repo-root", type=Path, default=Path("."))
    args = parser.parse_args()
    log_files = (
        sorted(
            p for p in args.logs.rglob("*")
            if p.is_file() and p.name != "failed_jobs.json" and p.suffix.lower() in {".log", ".txt"}
        )
        if args.logs.exists()
        else []
    )
    raw = "\n".join(p.read_text(encoding="utf-8", errors="replace") for p in log_files)
    signature_source = failed_job_log_text(args.logs)
    sanitized_log = sanitize(raw)
    log_sha256 = "sha256:" + hashlib.sha256(sanitized_log.encode()).hexdigest()
    if not sanitized_log.strip() or log_sha256 == EMPTY_LOG_SHA256:
        print("FAIL: refusing to write an empty failure log", flush=True)
        return 1
    jobs = failed_jobs(args.logs)
    record = {
        "schema_version": SCHEMA_VERSION,
        "run_id": args.run_id,
        "commit_sha": args.commit_sha,
        "branch": args.branch,
        "created_at": args.created_at or dt.datetime.now(dt.timezone.utc).isoformat(),
        "runner_image_version": args.runner_image_version,
        "trigger_event": args.trigger_event,
        "toolchain": toolchain(args.repo_root.resolve()),
        "failed_jobs": jobs,
        "error_signature": first_meaningful_line(signature_source),
        "masking_ruleset_version": MASKING_RULESET_VERSION,
        "log_sha256": log_sha256,
        "log": sanitized_log,
    }
    if is_action_input_echo(sanitized_log, record["error_signature"]):
        print("::warning::error_signature matched an Actions input echo; record was still appended", flush=True)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"record={args.out}")
    print(f"masking_ruleset_version={MASKING_RULESET_VERSION}")
    print(f"log_bytes={len(sanitized_log.encode())}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

