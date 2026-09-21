#!/usr/bin/env python3
"""Negative controls for replay reason and route metadata comparisons."""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path


def run_case(source: Path, cli: str, *, mutate_reason: bool = False, mutate_elevation: bool = False) -> int:
    work = Path(".tmp-replay-negative")
    shutil.rmtree(work, ignore_errors=True)
    try:
        root = work / "session"
        shutil.copytree(source, root)
        events = [json.loads(line) for line in (root / "events.ndjson").read_text(encoding="utf-8").splitlines() if line.strip()]
        if mutate_reason:
            for event in events:
                if event.get("stream") == "guide":
                    event.setdefault("reason", {})["rule"] = "mutation.reason-rule"
                    break
            (root / "events.ndjson").write_text("\n".join(json.dumps(event) for event in events) + "\n", encoding="utf-8")
        if mutate_elevation:
            manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
            manifest.setdefault("route", {})["elevation_use"] = {"used": True, "reason": "ok"}
            (root / "manifest.json").write_text(json.dumps(manifest) + "\n", encoding="utf-8")
        result = subprocess.run([sys.executable, "tools/replay.py", str(root), "--cli", cli, "--strict"], capture_output=True, text=True)
        if result.returncode != 1 or "FAIL:" not in (result.stdout + result.stderr):
            print(f"FAIL: replay negative control did not reject reason={mutate_reason} elevation={mutate_elevation}")
            print(result.stdout + result.stderr)
            return 1
    finally:
        shutil.rmtree(work, ignore_errors=True)
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cli", required=True)
    args = parser.parse_args()
    source = Path("testdata/sessions/golden/golden_engine")
    failures = run_case(source, args.cli, mutate_reason=True) + run_case(source, args.cli, mutate_elevation=True)
    if failures:
        print(f"FAIL: replay negative controls failures={failures}")
        return 1
    print("PASS: replay reason.rule and elevation metadata negative controls")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
