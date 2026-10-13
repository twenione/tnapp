#!/usr/bin/env python3
"""Require and verify Failed-Run trailers in a commit range."""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import urllib.error
import urllib.request
from collections.abc import Callable

TRAILER = re.compile(r"(?im)^Failed-Run:\s*([^\s]+)\s*$")
ALL_TRAILERS = re.compile(r"(?im)^Failed-Run:\s*(\S+)\s*$")
RUN_ID_FORMAT = re.compile(r"\d+")


class RunLookupError(Exception):
    """The GitHub Actions run could not be checked reliably."""


RunLookup = Callable[[str], str | None]


def github_run_lookup(repository: str, token: str) -> RunLookup:
    """Return a lookup function for a repository's Actions runs."""
    def lookup(run_id: str) -> str | None:
        url = f"https://api.github.com/repos/{repository}/actions/runs/{run_id}"
        request = urllib.request.Request(
            url,
            headers={
                "Accept": "application/vnd.github+json",
                "Authorization": f"Bearer {token}",
                "X-GitHub-Api-Version": "2022-11-28",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                if response.status != 200:
                    raise RunLookupError(f"GitHub returned HTTP {response.status}")
                payload = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            if exc.code == 404:
                return None
            raise RunLookupError(f"GitHub returned HTTP {exc.code}") from exc
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, UnicodeDecodeError) as exc:
            raise RunLookupError(str(exc)) from exc
        if not isinstance(payload, dict):
            raise RunLookupError("GitHub returned an invalid run response")
        conclusion = payload.get("conclusion")
        return "" if conclusion is None else str(conclusion)

    return lookup


def check_messages(
    messages: list[tuple[str, str]],
    run_lookup: RunLookup | None = None,
    repository: str = "OWNER/REPO",
) -> int:
    failures: list[str] = []
    undetermined: list[str] = []
    run_results: dict[str, str | None | RunLookupError] = {}

    for commit, message in messages:
        subject = message.splitlines()[0] if message.splitlines() else ""
        trailers = ALL_TRAILERS.findall(message)
        if subject.startswith("fix(build):") and not trailers:
            failures.append(f"{commit or '<message>'}: missing Failed-Run trailer")
        print(f"COMMIT {commit or '<message>'}")
        print(message.rstrip())
        if subject.startswith("fix(build):"):
            print("TRAILER " + ("present" if trailers else "missing"))

        for run_id in trailers:
            if not RUN_ID_FORMAT.fullmatch(run_id):
                failures.append(f"{commit or '<message>'}: invalid Failed-Run value {run_id!r}")
                continue
            if run_lookup is None or run_id in run_results:
                continue
            try:
                run_results[run_id] = run_lookup(run_id)
            except RunLookupError as exc:
                run_results[run_id] = exc

    for run_id, result in run_results.items():
        if isinstance(result, RunLookupError):
            undetermined.append(f"Failed-Run {run_id} could not be verified: {result}")
        elif result is None:
            failures.append(f"Failed-Run {run_id} does not exist in {repository}")
        elif result != "failure":
            failures.append(f"Failed-Run {run_id} has conclusion {result!r}; expected 'failure'")
        else:
            print(f"RUN {run_id} conclusion=failure")

    for failure in failures:
        print(f"FAIL: {failure}")
    for message in undetermined:
        print(f"UNDETERMINED: {message}")
    if failures:
        return 1
    if undetermined:
        return 2
    print("OK: all build-fix commits have valid Failed-Run trailers")
    return 0


def git_messages(base: str | None, head: str | None) -> list[tuple[str, str]]:
    if base and RUN_ID_FORMAT.fullmatch(base) and len(base) == 40 and set(base) == {"0"}:
        refs = ["-1", "HEAD"]
    else:
        refs = [f"{base}..{head}"] if base and head else [head or "HEAD"]
    result = subprocess.run(
        ["git", "log", "--format=%H%x00%B%x00", *refs],
        text=True,
        encoding="utf-8",
        errors="replace",
        capture_output=True,
    )
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
    parser = argparse.ArgumentParser()
    parser.add_argument("base", nargs="?")
    parser.add_argument("head", nargs="?")
    parser.add_argument("--message")
    parser.add_argument("--stdin", action="store_true")
    parser.add_argument("--verify-runs", action="store_true")
    parser.add_argument("--repository", default=None)
    args = parser.parse_args(argv[1:])

    repository = args.repository or "OWNER/REPO"
    run_lookup = None
    if args.verify_runs:
        token = __import__("os").environ.get("GITHUB_TOKEN", "")
        if not token:
            print("UNDETERMINED: GITHUB_TOKEN is not available", file=sys.stderr)
            return 2
        if not args.repository:
            print("--verify-runs requires --repository", file=sys.stderr)
            return 2
        run_lookup = github_run_lookup(repository, token)

    if args.message is not None:
        return check_messages([("", args.message)], run_lookup, repository)
    if args.stdin:
        return check_messages([("", sys.stdin.read())], run_lookup, repository)
    try:
        return check_messages(git_messages(args.base, args.head), run_lookup, repository)
    except RuntimeError:
        return 2


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
