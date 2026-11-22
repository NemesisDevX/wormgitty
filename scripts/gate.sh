#!/usr/bin/env bash
# Full gate: android unit+lint+instrumented, panel vitest+playwright.
# Exits non-zero on the first failing side.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FAIL=0

echo "== GATE android =="
if (cd "$ROOT/android" && ./gradlew --console=plain test lint \
    connectedWormDebugAndroidTest connectedGhostDebugAndroidTest connectedMasterDebugAndroidTest); then
  echo "GATE android: PASS"
else
  echo "GATE android: FAIL"
  FAIL=1
fi

echo "== GATE panel =="
if (cd "$ROOT/panel" \
    && npm ci \
    && npm test \
    && npx playwright test); then
  echo "GATE panel: PASS"
else
  echo "GATE panel: FAIL"
  FAIL=1
fi

if [ "$FAIL" -ne 0 ]; then
  echo "GATE: FAILURE"
  exit 1
fi
echo "GATE: ALL GREEN"
