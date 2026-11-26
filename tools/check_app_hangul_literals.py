#!/usr/bin/env python3
"""Enforce the app's reviewed Korean-literal inventory."""
from __future__ import annotations

import argparse
import json
from pathlib import Path


HANGUL_RANGES = ((0xAC00, 0xD7A3), (0x1100, 0x11FF), (0x3130, 0x318F))
COMMENT_PREFIXES = ("//", "/*", "*", "<!--")


def has_hangul(line: str) -> bool:
    return any(start <= ord(char) <= end for char in line for start, end in HANGUL_RANGES)


def is_comment_only(line: str) -> bool:
    # SCOPE: Only the comment prefixes specified by TASK-129 are excluded from literal counts.
    return line.lstrip().startswith(COMMENT_PREFIXES)


def scoped_files(root: Path) -> list[Path]:
    main = root / "app/src/main"
    if not main.is_dir():
        # SCOPE: A missing main source tree has no files in the checker's scope.
        return []
    result: list[Path] = []
    for path in main.rglob("*"):
        if not path.is_file():
            # SCOPE: Directory entries are not source files.
            continue
        relative = path.relative_to(root)
        if path.suffix.lower() in {".kt", ".java"}:
            result.append(relative)
        elif path.name == "AndroidManifest.xml":
            result.append(relative)
        elif path.suffix.lower() == ".xml" and "res" in relative.parts:
            # SCOPE: Values resources are the intended home for UI strings and are excluded by TASK-129.
            resource_parent = next(
                # SCOPE: The values* prefix is the exact resource-directory test from TASK-129.
                (part for part in relative.parts if part == "values" or part.startswith("values-")),
                None,
            )
            if resource_parent is None:
                result.append(relative)
    return sorted(result)


def read_allowlist(path: Path) -> tuple[dict[str, dict[str, object]] | None, list[str]]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        return None, [f"cannot read allowlist {path}: {exc}"]
    if not isinstance(data, dict):
        return None, ["allowlist must be a mapping from repository paths to review records"]
    failures: list[str] = []
    normalized: dict[str, dict[str, object]] = {}
    for name, record in data.items():
        if not isinstance(name, str) or not isinstance(record, dict):
            failures.append(f"invalid allowlist entry: {name!r}={record!r}")
            # SCOPE: Invalid allowlist records cannot be normalized or checked further.
            continue
        count = record.get("lines")
        reason = record.get("reason")
        retire = record.get("retire")
        if not isinstance(count, int) or isinstance(count, bool):
            failures.append(f"invalid allowlist line count: {name}")
        elif count <= 0:
            failures.append(f"{name}: line count must be positive; 목록에서 지우세요")
        if not isinstance(reason, str) or not reason.strip():
            failures.append(f"{name}: missing reason")
        if not isinstance(retire, str) or not retire.strip():
            failures.append(f"{name}: missing retire condition")
        if isinstance(count, int) and not isinstance(count, bool) and count > 0:
            normalized[name.replace("\\", "/")] = record
    return normalized, failures


def check_tree(root: Path, allowlist_path: Path | None = None) -> list[str]:
    root = root.resolve()
    allowlist_file = allowlist_path or root / "tools/app_hangul_allowlist.json"
    if not allowlist_file.is_absolute():
        allowlist_file = root / allowlist_file
    allowed, failures = read_allowlist(allowlist_file)
    if allowed is None:
        return failures
    allowed_names = set(allowed)
    available = {path.as_posix(): path for path in scoped_files(root)}
    for name, expected in allowed.items():
        if name not in available:
            failures.append(f"{name}: allowlisted file is missing; 목록에서 지우세요")

    for name, relative in available.items():
        path = root / relative
        try:
            lines = path.read_text(encoding="utf-8").splitlines()
        except (OSError, UnicodeDecodeError) as exc:
            failures.append(f"cannot read {name}: {exc}")
            # SCOPE: Read errors are reported, and unreadable content cannot be scanned.
            continue
        occurrences = [
            (number, line)
            for number, line in enumerate(lines, start=1)
            if has_hangul(line) and not is_comment_only(line)
        ]
        if name in allowed_names:
            expected = allowed[name]["lines"]
            assert isinstance(expected, int)
            if not occurrences:
                failures.append(f"{name}: allowlisted file has zero Hangul lines; 목록에서 지우세요")
                # SCOPE: A zero-literal entry must be removed instead of granting unused allowance.
                continue
            if len(occurrences) < expected:
                failures.append(
                    f"{name}: allowlist lines={expected}, actual={len(occurrences)}; 목록의 숫자를 낮추세요"
                )
            elif len(occurrences) > expected:
                for number, line in occurrences[expected:]:
                    failures.append(f"{name}:{number}: 새 한글 문구: {line.strip()}")
        else:
            for number, line in occurrences:
                failures.append(f"{name}:{number}: 새 한글 문구: {line.strip()}")
    return failures


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--allowlist", type=Path, default=Path("tools/app_hangul_allowlist.json"))
    args = parser.parse_args()
    allowlist = args.allowlist if args.allowlist.is_absolute() else args.root / args.allowlist
    failures = check_tree(args.root, allowlist)
    if failures:
        print("\n".join(f"FAIL: {item}" for item in failures))
        return 1
    print(f"PASS: app Hangul literals match {allowlist}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
