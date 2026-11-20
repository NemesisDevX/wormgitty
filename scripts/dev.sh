#!/usr/bin/env bash
# Dev runner: panel on :5173 (MSW mocks), optional android emulator assist.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

usage() {
  echo "usage: dev.sh panel|android"
  exit 1
}

TARGET="${1:-}"
case "$TARGET" in
  panel)
    cd "$ROOT/panel"
    exec npm run dev
    ;;
  android)
    cd "$ROOT/android"
    exec ./gradlew installDebug
    ;;
  *)
    usage
    ;;
esac
