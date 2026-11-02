#!/usr/bin/env python3
"""Verify the effective StartPhaseGridTest sample count from Gradle/JUnit output."""
from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

GRID_LINE = re.compile(r"START-GRID-N n=([0-9]+)")
GRID_PREFIX = "START-GRID-N n="


def check_texts(texts: list[str], expected_n: int) -> tuple[list[str], list[int]]:
    failures: list[str] = []
    values: list[int] = []
    for text in texts:
        matches = list(GRID_LINE.finditer(text))
        malformed = sum(line.count(GRID_PREFIX) for line in text.splitlines()) - len(matches)
        if malformed:
            failures.append(f"found {malformed} malformed START-GRID-N line(s)")
        values.extend(int(match.group(1)) for match in matches)
    if not values:
        failures.append("START-GRID-N line not found")
    distinct = sorted(set(values))
    if len(distinct) > 1:
        failures.append(f"mixed START-GRID-N values: {distinct}")
    if values and any(value != expected_n for value in values):
        failures.append(f"expected START-GRID-N n={expected_n}, found {distinct}")
    return failures, values


def read_output(path: Path) -> tuple[list[str], list[str]]:
    failures: list[str] = []
    texts: list[str] = []
    if path.is_dir():
        files = sorted(path.rglob("*.xml"))
        if not files:
            failures.append(f"no JUnit XML files found under {path}")
    elif path.is_file():
        files = [path]
    else:
        files = []
        failures.append(f"output path does not exist: {path}")
    for file in files:
        try:
            if file.suffix.lower() == ".xml":
                root = ET.parse(file).getroot()
                texts.append("\n".join(part for part in root.itertext()))
            else:
                texts.append(file.read_text(encoding="utf-8", errors="replace"))
        except (OSError, ET.ParseError) as exc:
            failures.append(f"cannot read {file}: {exc}")
    return failures, texts


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path, help="Gradle output log or JUnit XML file/directory")
    parser.add_argument("--expected-n", type=int, required=True)
    args = parser.parse_args()
    if args.expected_n < 1:
        print("FAIL: expected N must be at least 1", file=sys.stderr)
        return 2
    read_failures, texts = read_output(args.output)
    failures, values = check_texts(texts, args.expected_n)
    failures = read_failures + failures
    if failures:
        for failure in failures:
            print(f"FAIL: {failure}", file=sys.stderr)
        return 1
    print(f"PASS: observed START-GRID-N n={args.expected_n} ({len(values)} occurrence(s))")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
