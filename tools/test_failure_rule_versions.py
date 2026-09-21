#!/usr/bin/env python3
"""Negative controls for D-050 rule-version binding."""
from __future__ import annotations

import json
from pathlib import Path

from check_failure_rule_versions import validate
from preserve_failure_record import SELECTION_PRIORITY_PATTERNS, selection_rule_fingerprint

ROOT = Path(__file__).resolve().parents[1]
VERSIONS = ROOT / "testdata" / "failure_extractor" / "rule_versions.json"


def main() -> int:
    current = json.loads(VERSIONS.read_text(encoding="utf-8"))
    mutated = selection_rule_fingerprint(priority_patterns=tuple(reversed(SELECTION_PRIORITY_PATTERNS)))
    m1 = current.get("failrec-2.0.2") == mutated
    missing = ROOT / "testdata" / "failure_extractor" / ".tmp-rule-versions.json"
    missing.write_text(json.dumps({}), encoding="utf-8")
    try:
        m2 = validate(missing)
    finally:
        missing.unlink(missing_ok=True)
    print(f"rule_versions m1_reordered_same_version={'FAIL' if m1 else 'FAIL_EXPECTED'} m2_version_only={'FAIL' if m2 else 'FAIL_EXPECTED'}")
    return 1 if m1 or m2 else 0


if __name__ == "__main__":
    raise SystemExit(main())
