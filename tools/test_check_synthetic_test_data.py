#!/usr/bin/env python3
"""Negative controls for check_synthetic_test_data.py."""
from __future__ import annotations

import subprocess
import sys
import tempfile
import shutil
from datetime import datetime, timezone
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
    real_seconds = str(int(datetime(2026, 9, 20, tzinfo=timezone.utc).timestamp()))
    real_millis = real_seconds + "000"
    synthetic_seconds = f"{datetime(2026, 1, 15, tzinfo=timezone.utc).timestamp():.1f}"
    synthetic_millis = str(int(float(synthetic_seconds) * 1000))
    underscore = "_".join((real_seconds[:4], real_seconds[4:7], real_seconds[7:])) + "L"
    exponent = f"{real_seconds[0]}.{real_seconds[1:]}E9"
    cases = [
        ("case.kt", f"val a = {underscore}; val b = {exponent};", 1),
        ("case.json", f'{{"t":{real_millis}}}', 1),
        ("outside.json", f'{{"t":{synthetic_seconds}}}', 1),
        ("testdata/violations/privacy_demo/events.ndjson", f'{{"t":{synthetic_seconds}}}', 0),
        ("testdata/sessions/demo/manifest.json", '{"started_at_wall":"2026-09-20T01:03:53Z"}', 1),
        ("testdata/sessions/demo/manifest.json", '{"started_at_wall":"2026-01-15T03:04:05Z"}', 0),
        ("hash.txt", f"sha256:{real_millis}abcdef", 0),
    ]
    failures = sum(not run_case(path, content, expected) for path, content, expected in cases)
    print(f"check_synthetic_test_data cases={len(cases)} failures={failures}")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
