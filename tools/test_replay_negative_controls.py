#!/usr/bin/env python3
"""Negative controls for replay reason and route metadata comparisons."""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path


def run_case(source: Path, cli: str, *, mutate_reason: bool = False, mutate_elevation: bool = False, mutate_details: bool = False) -> int:
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
        if mutate_details:
            for event in events:
                if event.get("stream") == "guide" and "trigger" not in event:
                    details = event.setdefault("reason", {}).setdefault("details", {})
                    key = next((name for name, value in details.items() if _numeric(value) is not None), None)
                    if key is None:
                        details["unexpected_detail"] = "mutation"
                    else:
                        details[key] = str(float(details[key]) + 500.0)
                    (root / "events.ndjson").write_text("\n".join(json.dumps(item) for item in events) + "\n", encoding="utf-8")
                    break
        result = subprocess.run([sys.executable, "tools/replay.py", str(root), "--cli", cli, "--strict"], capture_output=True, text=True)
        if result.returncode != 1 or "FAIL:" not in (result.stdout + result.stderr):
            print(f"FAIL: replay negative control did not reject reason={mutate_reason} elevation={mutate_elevation}")
            print(result.stdout + result.stderr)
            return 1
    finally:
        shutil.rmtree(work, ignore_errors=True)
    return 0


def _numeric(value: object) -> float | None:
    try:
        return float(value) if not isinstance(value, bool) else None
    except (TypeError, ValueError):
        return None


def run_on_demand_case(source: Path, cli: str) -> int:
    work = Path(".tmp-replay-on-demand")
    shutil.rmtree(work, ignore_errors=True)
    try:
        root = work / "session"
        shutil.copytree(source, root)
        events = [json.loads(line) for line in (root / "events.ndjson").read_text(encoding="utf-8").splitlines() if line.strip()]
        frame = next(event for event in reversed(events) if event.get("stream") == "guide" and "trigger" not in event)
        loc = next(event for event in reversed(events) if event.get("stream") == "loc")
        trigger = {"seq": max(event.get("seq", 0) for event in events) + 1, "t": frame["t"] + 0.1, "stream": "guide", "src_seq": loc["seq"], "trigger": "on-demand", "decision": "STATUS", "inputs": {}, "reason": {"rule": "on-demand.route-status", "details": {"source": "test"}}, "state_hash": "sha256:test", "output_text": ""}
        events.append(trigger)
        (root / "events.ndjson").write_text("\n".join(json.dumps(event) for event in events) + "\n", encoding="utf-8")
        probe_command = [str(Path(cli).resolve()), "--route", str(root / "route.gpx"), "--session", str(root)]
        if Path(cli).suffix.lower() in {".bat", ".cmd"}:
            probe_command = ["cmd", "/c", *probe_command]
        probe = subprocess.run(probe_command, capture_output=True, text=True)
        if probe.returncode:
            print("FAIL: on-demand probe failed\n" + probe.stdout + probe.stderr)
            return 1
        trace = [json.loads(line) for line in probe.stdout.splitlines() if line.strip()]
        actual = next((item for item in trace if item.get("kind") == "trigger"), None)
        if actual is None:
            print("FAIL: on-demand probe emitted no trigger trace")
            return 1
        frame_trace = [item for item in trace if item.get("kind") == "guide"]
        frame_events = [event for event in events if event.get("stream") == "guide" and "trigger" not in event]
        if len(frame_trace) != len(frame_events):
            print("FAIL: on-demand probe frame count mismatch")
            return 1
        for event, item in zip(frame_events, frame_trace):
            event["decision"] = item.get("decision")
            event.setdefault("reason", {})["rule"] = item.get("reason_rule", "")
            event["reason"]["details"] = item.get("reason_details", {})
        trigger["reason"]["details"].update(actual.get("status_details", {}))
        (root / "events.ndjson").write_text("\n".join(json.dumps(event) for event in events) + "\n", encoding="utf-8")
        result = subprocess.run([sys.executable, "tools/replay.py", str(root), "--cli", cli, "--strict"], capture_output=True, text=True)
        if result.returncode != 0 or "mismatches=0" not in (result.stdout + result.stderr):
            print("FAIL: on-demand re-derivation case\n" + result.stdout + result.stderr)
            return 1
    finally:
        shutil.rmtree(work, ignore_errors=True)
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cli", required=True)
    args = parser.parse_args()
    source = Path("testdata/sessions/golden/golden_engine")
    failures = (
        run_case(source, args.cli, mutate_reason=True)
        + run_case(source, args.cli, mutate_elevation=True)
        + run_case(source, args.cli, mutate_details=True)
        + run_on_demand_case(source, args.cli)
    )
    if failures:
        print(f"FAIL: replay negative controls failures={failures}")
        return 1
    print("PASS: replay exact detail, reason.rule, elevation metadata, and on-demand re-derivation controls")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
