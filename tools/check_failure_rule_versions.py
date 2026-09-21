#!/usr/bin/env python3
"""Verify that extractor rule fingerprints are explicitly versioned."""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

from preserve_failure_record import SCHEMA_VERSION, selection_rule_fingerprint


def load(path: Path) -> dict[str, str]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict) or not all(isinstance(k, str) and isinstance(v, str) for k, v in value.items()):
        raise ValueError("rule version file must map versions to fingerprints")
    return value


def validate(path: Path) -> bool:
    values = load(path)
    return values.get(SCHEMA_VERSION) == selection_rule_fingerprint()


def base_values(base_ref: str, path: Path) -> dict[str, str] | None:
    result = subprocess.run(["git", "show", f"{base_ref}:{path.as_posix()}"], capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode:
        return None
    value = json.loads(result.stdout)
    return value if isinstance(value, dict) else None


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--versions", type=Path, default=Path("testdata/failure_extractor/rule_versions.json"))
    parser.add_argument("--base-ref")
    args = parser.parse_args(argv[1:])
    try:
        current = load(args.versions)
    except (OSError, json.JSONDecodeError, ValueError) as exc:
        print(f"FAIL: {exc}")
        return 1
    if current.get(SCHEMA_VERSION) != selection_rule_fingerprint():
        print("FAIL: current rule fingerprint is not recorded")
        return 1
    if args.base_ref:
        base = base_values(args.base_ref, args.versions)
        if base is not None and any(version not in current or current[version] != fingerprint for version, fingerprint in base.items()):
            print("FAIL: existing rule-version fingerprint changed or was removed")
            return 1
    print(f"PASS: {SCHEMA_VERSION} fingerprint={current[SCHEMA_VERSION]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
