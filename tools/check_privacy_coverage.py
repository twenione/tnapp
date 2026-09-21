#!/usr/bin/env python3
"""Ensure every privacy scanner field kind has a negative fixture."""
from __future__ import annotations

import argparse
import sys
from collections import Counter
from pathlib import Path

from privacy_scan import FIELD_KINDS, scan_session


def coverage(root: Path) -> Counter[str]:
    found: Counter[str] = Counter()
    for fixture in sorted(root.glob("privacy_*")):
        if not fixture.is_dir():
            continue
        counts = scan_session(fixture)
        if not counts:
            raise ValueError(f"privacy fixture produced no findings: {fixture.name}")
        found.update(counts.keys())
    return found


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--violations-root", type=Path, default=Path("testdata/violations"))
    args = parser.parse_args(argv[1:])
    try:
        found = coverage(args.violations_root)
    except (OSError, ValueError) as exc:
        print(f"FAIL: privacy coverage error: {exc}")
        return 1
    missing = sorted(kind for kind in FIELD_KINDS if not found.get(kind))
    if not any(kind.startswith("unknown.") for kind in found):
        missing.append("unknown.<path>")
    for kind in sorted(found):
        print(f"{kind}: {found[kind]}")
    if missing:
        print("FAIL: missing privacy field-kind coverage: " + ", ".join(missing))
        return 1
    print(f"PASS: privacy field-kind coverage kinds={len(FIELD_KINDS)} unknown=1")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
