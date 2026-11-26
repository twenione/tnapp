#!/usr/bin/env python3
"""Positive and negative controls for app Korean string extraction."""
from __future__ import annotations

import json
import tempfile
from pathlib import Path

import check_app_hangul_literals as checker


def _tree(allowlist: dict[str, int] | None = None) -> tuple[tempfile.TemporaryDirectory[str], Path]:
    temporary = tempfile.TemporaryDirectory()
    root = Path(temporary.name)
    (root / "tools").mkdir()
    (root / "app/src/main/kotlin/com/trailnav/app").mkdir(parents=True)
    records = {
        name: {
            "lines": count,
            "reason": "fixture reason",
            "retire": "fixture retire condition",
        }
        for name, count in (allowlist or {}).items()
    }
    (root / "tools/app_hangul_allowlist.json").write_text(
        json.dumps(records, ensure_ascii=False),
        encoding="utf-8",
    )
    return temporary, root


def test_clean_tree_passes() -> None:
    temporary, root = _tree()
    try:
        assert checker.check_tree(root) == []
    finally:
        temporary.cleanup()


def test_new_kotlin_hangul_literal_fails() -> None:
    temporary, root = _tree()
    try:
        source = root / "app/src/main/kotlin/com/trailnav/app/New.kt"
        source.write_text('val label = "새 문구"\n', encoding="utf-8")
        failures = checker.check_tree(root)
        assert any("New.kt:1: 새 한글 문구" in failure for failure in failures), failures
    finally:
        temporary.cleanup()


def test_exact_allowlist_count_passes() -> None:
    name = "app/src/main/kotlin/com/trailnav/app/Retained.kt"
    temporary, root = _tree({name: 2})
    try:
        (root / name).write_text('val first = "허용 하나"\nval second = "허용 둘"\n', encoding="utf-8")
        assert checker.check_tree(root) == []
    finally:
        temporary.cleanup()


def test_excess_allowlist_count_fails_as_new_string() -> None:
    name = "app/src/main/kotlin/com/trailnav/app/Retained.kt"
    temporary, root = _tree({name: 1})
    try:
        (root / name).write_text('val first = "허용"\nval second = "새 문구"\n', encoding="utf-8")
        failures = checker.check_tree(root)
        assert any("Retained.kt:2: 새 한글 문구" in failure for failure in failures), failures
    finally:
        temporary.cleanup()


def test_fewer_allowlist_lines_fails_with_reduction_prompt() -> None:
    name = "app/src/main/kotlin/com/trailnav/app/Retained.kt"
    temporary, root = _tree({name: 2})
    try:
        (root / name).write_text('val first = "하나"\n', encoding="utf-8")
        failures = checker.check_tree(root)
        assert any("목록의 숫자를 낮추세요" in failure for failure in failures), failures
    finally:
        temporary.cleanup()


def test_missing_file_and_zero_count_allowlist_fail() -> None:
    name = "app/src/main/kotlin/com/trailnav/app/Missing.kt"
    temporary, root = _tree({name: 1})
    try:
        failures = checker.check_tree(root)
        assert any("allowlisted file is missing; 목록에서 지우세요" in failure for failure in failures), failures
        source = root / name
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text("val label = \"English only\"\n", encoding="utf-8")
        failures = checker.check_tree(root)
        assert any("allowlisted file has zero Hangul lines; 목록에서 지우세요" in failure for failure in failures), failures
        (root / "tools/app_hangul_allowlist.json").write_text(
            json.dumps({name: {"lines": 0, "reason": "why", "retire": "when"}}), encoding="utf-8"
        )
        failures = checker.check_tree(root)
        assert any("line count must be positive; 목록에서 지우세요" in failure for failure in failures), failures
    finally:
        temporary.cleanup()


def test_comment_only_hangul_is_ignored() -> None:
    temporary, root = _tree()
    try:
        source = root / "app/src/main/kotlin/com/trailnav/app/Comment.kt"
        source.write_text('// 한글 주석\n/* 한글 주석\n * 한글 주석\n<!-- 한글 주석 -->\n', encoding="utf-8")
        assert checker.check_tree(root) == []
    finally:
        temporary.cleanup()


def test_values_and_test_sources_are_ignored() -> None:
    temporary, root = _tree()
    try:
        values = root / "app/src/main/res/values/strings.xml"
        values.parent.mkdir(parents=True)
        values.write_text('<resources><string name="title">허용 리소스</string></resources>', encoding="utf-8")
        tests = root / "app/src/test/kotlin/com/trailnav/app/Test.kt"
        tests.parent.mkdir(parents=True)
        tests.write_text('val expected = "허용 시험 문구"\n', encoding="utf-8")
        assert checker.check_tree(root) == []
    finally:
        temporary.cleanup()


def test_manifest_hangul_literal_fails() -> None:
    temporary, root = _tree()
    try:
        manifest = root / "app/src/main/AndroidManifest.xml"
        manifest.write_text('<manifest android:label="새 이름"/>\n', encoding="utf-8")
        failures = checker.check_tree(root)
        assert any("AndroidManifest.xml:1: 새 한글 문구" in failure for failure in failures), failures
    finally:
        temporary.cleanup()


def test_hangul_jamo_only_literal_fails() -> None:
    temporary, root = _tree()
    try:
        source = root / "app/src/main/kotlin/com/trailnav/app/Jamo.kt"
        source.write_text('val label = "ㄱㅏㄴ"\n', encoding="utf-8")
        failures = checker.check_tree(root)
        assert any("Jamo.kt:1: 새 한글 문구" in failure for failure in failures), failures
    finally:
        temporary.cleanup()


def run_all() -> None:
    test_clean_tree_passes()
    test_new_kotlin_hangul_literal_fails()
    test_exact_allowlist_count_passes()
    test_excess_allowlist_count_fails_as_new_string()
    test_fewer_allowlist_lines_fails_with_reduction_prompt()
    test_missing_file_and_zero_count_allowlist_fail()
    test_comment_only_hangul_is_ignored()
    test_values_and_test_sources_are_ignored()
    test_manifest_hangul_literal_fails()
    test_hangul_jamo_only_literal_fails()


if __name__ == "__main__":
    run_all()
    print("PASS: app Hangul checker accepts allowed inventory and rejects all counterexamples")
