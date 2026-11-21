#!/usr/bin/env python3
"""Regression tests for refresh_engine_guides field and interval preservation."""
from __future__ import annotations

import json
from pathlib import Path
from tempfile import TemporaryDirectory

import refresh_engine_guides
from refresh_engine_guides import rewrite_events


def test_refresh_session_passes_manifest_round_trip() -> None:
    with TemporaryDirectory() as temporary:
        session = Path(temporary)
        (session / "manifest.json").write_text(
            json.dumps({"engine": {"config": {"round_trip": True}}}),
            encoding="utf-8",
        )
        (session / "events.ndjson").write_text(
            json.dumps({"seq": 1, "stream": "guide", "decision": "CONTINUE"}) + "\n",
            encoding="utf-8",
        )
        observed: list[bool] = []

        def capture_invoke(cli, root, *, round_trip=False, overrides=None):
            observed.append(round_trip)
            return [{"seq": 1, "kind": "guide", "decision": "CONTINUE"}]

        original_invoke = refresh_engine_guides.invoke
        refresh_engine_guides.invoke = capture_invoke
        try:
            refresh_engine_guides.refresh_session(session, Path("unused-cli"))
        finally:
            refresh_engine_guides.invoke = original_invoke

        assert observed == [True], f"refresh_session did not forward manifest round_trip=True: {observed}"


def test_refresh_session_rejects_invalid_manifest_round_trip() -> None:
    with TemporaryDirectory() as temporary:
        session = Path(temporary)
        (session / "manifest.json").write_text(
            json.dumps({"engine": {"config": {"round_trip": "true"}}}),
            encoding="utf-8",
        )
        (session / "events.ndjson").write_text("", encoding="utf-8")
        try:
            refresh_engine_guides.refresh_session(session, Path("unused-cli"))
        except ValueError as error:
            assert str(error) == "manifest engine.config.round_trip invalid: expected a boolean"
        else:
            raise AssertionError("invalid manifest round_trip value was silently accepted")


def main() -> int:
    raw = [
        json.dumps({"seq": 0, "t": 1.125, "stream": "loc", "lat": 10.0, "lon": 20.0, "accuracy": 5.0, "provider": "synthetic"}),
        json.dumps({
            "seq": 1, "t": 1.225, "stream": "guide", "src_seq": 0, "decision": "CONTINUE",
            "inputs": {"frame_count": 1}, "reason": {"rule": "stub", "thresholds": {"keep": 1.0}, "details": {"old": "replaced"}},
            "state_hash": "sha256:test", "output_text": "",
        }),
        json.dumps({
            "seq": 2, "t": 1.325, "stream": "guide", "src_seq": 0, "trigger": "slot", "decision": "CONTINUE",
            "inputs": {"frame_count": 1}, "reason": {"rule": "slot", "thresholds": {}, "details": {"prompt": "원문 유지"}},
            "state_hash": "sha256:test", "output_text": "경로 위입니다",
        }),
    ]
    trace = {1: {"seq": 1, "kind": "guide", "decision": "STATUS", "reason_rule": "matching.on-route", "reason_details": {"distanceMeters": "0.5"}}}
    rewritten, changed = rewrite_events(raw, trace)
    assert changed == 1
    events = [json.loads(line) for line in rewritten]
    assert events[0]["t"] == 1.125
    assert events[1]["reason"]["rule"] == "matching.on-route"
    assert events[1]["reason"]["thresholds"] == {"keep": 1.0}
    assert events[1]["reason"]["details"] == {"distanceMeters": "0.5"}
    assert events[2]["trigger"] == "slot"
    assert events[2]["reason"]["details"] == {"prompt": "원문 유지"}
    test_refresh_session_passes_manifest_round_trip()
    test_refresh_session_rejects_invalid_manifest_round_trip()
    print("refresh_engine_guides=PASS subsecond_t_preserved=PASS trigger_fields_preserved=PASS reason_fields_preserved=PASS round_trip_forwarded=PASS invalid_round_trip_rejected=PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
