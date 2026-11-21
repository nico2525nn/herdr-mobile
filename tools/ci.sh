#!/usr/bin/env bash
# Isolated CI entry point for the on-device smoke.
#
# tools/smoke.sh talks to a daemon and drives the debug app. Run as-is, that
# touches the developer's live Herdr session (the default daemon at :8765 and
# the app's saved URL). This wrapper gives the smoke its own world:
#
#   * a throwaway Herdr session (default name `herdr-ci`), never the default one;
#   * a daemon on 127.0.0.1:8799 bound to that session's socket;
#   * the debug app temporarily pointed at that daemon (original transport +
#     URL backed up and restored, including after a killed run);
#   * adb reverse tcp:8766 -> tcp:8799, the debug build's direct-URL port.
#
# It also snapshots the DEFAULT daemon's workspace list + focused workspace
# before and after the smoke and fails if either changed, so isolation is
# proven rather than assumed.
#
#   tools/ci.sh
#   SERIAL=emulator-5556 tools/ci.sh
#   SKIP_BUILD=1 tools/ci.sh          # reuse daemon/target/debug + app APK
#   PRIVATE_PORT=8800 tools/ci.sh     # another private daemon port
#
# Requires: adb, curl, python3, herdr >= 0.9.5, and (unless SKIP_BUILD=1) cargo
# + the Gradle wrapper. With no device attached it starts AVD $AVD (default
# Medium_Phone_API_37.0) itself and stops it again on exit (KEEP_EMULATOR=1 to
# keep it). The IME is provisioned so the keyboard checks work on a fresh boot.
# The daemon host needs
# `herdr` on PATH and a POSIX shell environment for the fixture pane.
#
# Never runs `herdr server stop`/`session delete` without --session <private>.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

SERIAL="${SERIAL:-}"
AVD="${AVD:-Medium_Phone_API_37.0}"

# Resolve the SDK the way Android tooling does: an explicit ANDROID_HOME wins,
# then ANDROID_SDK_ROOT, then the per-user install. CI images export the env
# vars; many developer machines have neither. ADB=<path> still overrides.
ANDROID_SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
if [[ -z "${ADB:-}" ]]; then
  if [[ -x "$ANDROID_SDK/platform-tools/adb" ]]; then
    ADB="$ANDROID_SDK/platform-tools/adb"
  else
    ADB="adb"
  fi
