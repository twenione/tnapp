#!/usr/bin/env python3
"""Read-only audit of stored failure signatures against the D-047 extractor."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from preserve_failure_record import first_meaningful_line


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("records", type=Path, help="checked-out records directory")
    args = parser.parse_args()
    count = 0
    for path in sorted(args.records.rglob("*.json")):
        try:
            record = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        if not isinstance(record, dict) or not str(record.get("schema_version", "")).startswith("failrec-"):
            continue
        recomputed = first_meaningful_line(str(record.get("log", "")))
        print(json.dumps({
            "run_id": record.get("run_id"),
            "schema_version": record.get("schema_version"),
            "stored_error_signature": record.get("error_signature"),
            "recomputed_error_signature": recomputed,
        }, ensure_ascii=False))
        count += 1
    print(f"AUDIT records={count} read_only=true")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
