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
token_from_env_text() {
  printf '%s' "$1" | tr ' ' '\n' |
    sed -n 's/^HERDR_MOBILE_TOKEN=//p' | tr -d "\"'" | head -n1
}

resolve_deploy_token() {
  local token="" exec env envfiles file value
  command -v systemctl >/dev/null 2>&1 || {
    printf '%s' "${HERDR_MOBILE_TOKEN:-}"
    return
  }
  # Match the daemon's own priority exactly: --token wins over everything,
  # then the service environment, then the deploy shell override.
  exec="$(systemctl --user show "$SERVICE" -p ExecStart --value 2>/dev/null || true)"
  token="$(python3 - "$exec" <<'PY' || true
import shlex, sys
raw = sys.argv[1]
start = raw.find("argv[]=")
if start >= 0:
    argv = raw[start + 7:]
    end = argv.find(" ; ")
    if end >= 0:
        argv = argv[:end]
    try:
        args = shlex.split(argv)
    except ValueError:
        args = argv.split()
    token = ""
    i = 0
    while i < len(args):
        if args[i] == "--token" and i + 1 < len(args):
            token = args[i + 1]
            i += 2
            continue
        if args[i].startswith("--token="):
            token = args[i].split("=", 1)[1]
        i += 1
    print(token, end="")
PY
)"
  if [[ -n "$token" ]]; then
    printf '%s' "$token"
    return
  fi
  env="$(systemctl --user show "$SERVICE" -p Environment --value 2>/dev/null || true)"
  token="$(token_from_env_text "$env")"
  # systemd reads EnvironmentFiles after Environment and later files override
  # earlier ones. A present-but-empty assignment clears the value, so check
  # for the key rather than skipping empty values.
  envfiles="$(systemctl --user show "$SERVICE" -p EnvironmentFiles --value 2>/dev/null || true)"
  for file in $envfiles; do
    [[ -r "$file" ]] || continue
    if grep -Eq '^[[:space:]]*(export[[:space:]]+)?HERDR_MOBILE_TOKEN=' "$file"; then
      value="$(sed -n 's/^[[:space:]]*\(export[[:space:]]\{1,\}\)\?HERDR_MOBILE_TOKEN=//p' "$file" |
        tr -d "\"'" | tail -n1)"
      token="$value"
    fi
  done
  if [[ -n "$token" ]]; then
    printf '%s' "$token"
    return
  fi
  printf '%s' "${HERDR_MOBILE_TOKEN:-}"
}

# Read the token from the actually running process. systemctl's ExecStart text
# loses quoting details; /proc argv/environ are authoritative.
running_service_token() {
  local pid
  pid="$(systemctl --user show "$SERVICE" -p MainPID --value 2>/dev/null || true)"
  [[ "$pid" =~ ^[1-9][0-9]*$ ]] || return 0
  python3 - "$pid" <<'PY' 2>/dev/null || true
import sys
pid = sys.argv[1]
token = ""
try:
    args = open(f"/proc/{pid}/cmdline", "rb").read().split(b"\0")
    args = [a.decode("utf-8", "replace") for a in args if a]
    i = 0
    while i < len(args):
        arg = args[i]
        if arg == "--token" and i + 1 < len(args):
            token = args[i + 1]
            i += 2
            continue
        if arg.startswith("--token="):
            token = arg.split("=", 1)[1]
        i += 1
except OSError:
    pass
if not token:
    try:
        for line in open(f"/proc/{pid}/environ", "rb").read().split(b"\0"):
            text = line.decode("utf-8", "replace")
            if text.startswith("HERDR_MOBILE_TOKEN="):
                token = text.split("=", 1)[1]
    except OSError:
        pass
print(token, end="")
PY
}

TOKEN="$(resolve_deploy_token)"

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

health_curl() {
  local token="$1" url="$2"
  if [[ -n "$token" ]]; then
    # Do not put the bearer in curl's argv: /proc/<pid>/cmdline is readable.
    # -H @- avoids curl-config quoting rules for literal quotes/backslashes.
    printf 'Authorization: Bearer %s\n' "$token" |
      curl -fsS --max-time 2 -H @- "$url"
  else
    curl -fsS --max-time 2 "$url"
  fi
}

health_body() {
  health_curl "$TOKEN" "$HEALTH" 2>/dev/null
}

if [[ -z "$TOKEN" ]]; then
  HEALTH_CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "$HEALTH" 2>/dev/null || true)"
  if [[ "$HEALTH_CODE" == "401" ]]; then
    echo "deploy: warning: the running daemon requires a bearer; none resolved before restart; the post-restart health check decides" >&2
  fi
elif ! health_body >/dev/null; then
  # A new unit token legitimately differs from the still-running daemon;
  # warn instead of blocking the restart that applies it.
  echo "deploy: warning: resolved bearer was rejected by the running daemon; the restart will apply the unit's current value" >&2
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
  local body
  body="$(health_body)" || return 1
  # Parse the top-level JSON flags, not a substring match: a nested field or
  # an error body mentioning "true" must not pass as a healthy daemon.
  printf '%s' "$body" |
    python3 -c 'import json,sys
d = json.load(sys.stdin)
sys.exit(0 if d.get("ok") is True and d.get("herdrConnected") is True else 1)' \
      2>/dev/null
}

for _ in $(seq 1 20); do
  RUNNING_TOKEN="$(running_service_token || true)"
  if [[ -n "$RUNNING_TOKEN" ]]; then
    TOKEN="$RUNNING_TOKEN"
  fi
  if health_ok; then
    RESTORING=0
    trap - EXIT INT TERM
    echo "== healthy: $(health_body | head -c 120)"
    exit 0
  fi
  sleep 0.5
done

die "service did not become healthy within 10s; previous binary restored; check: journalctl --user -u $SERVICE -n 50"