fi
# tools/smoke.sh calls bare `adb`; make sure it finds the same binary.
if [[ "$ADB" == */* ]]; then
  PATH="$(cd "$(dirname "$ADB")" && pwd):$PATH"
  export PATH
fi
HERDR="${HERDR:-herdr}"
PRIVATE_SESSION="${PRIVATE_SESSION:-herdr-ci}"
PRIVATE_PORT="${PRIVATE_PORT:-8799}"
LIVE_URL="${LIVE_URL:-http://127.0.0.1:8765}"
APP_PORT="${APP_PORT:-8766}"
PRIVATE_URL="http://127.0.0.1:${PRIVATE_PORT}"
APP_URL="http://127.0.0.1:${APP_PORT}"

PKG="${PKG:-dev.herdr.mobile.debug}"
ACTIVITY="$PKG/dev.herdr.mobile.MainActivity"
OVERRIDE_ACTIVITY="$PKG/dev.herdr.mobile.DebugDaemonUrlActivity"
APK="${APK:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
DAEMON_BIN="${DAEMON_BIN:-$ROOT/daemon/target/debug/herdr-mobile-daemon}"
LOG_DIR="$ROOT/build/ci"
ARTIFACTS="${ARTIFACTS:-$ROOT/build/smoke-artifacts}"
PIDFILE="$LOG_DIR/daemon.pid"
EXTERNAL_STATE="/sdcard/Android/data/$PKG/files/herdr-ci-state.json"

SMOKE_STATUS=0
ISOLATION_STATUS=0
RESTORE_STATUS=0
TEARDOWN_STATUS=0
LIVE_ISOLATION=0
INSTALLED=0
REVERSE_SET=0
OLD_REVERSE=""
OLD_SHOW_IME=""
SHOW_IME_SAVED=0

mkdir -p "$LOG_DIR" "$ARTIFACTS"

say()  { printf '\n== %s\n' "$*"; }
info() { printf '   %s\n' "$*"; }
die()  { printf '\nci: %s\n' "$*" >&2; exit 1; }
adb_() { "$ADB" -s "$SERIAL" "$@"; }

require() {
  command -v "$1" >/dev/null 2>&1 || die "'$1' is required but not on PATH"
}

session_dir()  { printf '%s/.config/herdr/sessions/%s' "$HOME" "$PRIVATE_SESSION"; }
session_sock() {
  "$HERDR" --session "$PRIVATE_SESSION" status 2>/dev/null |
    awk '/^[[:space:]]*socket:/ { print $2; exit }'
}
# 0 = running, 1 = stopped/absent, 2 = could not ask.
session_state() {
  local out
  out="$("$HERDR" session list 2>/dev/null)" || return 2
  printf '%s' "$out" |
    awk -v name="$PRIVATE_SESSION" '
      $1 == name {
        found = 1
        if ($2 == "running") running = 1
      }
      END {
        if (running) exit 0
        if (found) exit 1
        exit 1
      }'
}

session_running() {
  session_state
}

daemon_json() {
  local url="$1" path="$2"
  curl -fsS --max-time 10 "$url$path"
}

health_ok() {
  local body
  body="$(daemon_json "$PRIVATE_URL" /v1/health 2>/dev/null)" || return 1
  [[ -n "$body" ]] || return 1
  printf '%s' "$body" |
    python3 -c 'import json,sys; d=json.load(sys.stdin); sys.exit(0 if d.get("ok") and d.get("herdrConnected") else 1)' 2>/dev/null
}

# Stable, READ-ONLY signature straight from the live Herdr session. The daemon
# snapshot is cache-backed and does not subscribe to focus changes, so it could
# return the same stale value before and after while the real session moved.
# `herdr api snapshot` neither focuses nor writes: it is safe against the live
# session and always reflects the current workspace/tab/pane focus.
herdr_signature() {
  "$HERDR" api snapshot 2>/dev/null |
    python3 -c '
import json,sys
s = json.load(sys.stdin)["result"]["snapshot"]
print(json.dumps({
    "workspaces": sorted(
        [w.get("workspace_id") or "", w.get("label") or ""]
        for w in s.get("workspaces", [])
    ),
    "focused": [
        s.get("focused_workspace_id"),
        s.get("focused_tab_id"),
        s.get("focused_pane_id"),
    ],
}, sort_keys=True))'
}

# 0 = running, 1 = not running, 2 = could not ask. Isolation verification must
# treat 2 as a failure: "unknown" is not "no live session".
live_session_state() {
  local out
  out="$("$HERDR" session list 2>/dev/null)" || return 2
  printf '%s' "$out" |
    awk '$1 == "default" && $2 == "running" { found = 1 } END { exit !found }'
}

proc_exe() {
  readlink "/proc/$1/exe" 2>/dev/null || printf 'unknown'
}

is_our_daemon() {
  local pid="$1" sock expected_bind args=() arg i bind="" socket=""
  [[ -r "/proc/$pid/cmdline" ]] || return 1
  mapfile -d '' -t args < "/proc/$pid/cmdline" 2>/dev/null || return 1
  ((${#args[@]} > 0)) || return 1
  local expected_exe
  expected_exe="$(readlink -f "$DAEMON_BIN" 2>/dev/null || printf '%s' "$DAEMON_BIN")"
  [[ "$(proc_exe "$pid")" == "$expected_exe" ]] || return 1
  expected_bind="127.0.0.1:${PRIVATE_PORT}"
  sock="$(session_dir)/herdr.sock"
  # Argument-boundary comparison: a substring match killed a process whose
  # --herdr-socket merely started with our path (e.g. herdr.sock.other).
  for ((i = 0; i < ${#args[@]}; i++)); do
    arg="${args[i]}"
    case "$arg" in
      --bind) bind="${args[i + 1]:-}" ;;
      --bind=*) bind="${arg#--bind=}" ;;
      --herdr-socket) socket="${args[i + 1]:-}" ;;
      --herdr-socket=*) socket="${arg#--herdr-socket=}" ;;
    esac
  done
  [[ "$bind" == "$expected_bind" && "$socket" == "$sock" ]]
}

pid_on_private_port() {
  if command -v ss >/dev/null 2>&1; then
    ss -ltnpH "sport = :${PRIVATE_PORT}" 2>/dev/null |
      sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p' | head -n1
  elif command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:"${PRIVATE_PORT}" -sTCP:LISTEN -t 2>/dev/null | head -n1
  fi
}

# A zombie is already dead: it holds no port and cannot be made to exit. The
# parent that started a background daemon here may not reap it until the next
# command, so `kill -0` alone reports a corpse as alive forever.
pid_alive() {
  local pid="$1" state
  state="$(ps -o stat= -p "$pid" 2>/dev/null | tr -d ' ')" || return 1
  [[ -n "$state" && "$state" != Z* ]]
}

private_port_listening() {
  if command -v ss >/dev/null 2>&1; then
    [[ -n "$(ss -ltnH "sport = :${PRIVATE_PORT}" 2>/dev/null)" ]]
  elif command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:"${PRIVATE_PORT}" -sTCP:LISTEN -t >/dev/null 2>&1
  else
    return 1
  fi
}

kill_pid() {
  local pid="$1"
  kill "$pid" 2>/dev/null || true
  for _ in $(seq 1 50); do
    pid_alive "$pid" || return 0
    sleep 0.1
  done
  kill -9 "$pid" 2>/dev/null || true
  for _ in $(seq 1 50); do
    pid_alive "$pid" || return 0
    sleep 0.1
  done
  die "process $pid ignored SIGKILL"
}

# A previous run may have been killed before its trap: clean only our own
# daemon (pid file, then cmdline match on the private port). A foreign listener
# on the port is never killed.
stop_private_daemon() {
  local pid=""
  if [[ -f "$PIDFILE" ]]; then
    pid="$(cat "$PIDFILE" 2>/dev/null || true)"
  fi
  if [[ -n "$pid" ]] && pid_alive "$pid"; then
    if is_our_daemon "$pid"; then
      info "stopping stale private daemon (pid $pid)"
      kill_pid "$pid"
    else
      die "pid $pid from $PIDFILE is not the private daemon: $(proc_exe "$pid")"
    fi
  fi
  rm -f "$PIDFILE"

  pid="$(pid_on_private_port || true)"
  if [[ -z "$pid" ]] && private_port_listening; then
    die "port $PRIVATE_PORT is already listening but no owning pid could be found;
ci: refusing to start a second daemon on it; stop the listener or set PRIVATE_PORT"
  fi
  if [[ -n "$pid" ]]; then
    if is_our_daemon "$pid"; then
      info "stopping stale private daemon on port $PRIVATE_PORT (pid $pid)"
      kill_pid "$pid"
    else
      die "port $PRIVATE_PORT is already held by pid $pid: $(proc_exe "$pid")
ci: refusing to kill an unrelated process; stop it or set PRIVATE_PORT"
    fi
  fi
}

close_private_workspaces() {
  session_running || return 0
  local ids
  ids="$("$HERDR" --session "$PRIVATE_SESSION" workspace list 2>/dev/null |
    python3 -c '
import json,sys
try:
    d = json.load(sys.stdin)
except Exception:
    raise SystemExit
for w in d.get("result", {}).get("workspaces", []):
    print(w.get("workspace_id"))
' 2>/dev/null || true)"
  for id in $ids; do
    "$HERDR" --session "$PRIVATE_SESSION" workspace close "$id" >/dev/null 2>&1 || true
  done
}

stop_private_session() {
  local dir stop_status=0 delete_status=0 state
  dir="$(session_dir)"
  # The EXIT trap runs even when the ownership guard aborted the run. Never
  # close or stop a session that this invocation did not create.
  if [[ ! -e "$dir/.ci-owned" ]]; then
    info "not stopping session '$PRIVATE_SESSION': no CI marker"
    return 0
  fi
  if session_state; then
    close_private_workspaces
    info "stopping private herdr session '$PRIVATE_SESSION'"
    "$HERDR" --session "$PRIVATE_SESSION" server stop >/dev/null 2>&1 ||
      stop_status=$?
    for _ in $(seq 1 100); do
      if ! session_state; then break; fi
      sleep 0.1
    done
  else
    state=$?
    if (( state == 2 )); then
      printf "ci: cannot verify whether session '%s' stopped; keeping its CI marker\n" \
        "$PRIVATE_SESSION" >&2
      return 1
    fi
  fi
  if session_state; then
    printf "ci: session '%s' is still responding after stop; keeping its CI marker\n" \
      "$PRIVATE_SESSION" >&2
    return 1
  else
    state=$?
    if (( state == 2 )); then
      printf "ci: cannot verify that session '%s' stopped; keeping its CI marker\n" \
        "$PRIVATE_SESSION" >&2
      return 1
    fi
  fi
  if (( stop_status != 0 )); then
    printf "ci: server stop returned %d for '%s'; keeping its CI marker\n" \
      "$stop_status" "$PRIVATE_SESSION" >&2
    return 1
  fi
  if [[ -d "$dir" ]]; then
    if [[ -e "$dir/.ci-owned" ]]; then
      "$HERDR" session delete "$PRIVATE_SESSION" >/dev/null 2>&1 ||
        delete_status=$?
      if (( delete_status != 0 )); then
        printf "ci: could not delete session '%s'; keeping its CI marker\n" \
          "$PRIVATE_SESSION" >&2
        return 1
      fi
      # Only remove the directory once the session is confirmed stopped and
      # its registry entry deleted.
      if [[ -d "$dir" ]]; then
        rm -rf "$dir"
      fi
    else
      info "warning: $dir has no CI marker; leaving it alone"
    fi
  fi
  return 0
}

read_override_state() {
  local state=""
  state="$(adb_ shell cat "$EXTERNAL_STATE" 2>/dev/null | tr -d '\r' || true)"
  if [[ -z "$state" ]]; then
    # Internal-file fallback. `exec-out` forwards the remote cat's stderr to
    # OUR stdout, so suppress it remotely instead of with a local redirect.
    state="$(adb_ exec-out run-as "$PKG" sh -c 'cat files/herdr-ci-state.json 2>/dev/null' | tr -d '\r' || true)"
  fi
  # Not-yet-written / no such file is not state; the caller keeps polling.
  [[ "$state" == \{* ]] || return 0
  printf '%s' "$state"
}

# Runs DebugDaemonUrlActivity and returns its JSON result on stdout.
override_settings() {
  local action="$1" url="${2:-}" state=""
  # Clear BOTH copies: read_override_state falls back to the app-private file,
  # so a stale result from a previous run would be mistaken for this one.
  adb_ shell rm -f "$EXTERNAL_STATE" >/dev/null 2>&1 || true
  adb_ shell run-as "$PKG" rm -f files/herdr-ci-state.json >/dev/null 2>&1 || true

  if [[ -n "$url" ]]; then
    adb_ shell am start -W -n "$OVERRIDE_ACTIVITY" --es action "$action" --es url "$url" --es backend tui >/dev/null
  else
    adb_ shell am start -W -n "$OVERRIDE_ACTIVITY" --es action "$action" >/dev/null
  fi

  # The activity allows 30s for DataStore's first load; a cold-booted
  # emulator can spend most of that dexopting. Poll past its timeout and
  # keep the activity's own ok:false result as the authoritative failure.
  for _ in $(seq 1 400); do
    state="$(read_override_state)"
    [[ -n "$state" ]] && break
    sleep 0.1
  done
  if [[ -z "$state" ]]; then
    printf 'ci: DebugDaemonUrlActivity wrote no state (%s); is the debug APK installed?\n' "$action" >&2
    adb_ shell ls -l "$EXTERNAL_STATE" 2>&1 | tail -2 >&2 || true
    adb_ shell run-as "$PKG" ls -l files/herdr-ci-state.json 2>&1 | tail -2 >&2 || true
    adb_ logcat -d -s HerdrCi 2>/dev/null | tail -20 >&2 || true
    return 1
  fi
  printf '%s' "$state"
}

verify_override_state() {
  local state="$1" action="$2"
  python3 - "$state" "$action" "$APP_URL" <<'PY'
import json, sys
raw, action, app_url = sys.argv[1], sys.argv[2], sys.argv[3]
try:
    state = json.loads(raw)
except Exception as exc:
    sys.exit(f"unparseable override state: {exc}: {raw!r}")
if not state.get("ok"):
    sys.exit(f"{action} failed inside the app: {state.get('error')}")
if action == "apply":
    if state.get("transportMode") != "direct":
        sys.exit(f"transport mode is {state.get('transportMode')!r}, not direct")
    if state.get("directUrl") != app_url:
        sys.exit(f"direct URL is {state.get('directUrl')!r}, not {app_url!r}")
    if not state.get("backupPresent"):
        sys.exit("settings backup missing after apply (restore would not be possible)")
else:
    if state.get("backupPresent"):
        sys.exit("settings backup still present after restore")
PY
}

restore_app_settings() {
  local state=""
  state="$(override_settings restore)" || return 1
  verify_override_state "$state" restore || return 1
  info "debug app transport/URL restored"
}

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  set +e

  if (( INSTALLED )); then
    if ! restore_app_settings >/dev/null 2>&1; then
      printf '\nci: WARNING could not restore the debug app settings; run\n' >&2
      printf 'ci:   adb -s %s shell am start -W -n %s --es action restore\n' "$SERIAL" "$OVERRIDE_ACTIVITY" >&2
      status=1
    fi
  fi

  if (( REVERSE_SET )); then
    adb_ reverse --remove "tcp:$APP_PORT" >/dev/null 2>&1
    if [[ -n "$OLD_REVERSE" ]]; then
      adb_ reverse "tcp:$APP_PORT" "$OLD_REVERSE" >/dev/null 2>&1
    fi
  fi

  if (( SHOW_IME_SAVED )); then
    if [[ -z "$OLD_SHOW_IME" || "$OLD_SHOW_IME" == "null" ]]; then
      adb_ shell settings delete secure show_ime_with_hard_keyboard >/dev/null 2>&1
    else
      adb_ shell settings put secure show_ime_with_hard_keyboard "$OLD_SHOW_IME" >/dev/null 2>&1
    fi
  fi

  ( stop_private_daemon ) >/dev/null 2>&1 || true
  ( stop_private_session ) >/dev/null 2>&1 || true
  if [[ -n "${STARTED_EMULATOR:-}" && -n "${SERIAL:-}" && "${KEEP_EMULATOR:-0}" != "1" ]]; then
    ( adb_ emu kill >/dev/null 2>&1 || kill "$STARTED_EMULATOR" 2>/dev/null ) || true
  fi
  rm -f /tmp/herdr-smoke-lines.txt
  rm -f "$PIDFILE"

  exit "$status"
}
trap cleanup EXIT INT TERM

# ---------------------------------------------------------------- preconditions
require "$ADB"
require curl
require python3
require "$HERDR"
python3 -c 'import PIL' 2>/dev/null ||
  die "python3 Pillow is required by tools/smoke.sh (pip install pillow)"

# The stale-daemon guard identifies our process by port + binary. If the live
# daemon shares that port, that match would stop the user's real session before
# the isolation baseline is even captured. Refuse before anything can be
# stopped; the private daemon is always loopback-only.
LIVE_ADDR="$(python3 - "$LIVE_URL" <<'PY'
import sys
from urllib.parse import urlparse
u = urlparse(sys.argv[1])
host = u.hostname or ""
port = u.port or (443 if u.scheme == "https" else 80)
print(f"{host}:{port}")
PY
)"
if [[ "$LIVE_ADDR" == "127.0.0.1:$PRIVATE_PORT" ||
      "$LIVE_ADDR" == "localhost:$PRIVATE_PORT" ||
      "$LIVE_ADDR" == "::1:$PRIVATE_PORT" ||
      "$LIVE_ADDR" == "[::1]:$PRIVATE_PORT" ]]; then
  die "LIVE_URL ($LIVE_URL) and the private daemon share 127.0.0.1:$PRIVATE_PORT.
ci: set PRIVATE_PORT to a free port; refusing to run where stopping a stale
ci: private daemon could stop the live daemon."
fi

if [[ -z "$SERIAL" ]]; then
  mapfile -t DEVICES < <("$ADB" devices 2>/dev/null | awk 'NR > 1 && $2 == "device" { print $1 }')
  case "${#DEVICES[@]}" in
    0)
      EMULATOR_BIN="${EMULATOR_BIN:-}"
      if [[ -z "$EMULATOR_BIN" && -x "$ANDROID_SDK/emulator/emulator" ]]; then
        EMULATOR_BIN="$ANDROID_SDK/emulator/emulator"
      fi
      if [[ -z "$EMULATOR_BIN" ]]; then
        EMULATOR_BIN="$(command -v emulator || true)"
      fi
      [[ -n "$EMULATOR_BIN" ]] ||
        die "no device attached and no 'emulator' on PATH or in \$ANDROID_HOME"
      info "no device attached; starting AVD $AVD"
      "$EMULATOR_BIN" -avd "$AVD" -no-window -no-audio -no-boot-anim \
        -no-snapshot-save >/tmp/herdr-ci-emulator.log 2>&1 &
      STARTED_EMULATOR=$!
      ;;

    1) SERIAL="${DEVICES[0]}" ;;
    *)
      die "${#DEVICES[@]} devices attached (${DEVICES[*]}); set SERIAL=<serial> to choose one"
      ;;
  esac
fi

if [[ -n "${STARTED_EMULATOR:-}" ]]; then
  for _ in $(seq 1 240); do
    SERIAL="$("$ADB" devices 2>/dev/null | awk 'NR > 1 && $2 == "device" { print $1; exit }')"
    [[ -n "$SERIAL" ]] && break
    sleep 1
  done
  [[ -n "$SERIAL" ]] || die "emulator did not appear in adb devices (see /tmp/herdr-ci-emulator.log)"
  info "emulator up as $SERIAL"
fi

if ! adb_ get-state >/dev/null 2>&1; then
  die "device '$SERIAL' is not attached or not authorised (adb devices shows it as offline/unauthorized)"
fi

info "device $SERIAL"
for _ in $(seq 1 600); do
  [[ "$(adb_ shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]] && break
  sleep 0.5
done
[[ "$(adb_ shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]] ||
  die "device '$SERIAL' did not finish booting (sys.boot_completed != 1)"

# Screenshots and layout checks are meaningless on a tiny AVD. The old
# HerdrTest profile booted at 320x480@160 (HVGA) while CI used medium_phone
# (1080x2400@420 = 411x914dp); keep the smoke on a realistic phone. The
# smoke's fixed swipe coordinates also assume at least 1080x1920 physical
# pixels, so enforce both pixel and dp sizes on emulators. Physical devices
# only get the log: a Display-size override can legitimately change the dp.
SCREEN_SIZE="$(adb_ shell wm size 2>/dev/null | tr -d '\r' | tail -n 1)" || true
SCREEN_DENSITY="$(adb_ shell wm density 2>/dev/null | tr -d '\r' | tail -n 1)" || true
read -r SCREEN_W SCREEN_H <<<"$(sed -n 's/.*: \([0-9][0-9]*\)x\([0-9][0-9]*\).*/\1 \2/p' <<<"$SCREEN_SIZE")" || true
SCREEN_DPI="$(sed -n 's/.*: \([0-9][0-9]*\).*/\1/p' <<<"$SCREEN_DENSITY")"
IS_EMULATOR="$(adb_ shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r' || true)"
if [[ -n "${SCREEN_W:-}" && -n "${SCREEN_H:-}" && -n "${SCREEN_DPI:-}" &&
      "$SCREEN_W" -gt 0 && "$SCREEN_H" -gt 0 && "$SCREEN_DPI" -gt 0 ]]; then
  DP_W=$((SCREEN_W * 160 / SCREEN_DPI))
  DP_H=$((SCREEN_H * 160 / SCREEN_DPI))
  info "screen ${SCREEN_W}x${SCREEN_H} @ ${SCREEN_DPI}dpi (${DP_W}x${DP_H}dp)"
  if [[ "${SKIP_SCREEN_CHECK:-0}" == "1" ]]; then
    info "realistic-screen check skipped (SKIP_SCREEN_CHECK=1)"
  elif [[ "${IS_EMULATOR:-0}" == "1" ]]; then
    if ((SCREEN_W < 1080 || SCREEN_H < 1920 || DP_W < 360 || DP_H < 640)); then
      die "emulator screen is ${SCREEN_W}x${SCREEN_H} @ ${SCREEN_DPI}dpi (${DP_W}x${DP_H}dp); the phone smoke expects >=1080x1920px / >=360x640dp. Use a medium_phone profile or fix the AVD's hw.lcd.* settings."
    fi
  else
    info "physical device; screen check logged only"
  fi
else
  info "screen size unavailable; skipping the realistic-screen check"
fi

# The keyboard checks need the IME to show while a hardware keyboard is present.
OLD_SHOW_IME="$(adb_ shell settings get secure show_ime_with_hard_keyboard 2>/dev/null | tr -d '\r')"
SHOW_IME_SAVED=1
adb_ shell settings put secure show_ime_with_hard_keyboard 1 >/dev/null 2>&1 || true

if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
  say "building daemon"
  ( cd "$ROOT" && cargo build --manifest-path daemon/Cargo.toml ) || die "daemon build failed"
  say "building debug APK"
  ( cd "$ROOT" && ./gradlew :app:assembleDebug --console=plain ) || die "APK build failed"
fi
[[ -x "$DAEMON_BIN" ]] || die "daemon binary not found: $DAEMON_BIN (build it or set DAEMON_BIN)"
[[ -f "$APK" ]] || die "APK not found: $APK (build it or set APK)"

# ------------------------------------------------------- stale run / ownership
say "cleaning any stale private instance"
stop_private_daemon

SESSION_DIR="$(session_dir)"
if [[ -e "$SESSION_DIR" && ! -e "$SESSION_DIR/.ci-owned" ]]; then
  die "$SESSION_DIR exists but was not created by tools/ci.sh.
ci: refusing to reuse a real Herdr session; set PRIVATE_SESSION=<other-name>"
fi
if [[ ! -e "$SESSION_DIR" ]]; then
  if session_running; then
    die "a Herdr session named '$PRIVATE_SESSION' is already running but is not owned by tools/ci.sh.
ci: refusing to stop it; set PRIVATE_SESSION=<other-name>"
  else
    SESSION_CHECK=$?
    if (( SESSION_CHECK == 2 )); then
      die "cannot determine whether session '$PRIVATE_SESSION' is running; refusing to proceed"
    fi
  fi
fi
stop_private_session

# ------------------------------------------------------------- private herdr
say "starting private herdr session '$PRIVATE_SESSION'"
mkdir -p "$SESSION_DIR"
: > "$SESSION_DIR/.ci-owned"
# The private server inherits THIS shell's environment, not the user's
# interactive one. A CI/tool shell often has TERM=dumb and LESS=FRX in its
# environment; those make `less` skip the alternate screen, so the fixture pane
# behaves differently from a real desktop session (and host-scroll page keys
# become no-ops at the live bottom). Pin a terminal-like environment so the
# fixture runs exactly as it would for a user.
env -u LESS -u LESSOPEN -u LESSCLOSE -u PAGER \
  TERM=xterm-256color COLORTERM=truecolor \
  "$HERDR" --session "$PRIVATE_SESSION" server >"$LOG_DIR/herdr-server.log" 2>&1 &

PRIVATE_SOCK=""
for _ in $(seq 1 120); do
  if session_running; then
    PRIVATE_SOCK="$(session_sock)"
    [[ -n "$PRIVATE_SOCK" && -S "$PRIVATE_SOCK" ]] && break
  fi
  sleep 0.25
done
[[ -n "$PRIVATE_SOCK" && -S "$PRIVATE_SOCK" ]] ||
  die "private herdr session did not come up; see $LOG_DIR/herdr-server.log"
info "private herdr socket: $PRIVATE_SOCK"

EMPTY_COUNT="$("$HERDR" --session "$PRIVATE_SESSION" api snapshot 2>/dev/null |
  python3 -c 'import json,sys; print(len(json.load(sys.stdin)["result"]["snapshot"]["workspaces"]))' 2>/dev/null || echo unknown)"
[[ "$EMPTY_COUNT" == "0" ]] || die "private herdr session is not empty (workspaces: $EMPTY_COUNT)"

# Herdr focuses the first workspace even when it is created with focus=false
# (there is nothing else to focus), which would make smoke.sh's "focus never
# moved" assertion meaningless. Give the private session a focused anchor so
# the fixture is genuinely unfocused, exactly like it is in a user session.
say "creating a focused anchor workspace"
"$HERDR" --session "$PRIVATE_SESSION" workspace create --cwd /tmp --label ci-anchor --focus \
  >"$LOG_DIR/anchor.json" 2>&1 || die "cannot create the anchor workspace; see $LOG_DIR/anchor.json"
FOCUSED_COUNT="$("$HERDR" --session "$PRIVATE_SESSION" api snapshot 2>/dev/null |
  python3 -c 'import json,sys; print(sum(1 for w in json.load(sys.stdin)["result"]["snapshot"]["workspaces"] if w.get("focused")))' 2>/dev/null || echo 0)"
