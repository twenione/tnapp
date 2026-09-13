#!/usr/bin/env python3
"""Static purity guard for the :core-guide source tree.

The scanner ignores comments, scans Kotlin/Java source files, and can also be
given a fixture file directly. It intentionally reports the original source
line so the failure signature remains useful to AKB consumers.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

FORBIDDEN = (
    (re.compile(r"\bSystem\.currentTimeMillis\s*\("), "clock access"),
    (re.compile(r"\bSystem\.nanoTime\s*\("), "clock access"),
    (re.compile(r"\bSystemClock\.(?:elapsedRealtime|uptimeMillis)\s*\("), "clock access"),
    (re.compile(r"\bInstant\.now\s*\("), "clock access"),
    (re.compile(r"\bLocalDate(?:Time)?\.now\s*\("), "clock access"),
    (re.compile(r"\bMath\.random\s*\("), "random access"),
    (re.compile(r"\bRandom(?:\.Default|\s*\()"), "random access"),
    (re.compile(r"\bSecureRandom\b"), "random access"),
    (re.compile(r"\b(?:OkHttpClient|Retrofit|HttpURLConnection|URL)\b"), "network access"),
    (re.compile(r"\b(?:File|FileInputStream|FileOutputStream|Room|SQLiteDatabase)\b"), "I/O access"),
    (re.compile(r"\b(?:LocationManager|FusedLocationProvider|SensorManager|Context)\b"), "platform access"),
    (re.compile(r"\b(?:Thread|GlobalScope|Dispatchers)\b"), "concurrency access"),
)
EXTENSIONS = {".kt", ".java"}
COMMENT = re.compile(r"^\s*(?://|/\*|\*|\*/)")


def files(root: Path):
    if root.is_file():
        yield root
        return
    if not root.exists():
        return
    for path in sorted(root.rglob("*")):
        if path.is_file() and path.suffix in EXTENSIONS:
            yield path


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print("usage: check_engine_purity.py <source-dir-or-file> [... ]", file=sys.stderr)
        return 2
    violations: list[tuple[Path, int, str, str]] = []
    missing = [Path(arg) for arg in argv[1:] if not Path(arg).exists()]
    if missing:
        for path in missing:
            print(f"ERROR: source root missing: {path}", file=sys.stderr)
        return 2
    for root in (Path(arg) for arg in argv[1:]):
        for path in files(root):
            try:
                lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
            except OSError as exc:
                print(f"ERROR: cannot read {path}: {exc}", file=sys.stderr)
                return 2
            for number, line in enumerate(lines, 1):
                if COMMENT.match(line):
                    continue
                for pattern, reason in FORBIDDEN:
                    if pattern.search(line):
                        violations.append((path, number, line.strip(), reason))
    if not violations:
        print("OK: core-guide purity scan found 0 forbidden symbol(s)")
        return 0
    print(f"FAIL: core-guide purity scan found {len(violations)} violation(s)")
    for path, number, line, reason in violations:
        print(f"  {path}:{number}: {line}")
        print(f"    reason: {reason}")
    return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))

