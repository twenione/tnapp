#!/usr/bin/env sh
set -eu
if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi
exec python3 tools/gradle_stub.py "$@"

