#!/usr/bin/env python3
"""Offline tests for README aggregation, validation, and idempotence."""
from __future__ import annotations

import json
import base64
import io
import shutil
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

from replace_failure_log_notice import HEADING, aggregate, build_notice, online

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
    actual_shape = prefix + HEADING + "\nold\x0c\x1b\n"
    generated_actual_shape = build_notice(actual_shape, records)
    assert generated_actual_shape.startswith(prefix)
    assert "\x0c" not in generated_actual_shape and "\x1b" not in generated_actual_shape
    failures = 0
    for bad in (prefix, prefix + HEADING + "\n" + HEADING + "\n"):
        try:
            build_notice(bad, records)
        except ValueError:
            failures += 1
    assert failures == 2
    try:
        build_notice(prefix + "\x00" + HEADING + "\nold\n", records)
    except ValueError:
        pass
    else:
        raise AssertionError("control character was accepted")
    mixed = [
        record("valid", "FAIL: compiler", "FAIL: compiler", "2026-09-21T00:00:00Z"),
        record("invalid", echo, echo, "2026-09-21T00:00:01Z"),
        {"schema_version": "failrec-2.0.1", "run_id": "newer"},
        {"schema_version": "failrec-1.0.0", "run_id": "legacy"},
    ]
    encode = lambda value: {"content": base64.b64encode(json.dumps(value).encode("utf-8")).decode("ascii")}
    listing = [{"name": f"{index}.json", "url": f"https://stub/records/{index}"} for index in range(len(mixed))]

    def fake_request(url: str, token: str, *, method: str = "GET", payload: dict | None = None):
        if url.endswith("/contents/README.md"):
            return {"content": base64.b64encode((prefix + HEADING + "\nold\n").encode("utf-8")).decode("ascii")}
        if url.endswith("/contents/records"):
            return listing
        return encode(mixed[int(url.rsplit("/", 1)[-1])])

    output = io.StringIO()
    with patch("replace_failure_log_notice._request", side_effect=fake_request), redirect_stdout(output):
        assert online("stub/repo", "token", apply=False) == 0
    assert "records=2" in output.getvalue()
    print("online_schema_filter=PASS fetched=4 accepted=2")
    print("readme_notice cases=8 failures=0 actual_shape=PASS valid_key_value_signature=PASS idempotent=PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
