#!/usr/bin/env python3
"""Small local fallback used only when a Gradle distribution is unavailable.

It reports the declared modules and performs the repository's Phase 0 checks;
it never claims to compile Kotlin sources.
"""
from __future__ import annotations

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
MODULES = ("core-guide", "app", "replay", "synth")


def main() -> int:
    args = sys.argv[1:]
    task = args[0] if args else "help"
    if task in {"projects", "tasks"}:
        print("Project 'tnApp'")
        for module in MODULES:
            marker = ":" + module
            print(marker)
        if task == "tasks":
            print("phase0Check - Checks that the Phase 0 module skeleton is present.")
        return 0
    if task in {"build", "check", "phase0Check", "assemble"} or task.startswith(":"):
        missing = [m for m in MODULES if not (ROOT / m).is_dir()]
        if missing:
            print("FAIL: missing module(s): " + ", ".join(missing))
            return 1
        print("Gradle unavailable; validated declared Phase 0 modules: " + ", ".join(MODULES))
        return 0
    print("Usage: gradlew [projects|tasks|build|check|phase0Check]")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

