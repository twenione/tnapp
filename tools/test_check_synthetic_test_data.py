#!/usr/bin/env python3
"""Negative controls for check_synthetic_test_data.py."""
from __future__ import annotations

import subprocess
import sys
import tempfile
import shutil
from pathlib import Path


CHECKER = Path(__file__).with_name("check_synthetic_test_data.py")


def run_case(relative: str, content: str, expected: int) -> bool:
    root = Path(tempfile.mkdtemp(prefix=".synthetic-data-negative-", dir=Path(__file__).resolve().parents[1]))
    try:
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        result = subprocess.run(
            [sys.executable, str(CHECKER), "--root", str(root), "--paths", relative],
            capture_output=True,
            text=True,
            check=False,
        )
        return result.returncode == expected
    finally:
        shutil.rmtree(root, ignore_errors=True)


def main() -> int:
    cases = [
        ("case.kt", "val a = 1_789_866_232L; val b = 1.789866232945E9;", 1),
        ("case.json", '{"t":1789866232945}', 1),
        ("outside.json", '{"t":1768000000.5}', 1),
        ("testdata/violations/privacy_demo/events.ndjson", '{"t":1768000000.5}', 0),
        ("testdata/sessions/demo/manifest.json", '{"started_at_wall":"2026-09-20T01:03:53Z"}', 1),
        ("testdata/sessions/demo/manifest.json", '{"started_at_wall":"2026-01-15T03:04:05Z"}', 0),
        ("hash.txt", "sha256:1789866232945abcdef", 0),
    ]
    failures = sum(not run_case(path, content, expected) for path, content, expected in cases)
    print(f"check_synthetic_test_data cases={len(cases)} failures={failures}")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
