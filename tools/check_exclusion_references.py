#!/usr/bin/env python3
"""Require an explicit decision or scope reason at scanner exclusions."""
from __future__ import annotations

import argparse
import ast
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path


# SCOPE: These regular expressions define the required annotation syntax, not target allowlists.
EXCLUDE_MARKER_RE = re.compile(r"#\s*EXCLUDE\s*:(.*)$")
SCOPE_MARKER_RE = re.compile(r"#\s*SCOPE\s*:(.*)$")
DECISION_HEADING_RE = re.compile(r"^## D-(\d+)\b", re.MULTILINE)
NAMED_SETTING_RE = re.compile(r"(?i)(exclu|ignor|skip|allow|exempt)")


@dataclass
class Candidate:
    path: str
    line: int
    code: str
    patterns: set[str] = field(default_factory=set)


@dataclass
class Result:
    classification: str | None = None
    decision: str | None = None
    reason: str | None = None
    error: str | None = None


def _python_files(root: Path) -> list[Path]:
    tools = root / "tools"
    found: set[Path] = set()
    for pattern in ("check_*.py", "*scan*.py", "validate_*.py", "fixture_shape_check.py"):
        found.update(tools.glob(pattern))
    return sorted(
        path for path in found
        # SCOPE: Test modules exercise scanner behavior; only production tools are audited.
        if path.is_file() and not path.name.startswith("test_")
    )


def _empty_return(node: ast.Return) -> bool:
    value = node.value
    if value is None:
        return True
    if isinstance(value, ast.Constant) and (value.value is None or value.value is False or value.value == 0):
        return True
    if isinstance(value, (ast.List, ast.Tuple, ast.Set)) and not value.elts:
        return True
    return isinstance(value, ast.Dict) and not value.keys


def _conditional_return(node: ast.Return, parents: dict[ast.AST, ast.AST]) -> bool:
    parent = parents.get(node)
    while parent is not None:
        if isinstance(parent, ast.If):
            return True
        if isinstance(parent, (ast.FunctionDef, ast.AsyncFunctionDef, ast.Lambda)):
            # SCOPE: A return outside an if in this function is not a conditional exclusion candidate.
            return False
        parent = parents.get(parent)
    return False


def _assigned_names(node: ast.AST) -> list[str]:
    targets: list[ast.AST] = []
    if isinstance(node, ast.Assign):
        targets.extend(node.targets)
    elif isinstance(node, (ast.AnnAssign, ast.AugAssign)):
        targets.append(node.target)
    names: list[str] = []
    for target in targets:
        names.extend(child.id for child in ast.walk(target) if isinstance(child, ast.Name))
    return names


def _python_candidates(root: Path) -> tuple[list[Candidate], list[str]]:
    rows: dict[tuple[str, int], Candidate] = {}
    errors: list[str] = []

    def add(path: Path, line: int, text: list[str], pattern: str) -> None:
        key = (path.relative_to(root).as_posix(), line)
        row = rows.setdefault(key, Candidate(key[0], line, text[line - 1].strip()))
        row.patterns.add(pattern)

    for path in _python_files(root):
        source = path.read_text(encoding="utf-8")
        try:
            tree = ast.parse(source, filename=path.relative_to(root).as_posix())
        except SyntaxError as exc:
            errors.append(f"{path.relative_to(root).as_posix()}:{exc.lineno or 1}: Python parse error")
            # SCOPE: The parse error is recorded because AST candidates cannot be extracted from invalid syntax.
            continue
        lines = source.splitlines()
        parents: dict[ast.AST, ast.AST] = {}
        for parent in ast.walk(tree):
            for child in ast.iter_child_nodes(parent):
                parents[child] = parent

        for node in ast.walk(tree):
            if isinstance(node, ast.Continue):
                add(path, node.lineno, lines, "a")
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute) and node.func.attr in {"startswith", "endswith"}:
                add(path, node.lineno, lines, "c")
            if isinstance(node, ast.Return) and _empty_return(node) and _conditional_return(node, parents):
                add(path, node.lineno, lines, "b")
            if isinstance(node, ast.ExceptHandler):
                for child in ast.walk(node):
                    if isinstance(child, (ast.Continue, ast.Pass)):
                        add(path, child.lineno, lines, "e")

        for node in tree.body:
            if any(NAMED_SETTING_RE.search(name) for name in _assigned_names(node)):
                add(path, node.lineno, lines, "d")

    return sorted(rows.values(), key=lambda item: (item.path, item.line)), errors


