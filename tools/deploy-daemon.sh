#!/usr/bin/env bash
# Build and deploy the daemon that the user service runs.
#
# The service unit points at /home/nico/herdr-mobile-work/daemon/target/release,
# a separate clone, so a plain `cargo build` in this repo changes nothing the
# phone talks to. This script is that missing step: sync, build, restart, verify.
#
#   tools/deploy-daemon.sh              # sync + build release + restart + health
#   NO_RESTART=1 tools/deploy-daemon.sh # build only
#   WORK=/path/to/clone tools/deploy-daemon.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${WORK:-$HOME/herdr-mobile-work}"
SERVICE="${SERVICE:-herdr-mobile-daemon}"
HEALTH="${HEALTH:-http://127.0.0.1:8765/v1/health}"
TOKEN="${HERDR_MOBILE_TOKEN:-}"
if [[ -z "$TOKEN" ]] && command -v systemctl >/dev/null 2>&1; then
  # The unit may carry the bearer token; health rejects unauthenticated calls
  # when it does, which used to look like a broken daemon and roll back.
  TOKEN="$(systemctl --user show "$SERVICE" -p Environment --value 2>/dev/null |
    tr ' ' '\n' | sed -n 's/^HERDR_MOBILE_TOKEN=//p' | tr -d '"' | head -n1 || true)"
fi

die() { printf 'deploy: %s\n' "$*" >&2; exit 1; }

[ -d "$WORK/daemon" ] || die "no daemon checkout at $WORK/daemon (set WORK=...)"
command -v cargo >/dev/null || die "cargo not on PATH"
command -v python3 >/dev/null || die "python3 not on PATH"

BIN="$WORK/daemon/target/release/herdr-mobile-daemon"
BACKUP="$BIN.prev"
# Keep the last known-good binary; a failed build/restart must not leave the
# phone without a daemon.
if [[ -x "$BIN" ]]; then
  cp -f "$BIN" "$BACKUP"
fi

echo "== syncing daemon sources -> $WORK/daemon"
rsync -a --delete "$ROOT/daemon/src/" "$WORK/daemon/src/"
for f in Cargo.toml Cargo.lock; do
  [ -f "$ROOT/daemon/$f" ] && cp "$ROOT/daemon/$f" "$WORK/daemon/$f"
done

echo "== building release (offline)"
( cd "$WORK/daemon" && cargo build --release --offline )

[ -x "$BIN" ] || die "binary missing after build: $BIN"

if [[ "${NO_RESTART:-0}" == "1" ]]; then
  echo "== build only (NO_RESTART=1)"
  exit 0
fi

echo "== restarting $SERVICE"
RESTORING=0
restore_previous() {
  local status=$?
  trap - EXIT INT TERM
  if (( RESTORING )) && [[ -x "$BACKUP" ]]; then
    echo "deploy: health check failed; restoring previous binary" >&2
    cp -f "$BACKUP" "$BIN"
    systemctl --user restart "$SERVICE" >/dev/null 2>&1 || true
  fi
  exit "$status"
}
trap restore_previous EXIT INT TERM
RESTORING=1
systemctl --user restart "$SERVICE"

health_ok() {
  local body args=(-fsS --max-time 2)
  if [[ -n "$TOKEN" ]]; then
    args+=(-H "Authorization: Bearer $TOKEN")
  fi
  body="$(curl "${args[@]}" "$HEALTH" 2>/dev/null)" || return 1
  # Parse the top-level JSON flags, not a substring match: a nested field or
  # an error body mentioning "true" must not pass as a healthy daemon.
  printf '%s' "$body" |
    python3 -c 'import json,sys
d = json.load(sys.stdin)
sys.exit(0 if d.get("ok") is True and d.get("herdrConnected") is True else 1)' \
      2>/dev/null
}

for _ in $(seq 1 20); do
  if health_ok; then
    RESTORING=0
    trap - EXIT INT TERM
    if [[ -n "$TOKEN" ]]; then
      echo "== healthy: $(curl -s --max-time 2 -H "Authorization: Bearer $TOKEN" "$HEALTH" | head -c 120)"
    else
      echo "== healthy: $(curl -s --max-time 2 "$HEALTH" | head -c 120)"
    fi
    exit 0
  fi
  sleep 0.5
done

die "service did not become healthy within 10s; previous binary restored; check: journalctl --user -u $SERVICE -n 50"