[[ "$FOCUSED_COUNT" == "1" ]] || die "private session has $FOCUSED_COUNT focused workspaces after creating the anchor"

# ------------------------------------------------------------ isolation baseline
say "capturing live session baseline"
LIVE_BEFORE="$(herdr_signature 2>/dev/null)" || LIVE_BEFORE=""
if [[ -z "$LIVE_BEFORE" ]]; then
  if live_session_state; then LIVE_STATE=0; else LIVE_STATE=$?; fi
  case "$LIVE_STATE" in
    0)
      die "the live Herdr session is running but its read-only snapshot failed.
ci: refusing to claim isolation without a fresh baseline."
      ;;
    1)
      LIVE_ISOLATION=0
      info "no live Herdr session; isolation check not applicable"
      ;;
    *)
      die "cannot determine whether a live Herdr session exists.
ci: refusing to treat an unreadable session list as 'no live session'."
      ;;
  esac
else
  LIVE_ISOLATION=1
  info "live workspaces/focus: $LIVE_BEFORE"
fi

# ------------------------------------------------------------- private daemon
say "starting private daemon on $PRIVATE_URL"
"$DAEMON_BIN" --bind "127.0.0.1:${PRIVATE_PORT}" --herdr-socket "$PRIVATE_SOCK" \
  >"$LOG_DIR/daemon.log" 2>&1 &
