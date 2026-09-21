#!/usr/bin/env python3
"""Read-only audit of stored failure signatures against the D-047 extractor."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from preserve_failure_record import (
    ACTION_INPUT_LINE,
    ERROR_LINE,
    EXIT_FAILURE_LINE,
    EXPLICIT_FAILURE_LINE,
    GRADLE_FAILURE_LINE,
    KOTLIN_COMPILE_LINE,
    TEST_FAILURE_LINE,
    first_meaningful_line,
    _candidate_lines,
    _step_scope,
    sanitize,
)


def first_meaningful_line_201(text: str) -> str:
    """Reproduce the 2.0.1 selector without the 2.0.2 marker rule."""
    lines = _step_scope(text.splitlines())
    for pattern in (KOTLIN_COMPILE_LINE, TEST_FAILURE_LINE, EXPLICIT_FAILURE_LINE, ERROR_LINE, GRADLE_FAILURE_LINE, EXIT_FAILURE_LINE):
        for line in _candidate_lines(lines, pattern):
            if pattern is ERROR_LINE and EXIT_FAILURE_LINE.search(line):
                continue
            return sanitize(line)
    for line in lines:
        value = line.strip()
        if value and not value.startswith("#") and not ACTION_INPUT_LINE.match(value):
            return sanitize(line)
    return "no meaningful error line found"


def main() -> int:
    if hasattr(__import__("sys").stdout, "reconfigure"):
        __import__("sys").stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("records", type=Path, help="checked-out records directory")
    args = parser.parse_args()
    count = 0
    changed = 0
    weak = 0
    for path in sorted(args.records.rglob("*.json")):
        try:
            record = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        if not isinstance(record, dict) or not str(record.get("schema_version", "")).startswith("failrec-"):
            continue
        log = str(record.get("log", ""))
        recomputed_201 = first_meaningful_line_201(log)
        recomputed = first_meaningful_line(log)
        if recomputed_201 != recomputed:
            changed += 1
        if not log.strip() or recomputed in {"no meaningful error line found"}:
            weak += 1
        print(json.dumps({
            "run_id": record.get("run_id"),
            "schema_version": record.get("schema_version"),
            "stored_error_signature": record.get("error_signature"),
            "rule_2_0_1_error_signature": recomputed_201,
            "rule_2_0_2_error_signature": recomputed,
        }, ensure_ascii=False))
        count += 1
    print(f"AUDIT records={count} changed_2_0_1_to_2_0_2={changed} weak_signatures={weak} read_only=true")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
