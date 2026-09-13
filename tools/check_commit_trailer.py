#!/usr/bin/env python3
"""Require Failed-Run trailers on build-fix commits in a commit range."""
from __future__ import annotations

import re
import subprocess
import sys

TRAILER = re.compile(r"(?im)^Failed-Run:\s*([^\s]+)\s*$")


def check_messages(messages: list[tuple[str, str]]) -> int:
    failures = []
    for commit, message in messages:
        subject = message.splitlines()[0] if message.splitlines() else ""
        if subject.startswith("fix(build):") and not TRAILER.search(message):
            failures.append((commit, subject))
        print(f"COMMIT {commit or '<message>'}")
        print(message.rstrip())
        if subject.startswith("fix(build):"):
            print("TRAILER " + ("present" if TRAILER.search(message) else "missing"))
    if failures:
        print(f"FAIL: {len(failures)} build-fix commit(s) missing Failed-Run trailer")
        for commit, subject in failures:
            print(f"  {commit}: {subject}")
        return 1
    print("OK: all build-fix commits have Failed-Run trailers")
    return 0


def git_messages(base: str | None, head: str | None) -> list[tuple[str, str]]:
    refs = f"{base}..{head}" if base and head else (head or "HEAD")
    result = subprocess.run(["git", "log", "--format=%H%x00%B%x00", refs], text=True, encoding="utf-8", errors="replace", capture_output=True)
    if result.returncode:
        print(result.stderr.rstrip(), file=sys.stderr)
        raise RuntimeError("git log failed")
    parts = result.stdout.split("\x00")
    messages = []
    for index in range(0, len(parts) - 1, 2):
        if parts[index].strip():
            messages.append((parts[index].strip(), parts[index + 1]))
    return messages


def main(argv: list[str]) -> int:
    if "--message" in argv:
        index = argv.index("--message")
        if index + 1 >= len(argv):
            print("--message requires a value", file=sys.stderr)
            return 2
        return check_messages([("", argv[index + 1])])
    if "--stdin" in argv:
        return check_messages([("", sys.stdin.read())])
    try:
        base, head = (argv[1], argv[2]) if len(argv) >= 3 else (None, None)
        return check_messages(git_messages(base, head))
    except RuntimeError:
        return 2


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))

