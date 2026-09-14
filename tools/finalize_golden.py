#!/usr/bin/env python3
"""Freeze the verified golden manifest with a content hash of the engine."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--engine", type=Path, default=Path("core-guide/src/main/kotlin"))
    parser.add_argument("--manifest", type=Path, default=Path("testdata/sessions/golden/golden_engine/manifest.json"))
    args = parser.parse_args()
    digest = hashlib.sha256()
    for path in sorted(args.engine.rglob("*.kt")):
        digest.update(path.relative_to(args.engine).as_posix().encode("utf-8"))
        digest.update(b"\0")
        digest.update(path.read_bytes())
        digest.update(b"\0")
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    manifest["app"]["code_hash"] = "sha256:" + digest.hexdigest()
    manifest["golden_status"] = "verified-engine-output"
    manifest["golden_verification"] = "phase1-accuracy"
    args.manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("golden_status=verified-engine-output")
    print("code_hash=" + manifest["app"]["code_hash"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