echo $! > "$PIDFILE"

READY=0
for _ in $(seq 1 300); do
  if health_ok; then READY=1; break; fi
  if ! pid_alive "$(cat "$PIDFILE")"; then break; fi
  sleep 0.1
done
if (( ! READY )); then
  printf '\nci: private daemon failed to become healthy at %s/v1/health\n' "$PRIVATE_URL" >&2
  sed 's/^/ci:   /' "$LOG_DIR/daemon.log" >&2 || true
  die "daemon startup failed"
fi
info "private daemon healthy, herdr connected"
PRIVATE_LABELS="$(daemon_json "$PRIVATE_URL" /v1/snapshot 2>/dev/null |
  python3 -c 'import json,sys; print(",".join(sorted(w.get("label", "") for w in json.load(sys.stdin).get("workspaces", []))))' 2>/dev/null || true)"
[[ "$PRIVATE_LABELS" == "ci-anchor" ]] ||
  die "private daemon is not serving the private session (workspace labels: '${PRIVATE_LABELS:-none}', expected 'ci-anchor')"

# -------------------------------------------------------------- device wiring
say "installing $APK"
# The cached AVD snapshot may carry a build signed by a previous runner's
# debug keystore; `install -r` then fails with a signature mismatch. Only CI
# owns that throwaway device: uninstalling on a developer device would erase
# their profiles and keystore aliases.
if [[ "${CI:-}" == "true" ]]; then
  adb_ uninstall "$PKG" >/dev/null 2>&1 || true
