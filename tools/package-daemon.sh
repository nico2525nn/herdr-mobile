#!/usr/bin/env bash
# Build the Linux x86_64 daemon tarball attached to a GitHub release.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${1:-$ROOT/build/herdr-mobile-daemon-linux-x86_64.tar.gz}"
mkdir -p "$(dirname "$OUT")"
( cd "$ROOT/daemon" && cargo build --release --offline )
tar -C "$ROOT/daemon/target/release" -czf "$OUT" herdr-mobile-daemon
sha256sum "$OUT"
