#!/usr/bin/env bash
# Verify that a built APK carries the bundled third-party license notices.
#
# The release gate used to check only version/ABI/signature, so an APK could
# ship libghostty-vt without the notices the MIT/Apache/Unicode licenses
# require. Every file under terminal-ghostty/src/main/assets/licenses must be
# present in the APK at assets/licenses/...
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/terminal-ghostty/src/main/assets/licenses"
APK="${1:-}"

if [[ -z "$APK" ]]; then
  echo "usage: tools/check-licenses.sh <apk>" >&2
  exit 2
fi
if [[ ! -f "$APK" ]]; then
  echo "check-licenses: APK not found: $APK" >&2
  exit 2
fi
if [[ ! -d "$SRC" ]]; then
  echo "check-licenses: license asset directory not found: $SRC" >&2
  exit 2
fi

names="$(unzip -Z1 "$APK")"
missing=0
while IFS= read -r rel; do
  entry="assets/licenses/${rel#./}"
  if ! grep -Fxq "$entry" <<<"$names"; then
    echo "MISSING  $entry" >&2
    missing=1
  fi
done < <(cd "$SRC" && find . -type f | sort)

if (( missing )); then
  echo "check-licenses: FAILED for $APK" >&2
  exit 1
fi

count="$(cd "$SRC" && find . -type f | wc -l)"
echo "check-licenses: OK ($count files in $APK)"
