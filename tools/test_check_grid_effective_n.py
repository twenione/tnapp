#!/usr/bin/env python3
"""Counterexamples for effective grid N validation."""
from __future__ import annotations

import check_grid_effective_n as checker


def test_matching_n_passes() -> None:
    failures, values = checker.check_texts(["START-GRID-N n=250"], 250)
    assert failures == []
    assert values == [250]


def test_mismatched_n_is_rejected() -> None:
    failures, _ = checker.check_texts(["START-GRID-N n=1000"], 250)
    assert any("expected START-GRID-N n=250" in failure for failure in failures)


def test_missing_line_is_rejected() -> None:
    failures, _ = checker.check_texts(["Gradle test completed"], 250)
    assert "START-GRID-N line not found" in failures


def test_mixed_values_are_rejected() -> None:
    failures, _ = checker.check_texts(["START-GRID-N n=250\nSTART-GRID-N n=1000"], 250)
    assert any("mixed START-GRID-N values" in failure for failure in failures)


def test_same_value_repeated_is_accepted() -> None:
    failures, values = checker.check_texts(["START-GRID-N n=250\nSTART-GRID-N n=250"], 250)
    assert failures == []
    assert values == [250, 250]


def test_malformed_line_is_rejected() -> None:
    failures, _ = checker.check_texts(["START-GRID-N n=two"], 250)
    assert any("malformed START-GRID-N" in failure for failure in failures)


def run_all() -> None:
    test_matching_n_passes()
    test_mismatched_n_is_rejected()
    test_missing_line_is_rejected()
    test_mixed_values_are_rejected()
    test_same_value_repeated_is_accepted()
    test_malformed_line_is_rejected()


if __name__ == "__main__":
    run_all()
    print("PASS: matching N accepted; mismatch, missing, mixed, malformed values rejected")
