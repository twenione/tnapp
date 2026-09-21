#!/usr/bin/env python3
"""Negative control for the privacy field-kind coverage checker."""
from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CHECKER = ROOT / "tools" / "check_privacy_coverage.py"
SOURCE = ROOT / "testdata" / "violations"


def run(root: Path, expected: int) -> bool:
    result = subprocess.run(
        [sys.executable, str(CHECKER), "--violations-root", str(root)],
        capture_output=True,
        text=True,
        check=False,
    )
    return result.returncode == expected


def main() -> int:
    failures = 0
    failures += not run(SOURCE, 0)
    temp = Path(tempfile.mkdtemp(prefix=".privacy-coverage-negative-", dir=ROOT))
    try:
        for fixture in SOURCE.glob("privacy_*"):
            if fixture.is_dir():
                shutil.copytree(fixture, temp / fixture.name)
        shutil.rmtree(temp / "privacy_loc_t")
        failures += not run(temp, 1)
    finally:
        shutil.rmtree(temp, ignore_errors=True)
    print(f"privacy_coverage cases=2 failures={failures}")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
