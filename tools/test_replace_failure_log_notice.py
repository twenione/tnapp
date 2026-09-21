#!/usr/bin/env python3
"""Offline tests for README aggregation, validation, and idempotence."""
from __future__ import annotations

import json
import shutil
from pathlib import Path

from replace_failure_log_notice import HEADING, aggregate, build_notice

ROOT = Path(__file__).resolve().parents[1]


def record(run_id: str, signature: str, log: str, created_at: str) -> dict:
    return {"schema_version": "failrec-2.0.0", "run_id": run_id, "error_signature": signature, "log": log, "created_at": created_at}


def main() -> int:
    prefix = "# tnapp failure log\n"
    echo = "2026-09-21T00:00:00.0000000Z dependency-graph-continue-on-failure: true"
    records = [
        record("2", echo, "2026-09-21T00:00:00.0000000Z ##[group]Run setup\n" + echo + "\n2026-09-21T00:00:00.0000000Z ##[endgroup]", "2026-09-21T00:00:02Z"),
        record("1", "FAIL: compiler", "FAIL: compiler", "2026-09-20T00:00:01Z"),
    ]
    current = prefix + HEADING + "\nold\n"
    generated = build_notice(current, records)
    assert generated.startswith(prefix) and generated.count(HEADING) == 1
    assert "invalid for 1 records and valid for 1 records" in generated
    assert "2" in generated and "1" in generated
    assert build_notice(generated, records) == generated
    failures = 0
    for bad in (prefix, prefix + HEADING + "\n" + HEADING + "\n"):
        try:
            build_notice(bad, records)
        except ValueError:
            failures += 1
    assert failures == 2
    try:
        build_notice(prefix + HEADING + "\n\x00", records)
    except ValueError:
        pass
    else:
        raise AssertionError("control character was accepted")
    print("readme_notice cases=6 failures=0 valid_key_value_signature=PASS idempotent=PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
