#!/usr/bin/env python3
"""Unit tests for D-033 positive controls and explicit mutation consumers."""
from __future__ import annotations

from pathlib import Path
import tempfile

import negative_control_engine as d033


def fake_consumer_runner(outcomes: dict[str, int], names: tuple[str, ...]) -> dict[str, int]:
    """Stand in for real consumers and return their configured exit codes."""
    return {name: outcomes[name] for name in names if name in outcomes}


def test_unmutated_positive_control() -> None:
    names = ("core-guide-test", *d033.CONSUMER_NAMES)
    passing = fake_consumer_runner({name: 0 for name in names}, names)
    assert d033.unmutated_consumer_failures(passing) == []

    failing = fake_consumer_runner({**{name: 0 for name in names}, "replay": 1}, names)
    assert d033.unmutated_consumer_failures(failing) == [
        "FAIL: consumer replay fails on the unmutated engine"
    ]

    missing = fake_consumer_runner({name: 0 for name in names if name != "phase1-accuracy"}, names)
    assert d033.unmutated_consumer_failures(missing) == [
        "FAIL: consumer phase1-accuracy was not run on the unmutated engine"
    ]


def test_required_mutation_consumers() -> None:
    assert set(d033.MUTATION_REQUIRED_CONSUMERS) == set(d033.VARIANTS)
    event_and_sunrise_mutations = {
        name for name in d033.VARIANTS
        if name.startswith(("event-", "sunset-", "sunrise-", "reverse-sunset-", "reverse-events-", "reverse-status-"))
    }
    assert all(d033.MUTATION_REQUIRED_CONSUMERS[name] == d033.CORE_TEST for name in event_and_sunrise_mutations)
    for mutation in ("turn-consumption", "turn-direction-gate", "turn-off-route-gate", "turn-offset-gate", "turn-axis-simplified"):
        required = d033.MUTATION_REQUIRED_CONSUMERS[mutation]
        assert "core-guide-test" in required
        assert required & {"replay", "replay-turn-session", "config-sensitivity"}

    for mutation, required in d033.MUTATION_REQUIRED_CONSUMERS.items():
        names = tuple(sorted(required))
        rejected = fake_consumer_runner({name: 1 for name in names}, names)
        assert d033.mutation_consumer_failures(mutation, rejected) == []

    mutation = "sunset-no-start"
    required = d033.MUTATION_REQUIRED_CONSUMERS[mutation]
    names = tuple(sorted(required | {"replay"}))
    consumer_passed = fake_consumer_runner({name: 1 for name in names}, names)
    consumer_passed["core-guide-test"] = 0
    assert d033.mutation_consumer_failures(mutation, consumer_passed) == [
        "sunset-no-start: required consumer core-guide-test unexpectedly passed"
    ]

    non_required_passes = fake_consumer_runner({name: 1 for name in names}, names)
    non_required_passes["replay"] = 0
    assert d033.mutation_consumer_failures(mutation, non_required_passes) == []

    missing = fake_consumer_runner({}, tuple(sorted(required)))
    assert d033.mutation_consumer_failures(mutation, missing) == [
        "sunset-no-start: required consumer core-guide-test was not run"
    ]


def test_installed_cli_selects_host_launcher() -> None:
    with tempfile.TemporaryDirectory(prefix="task042-cli-launcher-") as temporary:
        workspace = Path(temporary)
        bin_dir = workspace / "replay/build/install/replay/bin"
        bin_dir.mkdir(parents=True)
        unix_cli = bin_dir / "replay"
        windows_cli = bin_dir / "replay.bat"
        unix_cli.write_text("#!/bin/sh\n", encoding="utf-8")
        windows_cli.write_text("@echo off\n", encoding="utf-8")
        assert d033.installed_cli(workspace, windows=False) == unix_cli
        assert d033.installed_cli(workspace, windows=True) == windows_cli


def test_unique_mutation_needles() -> None:
    source = Path("tools/negative_control_engine.py").read_text(encoding="utf-8")
    engine_path = Path("core-guide/src/main/kotlin/com/trailnav/core/Engine.kt")
    route_path = Path("core-guide/src/main/kotlin/com/trailnav/core/Route.kt")
    engine = engine_path.read_text(encoding="utf-8")
    route = route_path.read_text(encoding="utf-8")
    assert source and d033.mutation_needle_failures(engine, route) == []

    original = d033.VARIANTS.get("test-duplicate-needle")
    d033.VARIANTS["test-duplicate-needle"] = ("TASK-042-DUPLICATE-NEEDLE", "unused")
    try:
        errors = d033.mutation_needle_failures(engine + "\nTASK-042-DUPLICATE-NEEDLE\nTASK-042-DUPLICATE-NEEDLE\n", route)
        assert errors == ["test-duplicate-needle: mutation needle occurrence count=2, expected=1"]
    finally:
        if original is None:
            del d033.VARIANTS["test-duplicate-needle"]
        else:
            d033.VARIANTS["test-duplicate-needle"] = original


if __name__ == "__main__":
    test_unmutated_positive_control()
    test_required_mutation_consumers()
    test_installed_cli_selects_host_launcher()
    test_unique_mutation_needles()
    print("PASS: D-033 unmutated positive control, required-consumer matrix, and unique mutation needles")
