#!/usr/bin/env bash
# Every `external fun` in GhosttyVt.kt must have a JNI symbol in the built
# library for each packaged ABI. A Kotlin declaration without a C++ definition
# only fails at runtime (UnsatisfiedLinkError on the first frame), which unit
# tests cannot see.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/terminal-ghostty/src/main/java/io/github/sagernet/libghostty/GhosttyVt.kt"
LIB_DIR="$ROOT/terminal-ghostty/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib"

[[ -f "$SRC" ]] || { echo "check-jni: missing $SRC" >&2; exit 1; }
command -v nm >/dev/null 2>&1 || { echo "check-jni: 'nm' is required" >&2; exit 1; }
[[ -d "$LIB_DIR" ]] || { echo "check-jni: build :terminal-ghostty:assembleDebug first" >&2; exit 1; }
shopt -s nullglob
mapfile -t LIBS < <(printf '%s\n' "$LIB_DIR"/*/libghostty_android.so)
[[ ${#LIBS[@]} -gt 0 ]] || {
  echo "check-jni: no libghostty_android.so under $LIB_DIR (native build produced nothing)" >&2
  exit 1
}

mapfile -t NAMES < <(grep -oE 'external fun [A-Za-z0-9_]+' "$SRC" | awk '{print $3}' | sort -u)
[[ ${#NAMES[@]} -gt 0 ]] || { echo "check-jni: no external fun found" >&2; exit 1; }

missing=0
for so in "${LIBS[@]}"; do
  abi="$(basename "$(dirname "$so")")"
  symbols="$(nm -D --defined-only "$so" 2>/dev/null || true)"
  for name in "${NAMES[@]}"; do
    if ! grep -q "Java_io_github_sagernet_libghostty_GhosttyVt_$name\$" <<<"$symbols"; then
      echo "check-jni: $abi missing JNI symbol for $name" >&2
      missing=1
    fi
  done
done

if (( missing )); then
  exit 1
fi
echo "JNI symbols OK (${#NAMES[@]} methods, ${#LIBS[@]} ABIs)"