fi
adb_ install -r "$APK" >/dev/null || die "APK install failed"
INSTALLED=1
# A fresh install shows the POST_NOTIFICATIONS dialog above Home, so the smoke
# never finds the workspace card. Pre-grant it (API 33+; older devices ignore).
adb_ shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true

OLD_REVERSE="$(adb_ reverse --list 2>/dev/null | awk -v p="tcp:$APP_PORT" '$2 == p { print $3; exit }')"
adb_ reverse "tcp:$APP_PORT" "tcp:$PRIVATE_PORT" >/dev/null ||
  die "cannot reverse tcp:$APP_PORT -> tcp:$PRIVATE_PORT (device does not support adb reverse?)"
REVERSE_SET=1

say "pointing the debug app at $APP_URL (saved value backed up)"
STATE="$(override_settings apply "$APP_URL")" || die "cannot apply the debug daemon override"
verify_override_state "$STATE" apply || die "debug daemon override did not take effect"
info "app transport=direct url=$APP_URL"

# --------------------------------------------------------------------- smoke
say "running tools/smoke.sh against the private daemon"
set +e
BASE_URL="$PRIVATE_URL" \
SERIAL="$SERIAL" \
APK="$APK" \
PKG="$PKG" \
ARTIFACTS="$ARTIFACTS" \
  "$ROOT/tools/smoke.sh"
