#!/usr/bin/env python3
"""Validate the v0 session envelope and append-only event stream.

The D-040/D-045 time contract is explicit: ``t`` is the event timestamp and
must be monotonic within each provider stream. For an ``envelope`` event the
provider is the pair ``(stream, event_stream)``; loc and guide envelopes may
cross in time while each provider remains monotonic. ``seq`` is append order
and must increase strictly across the complete file.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Any

MANIFEST_REQUIRED = ("session_id", "schema_version", "started_at_wall", "app", "engine", "route", "clock")
STREAM_REQUIRED = {
    "envelope": ("event_stream", "event_type"),
    "loc": ("lat", "lon", "accuracy", "provider"),
    "imu": ("heading_deg",),
    "baro": ("pressure_hpa",),
    "guide": ("decision", "inputs", "reason", "state_hash"),
    "tts": ("utterance", "t_start"),
    "stt": ("text", "confidence"),
    "ar": ("tracking_state",),
    "sys": ("battery_pct",),
    "err": ("kind",),
}
FORBIDDEN_KEY = re.compile(r"(?:imei|android_id|advertising_id|mac_address|email|phone|account_id)", re.I)
UUID4 = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
GIT_CODE_HASH = re.compile(r"^git:(?:[0-9a-fA-F]{40}(?:-dirty)?|unknown)$")


def get(obj: Any, dotted: str) -> Any:
    cur = obj
    for key in dotted.split("."):
        if not isinstance(cur, dict) or key not in cur:
            return None
        cur = cur[key]
    return cur


def forbidden(obj: Any, path: str = "") -> list[str]:
    hits: list[str] = []
    if isinstance(obj, dict):
        for key, value in obj.items():
            current = f"{path}.{key}" if path else key
            if FORBIDDEN_KEY.search(str(key)):
                hits.append(current)
            hits.extend(forbidden(value, current))
    elif isinstance(obj, list):
        for index, value in enumerate(obj):
            hits.extend(forbidden(value, f"{path}[{index}]"))
    return hits


def load_json(path: Path, errors: list[str], label: str) -> Any | None:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        errors.append(f"{label}: missing")
    except json.JSONDecodeError as exc:
        errors.append(f"{label}:{exc.lineno}: invalid JSON: {exc.msg}")
    except OSError as exc:
        errors.append(f"{label}: read error: {exc}")
    return None


def validate(root: Path) -> tuple[list[str], list[str], int]:
    errors: list[str] = []
    warnings: list[str] = []
    manifest = load_json(root / "manifest.json", errors, "manifest.json")
    if not isinstance(manifest, dict):
        return errors, warnings, 0
    for field in MANIFEST_REQUIRED:
        if get(manifest, field) is None:
            errors.append(f"manifest.json: missing required field: {field}")
    if manifest.get("schema_version") != "0.1.0-draft":
        errors.append("manifest.json: schema_version must be 0.1.0-draft")
    session_id = manifest.get("session_id")
    if not isinstance(session_id, str) or not UUID4.match(session_id):
        errors.append(f"manifest.json: session_id must be a UUID: {session_id!r}")
    if manifest.get("privacy", {}).get("upload_default") not in (False, None):
        errors.append("manifest.json: upload_default must be false")
    code_hash = get(manifest, "app.code_hash")
    if isinstance(code_hash, str) and not code_hash.startswith("sha256:") and not GIT_CODE_HASH.fullmatch(code_hash):
        errors.append("manifest.json: app.code_hash must use sha256: or git: prefix")
    for hit in forbidden(manifest):
        errors.append(f"manifest.json: forbidden identifying key: {hit}")

    events_path = root / "events.ndjson"
    try:
        lines = events_path.read_text(encoding="utf-8").splitlines()
    except FileNotFoundError:
        errors.append("events.ndjson: missing")
        return errors, warnings, 0
    except OSError as exc:
        errors.append(f"events.ndjson: read error: {exc}")
        return errors, warnings, 0
    previous_seq = -1
    previous_t_by_key: dict[tuple[str, str | None], float] = {}
    loc_sequences: list[int] = []
    guide_events: list[tuple[int, int | None]] = []
    count = 0
    guide_count = 0
    for line_number, raw in enumerate(lines, 1):
        if not raw.strip():
            continue
        count += 1
        try:
            event = json.loads(raw)
        except json.JSONDecodeError as exc:
            errors.append(f"events.ndjson:{line_number}: invalid JSON: {exc.msg}")
            continue
        if not isinstance(event, dict):
            errors.append(f"events.ndjson:{line_number}: event must be an object")
            continue
        seq = event.get("seq")
        timestamp = event.get("t")
        stream = event.get("stream")
        if not isinstance(seq, int) or isinstance(seq, bool):
            errors.append(f"events.ndjson:{line_number}: seq must be integer")
        elif seq <= previous_seq:
            errors.append(f"events.ndjson:{line_number}: seq {seq} is not greater than {previous_seq}")
        else:
            previous_seq = seq
        if not isinstance(timestamp, (int, float)) or isinstance(timestamp, bool):
            errors.append(f"events.ndjson:{line_number}: t must be numeric")
        elif not isinstance(stream, str):
            pass
        else:
            event_stream = event.get("event_stream") if stream == "envelope" else None
            if stream == "envelope" and not isinstance(event_stream, str):
                errors.append(f"events.ndjson:{line_number}: [envelope] event_stream must be a string for per-stream t ordering")
            time_key = (stream, event_stream)
            previous_t = previous_t_by_key.get(time_key)
            key_label = f"({stream}, {event_stream})" if stream == "envelope" else stream
            if previous_t is not None and timestamp < previous_t:
                errors.append(f"events.ndjson:{line_number}: [{key_label}] t {timestamp} regresses from {previous_t} (D-045 key)")
            else:
                previous_t_by_key[time_key] = float(timestamp)
        if stream not in STREAM_REQUIRED:
            errors.append(f"events.ndjson:{line_number}: unsupported stream: {stream!r}")
        else:
            for field in STREAM_REQUIRED[stream]:
                if field not in event:
                    errors.append(f"events.ndjson:{line_number}: [{stream}] missing field: {field}")
            if stream == "guide":
                guide_count += 1
                src_seq = event.get("src_seq")
                if src_seq is not None and (not isinstance(src_seq, int) or isinstance(src_seq, bool)):
                    errors.append(f"events.ndjson:{line_number}: [guide] src_seq must be integer when present")
                guide_events.append((line_number, src_seq if isinstance(src_seq, int) and not isinstance(src_seq, bool) else None))
                reason = event.get("reason")
                if not isinstance(reason, dict) or not reason.get("rule"):
                    errors.append(f"events.ndjson:{line_number}: guide.reason.rule is required")
            elif stream == "loc" and isinstance(seq, int) and not isinstance(seq, bool):
                loc_sequences.append(seq)
        for hit in forbidden(event):
            errors.append(f"events.ndjson:{line_number}: forbidden identifying key: {hit}")
    if count == 0:
        warnings.append("events.ndjson: no events")
    if guide_count == 0:
        warnings.append("events.ndjson: guide stream is empty")
    src_values = [value for _, value in guide_events if value is not None]
    if src_values:
        if len(src_values) != len(guide_events):
            errors.append("events.ndjson: guide src_seq must be present on every guide event when used")
        for index, src_seq in enumerate(src_values):
            if src_seq not in loc_sequences:
                errors.append(f"events.ndjson: guide src_seq {src_seq} does not reference a loc event")
            if index and src_seq <= src_values[index - 1]:
                errors.append(f"events.ndjson: guide src_seq {src_seq} is not strictly increasing")
        if len(loc_sequences) == len(src_values) and src_values != loc_sequences:
            errors.append("events.ndjson: guide src_seq does not match loc order")
    return errors, warnings, count


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print("usage: validate_session.py <session-dir> [... ]", file=sys.stderr)
        return 2
    total = 0
    failed = False
    session_roots: list[Path] = []
    for argument in argv[1:]:
        root = Path(argument)
        if root.is_dir() and not (root / "manifest.json").exists():
            children = sorted(child for child in root.iterdir() if child.is_dir() and ((child / "manifest.json").exists() or (child / "events.ndjson").exists()))
            session_roots.extend(children or [root])
        else:
            session_roots.append(root)
    for root in session_roots:
        print(f"SESSION {root}")
        if not root.is_dir():
            print(f"FAIL: session directory missing: {root}")
            failed = True
            continue
        errors, warnings, count = validate(root)
        total += count
        for warning in warnings:
            print(f"WARN  {warning}")
        if errors:
            print(f"FAIL  {len(errors)} error(s)")
            for error in errors:
                print(f"  {error}")
            failed = True
        else:
            print(f"OK    events={count}")
    print(f"RESULT sessions={len(session_roots)} events={total} status={'FAIL' if failed else 'OK'}")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