def _workflow_candidates(root: Path) -> list[Candidate]:
    rows: list[Candidate] = []
    workflow_dir = root / ".github" / "workflows"
    if not workflow_dir.is_dir():
        return rows
    for path in sorted(workflow_dir.glob("*.yml")):
        lines = path.read_text(encoding="utf-8").splitlines()
        for number, line in enumerate(lines, 1):
            stripped = line.strip()
            # SCOPE: YAML comments and blank lines are not workflow exclusion directives.
            if not stripped or stripped.startswith("#"):
                # SCOPE: Comment and blank lines are skipped after recognizing they are not candidates.
                continue
            patterns: set[str] = set()
            if re.fullmatch(r"continue(?:\s+#.*)?", stripped):
                patterns.add("f")
            if re.match(r"^(?:paths-ignore|branches-ignore)\s*:", stripped, re.IGNORECASE):
                patterns.add("f")
            if re.match(r"^if\s*:", stripped, re.IGNORECASE) and re.search(r"skip", stripped, re.IGNORECASE):
                patterns.add("f")
            if patterns:
                rows.append(Candidate(path.relative_to(root).as_posix(), number, stripped, patterns))
    return rows


def _nearby_comments(lines: list[str], line_number: int) -> list[str]:
    comments: list[str] = []
    current = lines[line_number - 1]
    if "#" in current:
        comments.append(current[current.index("#"):])
    index = line_number - 2
    while index >= 0:
        stripped = lines[index].strip()
        # SCOPE: Only adjacent comments can carry a candidate marker.
        if stripped.startswith("#"):
            comments.append(stripped)
            index -= 1
            # SCOPE: Continue while collecting the adjacent comment block.
            continue
        break
    return comments


def _read_marker(root: Path, candidate: Candidate) -> Result:
    lines = (root / candidate.path).read_text(encoding="utf-8").splitlines()
    comments = _nearby_comments(lines, candidate.line)
    markers: list[tuple[str, str]] = []
    for comment in comments:
        exclude_match = EXCLUDE_MARKER_RE.search(comment)
        scope_match = SCOPE_MARKER_RE.search(comment)
        if exclude_match:
            markers.append(("EXCLUDE", exclude_match.group(1).strip()))
        if scope_match:
            markers.append(("SCOPE", scope_match.group(1).strip()))
    if not markers:
        return Result(error="missing adjacent EXCLUDE or SCOPE marker")
    if len(markers) != 1:
        return Result(error="multiple adjacent markers")

    kind, payload = markers[0]
    if kind == "EXCLUDE":
        match = re.fullmatch(r"D-(\d+)", payload)
        if not match:
            return Result(error="EXCLUDE marker must contain D-nnn")
        return Result(classification=kind, decision=match.group(1))
    if not payload or payload == "<한 줄 사유>":
        return Result(error="SCOPE marker requires a concrete one-line reason")
    return Result(classification=kind, reason=payload)


def check(root: Path) -> tuple[list[Candidate], list[tuple[Candidate, Result]], list[str]]:
    candidates, parse_errors = _python_candidates(root)
    candidates.extend(_workflow_candidates(root))
    candidates.sort(key=lambda item: (item.path, item.line))
    decisions_path = root / "docs" / "DECISIONS.md"
    errors = list(parse_errors)
    if not decisions_path.is_file():
        errors.append("docs/DECISIONS.md:1: decision document is missing")
        decision_numbers: set[str] = set()
    else:
        decision_numbers = set(DECISION_HEADING_RE.findall(decisions_path.read_text(encoding="utf-8")))

    results: list[tuple[Candidate, Result]] = []
    for candidate in candidates:
        result = _read_marker(root, candidate)
        results.append((candidate, result))
        if result.error:
            errors.append(f"{candidate.path}:{candidate.line}: {result.error} [{','.join(sorted(candidate.patterns))}]")
        elif result.classification == "EXCLUDE" and result.decision not in decision_numbers:
            errors.append(f"{candidate.path}:{candidate.line}: D-{result.decision} is not a heading in docs/DECISIONS.md")
    return candidates, results, errors


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args(argv[1:])
    root = args.root.resolve()
    if not root.is_dir():
        print(f"FAIL: repository root is missing: {root}")
        return 1
    try:
        candidates, results, errors = check(root)
    except (OSError, UnicodeError) as exc:
        print(f"FAIL: cannot inspect exclusion candidates: {type(exc).__name__}")
        return 1
    if errors:
        for error in errors:
            print(f"FAIL: {error}")
        print(f"FAIL: {len(errors)} exclusion-reference issue(s) across {len(candidates)} candidate(s)")
        return 1
    exclude_count = sum(result.classification == "EXCLUDE" for _, result in results)
    scope_count = sum(result.classification == "SCOPE" for _, result in results)
    print(f"PASS: candidates {len(candidates)}: EXCLUDE {exclude_count}, SCOPE {scope_count}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