SMOKE_STATUS=$?
set -e

# ---------------------------------------------------------------- restore app
set +e
restore_app_settings
RESTORE_STATUS=$?
set -e
if (( RESTORE_STATUS != 0 )); then
  printf 'ci: could not restore the debug app settings; run the restore activity manually\n' >&2
fi

# ------------------------------------------------------- private teardown first
# Compare the live signature only after the private daemon and session are
# fully gone: a teardown bug must not hide behind an earlier reading.
say "tearing down the private instance"
set +e
stop_private_daemon
DAEMON_TEARDOWN=$?
stop_private_session
SESSION_TEARDOWN=$?
set -e
if (( DAEMON_TEARDOWN != 0 || SESSION_TEARDOWN != 0 )); then
  printf '\nCI FAILED: private teardown failed (daemon %d, session %d)\n' \
    "$DAEMON_TEARDOWN" "$SESSION_TEARDOWN" >&2
  TEARDOWN_STATUS=1
fi

# ------------------------------------------------------------- isolation proof
say "verifying the live session was untouched"
if (( TEARDOWN_STATUS != 0 )); then
  printf '\nISOLATION FAILED: private teardown did not complete; refusing to claim the live session was untouched\n' >&2
  ISOLATION_STATUS=1
elif (( LIVE_ISOLATION )); then
  LIVE_AFTER="$(herdr_signature 2>/dev/null)" || LIVE_AFTER=""
  if [[ -z "$LIVE_AFTER" ]]; then
    printf '\nISOLATION FAILED: the live session was readable before the run but not after\n' >&2
    printf '  before: %s\n' "$LIVE_BEFORE" >&2
    ISOLATION_STATUS=1
  elif [[ "$LIVE_BEFORE" == "$LIVE_AFTER" ]]; then
    info "live workspaces/focus unchanged: $LIVE_AFTER"
  else
    printf '\nISOLATION FAILED\n' >&2
    printf '  before: %s\n' "$LIVE_BEFORE" >&2
    printf '  after:  %s\n' "$LIVE_AFTER" >&2
    ISOLATION_STATUS=1
  fi
