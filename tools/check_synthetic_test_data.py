#!/usr/bin/env python3
"""Reject real-session time literals that enter test data or source files.

The checker reports only rule and file:line.  It is intentionally separate
from privacy_scan.py: privacy fixtures keep their synthetic 2026 values so
the privacy scanner can continue to prove that it detects them, while this
rule prevents those values from escaping into production-facing test data.
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path


UTC = timezone.utc
YEAR_START = datetime(2026, 1, 1, tzinfo=UTC).timestamp()
YEAR_END = datetime(2027, 1, 1, tzinfo=UTC).timestamp()
SYNTH_START = datetime(2026, 1, 1, tzinfo=UTC).timestamp()
SYNTH_END = datetime(2026, 2, 1, tzinfo=UTC).timestamp()
NUMBER = re.compile(
    r"(?<![\w.\-])\d[\d_]*(?:\.\d[\d_]*)?(?:[eE][+-]?\d+)?[LlFfDd]?(?![\w.])"
)
ISO_2026 = re.compile(r"2026-(\d{2})-")


def _tracked(root: Path) -> list[Path]:
    result = subprocess.run(
        ["git", "-C", str(root), "ls-files", "-z"],
        check=True,
        capture_output=True,
    )
    return [root / item for item in result.stdout.decode().split("\0") if item]


def _paths(root: Path, explicit: list[str] | None) -> list[Path]:
    if explicit:
        return [root / item for item in explicit]
    return _tracked(root)


def _text(path: Path) -> str | None:
    try:
        data = path.read_bytes()
    except OSError:
        return None
    if b"\0" in data:
        return None
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return data.decode("utf-8", errors="ignore")


def _relative(root: Path, path: Path) -> str:
    return path.relative_to(root).as_posix()


def _is_privacy_fixture(relative: str) -> bool:
    return relative.startswith("testdata/violations/privacy_")


def _numeric_hits(relative: str, lines: list[str]) -> list[tuple[str, int]]:
    hits: list[tuple[str, int]] = []
    for line_number, line in enumerate(lines, 1):
        for match in NUMBER.finditer(line):
            token = match.group(0).rstrip("LlFfDd").replace("_", "")
            try:
                number = float(token)
            except ValueError:
                continue
            seconds = number if number < YEAR_END * 10 else number / 1000.0
            if YEAR_START <= seconds < YEAR_END:
                synthetic = SYNTH_START <= seconds < SYNTH_END
                if not (synthetic and _is_privacy_fixture(relative)):
                    hits.append(("N", line_number))
                    break
    return hits


def _iso_hits(relative: str, lines: list[str]) -> list[tuple[str, int]]:
    if not (relative.startswith("testdata/sessions/") or relative.startswith("testdata/violations/")):
        return []
    hits: list[tuple[str, int]] = []
    for line_number, line in enumerate(lines, 1):
        for match in ISO_2026.finditer(line):
            if match.group(1) != "01":
                hits.append(("S", line_number))
                break
    return hits


def scan(root: Path, paths: list[str] | None = None) -> list[tuple[str, int, str]]:
    findings: list[tuple[str, int, str]] = []
    for path in _paths(root, paths):
        relative = _relative(root, path)
        if relative.startswith("docs/") or relative.endswith(".md"):
            continue
        content = _text(path)
        if content is None:
            continue
        lines = content.splitlines()
        findings.extend((rule, line, relative) for rule, line in _numeric_hits(relative, lines))
        findings.extend((rule, line, relative) for rule, line in _iso_hits(relative, lines))
    return sorted(set(findings), key=lambda item: (item[2], item[1], item[0]))


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--paths", nargs="*", help="relative paths; defaults to git-tracked files")
    args = parser.parse_args(argv[1:])
    try:
        findings = scan(args.root.resolve(), args.paths)
    except (OSError, subprocess.CalledProcessError) as exc:
        print(f"ERROR: synthetic-data check failed: {exc}", file=sys.stderr)
        return 2
    for rule, line, relative in findings:
        print(f"{rule} {relative}:{line}")
    if findings:
        print(f"FAIL: synthetic-data rules matched ({len(findings)} findings)")
        return 1
    print("PASS: no real-session time literals found")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
