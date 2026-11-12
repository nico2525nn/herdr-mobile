#!/usr/bin/env bash
# Fast local gate: JVM unit tests plus the Rust daemon tests.
#
#   tools/check.sh
#
# No emulator, no daemon, no device: this is the loop to run while coding and
# before pushing. The on-device smoke is tools/ci.sh's job. Both suites run even
# when the first one fails so a single run reports every broken side.
#
# Coverage is deliberately absent: it is opt-in (`-Pcoverage`), so keeping it
# out of this gate keeps the JaCoCo agent out of the offline dependency graph.
# CI's kotlin job runs `./gradlew test -Pcoverage` and uploads the XML.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# A fresh checkout does not always carry the executable bit on the wrapper.
[[ -x ./gradlew ]] || chmod +x ./gradlew

PASSED=0
FAILED=0
SKIPPED=0
declare -a RESULTS=()

record() {
  local status="$1" name="$2"
  RESULTS+=("$(printf '%-4s  %s' "$status" "$name")")
}

run() {
  local name="$1"
  shift
  printf '\n== %s\n' "$name"
  if "$@"; then
    record PASS "$name"
    PASSED=$((PASSED + 1))
  else
    record FAIL "$name"
    FAILED=$((FAILED + 1))
  fi
}

gradle_tests() { ./gradlew test :terminal-ghostty:assembleDebug --offline --console=plain; }
cargo_tests()  { ( cd daemon && cargo test --release --offline ); }

run "JVM unit tests + native build" gradle_tests
# Runs after the Gradle build so the native libraries exist.
run "JNI symbols" ./tools/check-jni.sh

if command -v cargo >/dev/null 2>&1; then
  run "daemon tests (cargo test --release --offline)" cargo_tests
else
  record SKIP "daemon tests: cargo is not on PATH"
  SKIPPED=$((SKIPPED + 1))
fi

printf '\n===== check summary =====\n'
printf '%s\n' "${RESULTS[@]}"
if (( FAILED > 0 )); then
  printf '\nCHECK FAILED: %d failed, %d passed, %d skipped\n' \
    "$FAILED" "$PASSED" "$SKIPPED" >&2
  exit 1
fi
printf '\nCHECK OK: %d passed, %d skipped\n' "$PASSED" "$SKIPPED"