else
  if live_session_state; then LIVE_STATE=0; else LIVE_STATE=$?; fi
  case "$LIVE_STATE" in
    0)
      printf '\nISOLATION FAILED: a live Herdr session appeared during the run\n' >&2
      ISOLATION_STATUS=1
      ;;
    1)
      info "no live Herdr session; isolation check skipped"
      ;;
    *)
      printf '\nISOLATION FAILED: cannot determine whether a live session exists after the run\n' >&2
      ISOLATION_STATUS=1
      ;;
  esac
fi

# --------------------------------------------------------------------- verdict
if (( SMOKE_STATUS != 0 )); then
  printf '\nCI FAILED: smoke failed (exit %d)\n' "$SMOKE_STATUS" >&2
fi
if (( RESTORE_STATUS != 0 )); then
  printf 'CI FAILED: debug app settings were not restored\n' >&2
fi
if (( ISOLATION_STATUS != 0 )); then
  printf 'CI FAILED: the live Herdr session changed during the run\n' >&2
fi
if (( TEARDOWN_STATUS != 0 )); then
  printf 'CI FAILED: the private instance did not tear down cleanly\n' >&2
fi
if (( SMOKE_STATUS != 0 || RESTORE_STATUS != 0 || ISOLATION_STATUS != 0 ||
      TEARDOWN_STATUS != 0 )); then
  exit 1
fi

printf '\nCI OK: smoke passed against %s; private session torn down; live session unchanged\n' "$PRIVATE_URL"
