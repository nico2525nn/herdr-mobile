#!/usr/bin/env bash
# End-to-end smoke for the behaviours that unit tests cannot reach: a real
# TerminalView, a real daemon, a real swipe.
#
# Every check here is one I previously ran by hand with adb + screenshots.
# It creates its own workspace, but that workspace is still created inside the
# daemon it is pointed at. Use tools/ci.sh, which owns a private Herdr session
# and daemon; there is deliberately no live-session default here.
#
#   tools/ci.sh                                   # isolated daemon + smoke
#   BASE_URL=http://127.0.0.1:8799 tools/smoke.sh # a private daemon you own
#   ALLOW_LIVE_DAEMON=1 tools/smoke.sh            # deliberate override (live!)
#
# Requires: adb on PATH, python3 with Pillow, curl, a running herdr daemon.
set -euo pipefail

SERIAL="${SERIAL:-emulator-5554}"
BASE_URL="${BASE_URL:-}"
if [[ -z "$BASE_URL" ]]; then
  cat >&2 <<'EOF'
smoke.sh: BASE_URL is required.
  The old default (http://127.0.0.1:8765) is the developer's live session.
  Use tools/ci.sh for an isolated run, or point BASE_URL at a private daemon.
  Deliberate live override: ALLOW_LIVE_DAEMON=1
EOF
  exit 2
fi
if [[ "${ALLOW_LIVE_DAEMON:-0}" != "1" &&
      "$BASE_URL" =~ ^https?://(127\.0\.0\.1|localhost|\[::1\]):8765(/|$) ]]; then
  cat >&2 <<EOF
smoke.sh: refusing the live daemon at $BASE_URL.
  Use tools/ci.sh (private session + daemon), or set ALLOW_LIVE_DAEMON=1
  if you really mean to create and close a workspace in the live session.
EOF
  exit 2
fi
APK="${APK:-app/build/outputs/apk/debug/app-debug.apk}"
PKG="${PKG:-dev.herdr.mobile.debug}"
ACTIVITY="$PKG/dev.herdr.mobile.MainActivity"
WORKDIR="$(mktemp -d)"
# Unique per run: cleanup sweeps by label, and a fixed name let two runs
# (or a user workspace) close each other's fixture.
LABEL="${SMOKE_LABEL:-smoke-scroll-$$}"
export SMOKE_LABEL="$LABEL"
ARTIFACTS="${ARTIFACTS:-$(pwd)/build/smoke-artifacts}"
mkdir -p "$ARTIFACTS"
WS=""
FAILED=0
PRIVATE_DAEMON_PID="${PRIVATE_DAEMON_PID:-}"
PRIVATE_DAEMON_BIN="${PRIVATE_DAEMON_BIN:-}"
PRIVATE_SOCK="${PRIVATE_SOCK:-}"
PRIVATE_PORT="${PRIVATE_PORT:-}"
PRIVATE_PIDFILE="${PRIVATE_PIDFILE:-}"

# Minimum changed pixels for "the screen moved". Calibrated on the fixture
# below (dense 90-column lines): a full page is ~200k pixels on a 1080x2400
# screen, a cursor blink is <1k. Sparse text changes far fewer pixels, so the
# fixture is dense on purpose.
SCROLL_THRESHOLD="${SCROLL_THRESHOLD:-60000}"
# The host-scroll fixture uses short `seq` lines; a 10-row move repaints far
# fewer pixels than a full `less` page. 5k is still well above a cursor blink.
HOST_SCROLL_THRESHOLD="${HOST_SCROLL_THRESHOLD:-5000}"

say()   { printf '  %s\n' "$*"; }
pass()  { printf 'PASS  %s\n' "$*"; }
fail()  { printf 'FAIL  %s\n' "$*"; FAILED=1; }

adb_() { adb -s "$SERIAL" "$@"; }
api()  { curl -s --max-time 10 "$@"; }

cleanup() {
  if [[ -n "$WS" ]]; then
    api -X POST "$BASE_URL/v1/workspace/$WS/close" >/dev/null 2>&1 || true
  fi
  # A killed run (Ctrl-C, cancelled job) skips the normal path: sweep any
  # leftover fixture workspaces by label so the user's list stays clean.
  for id in $(api "$BASE_URL/v1/snapshot" 2>/dev/null | python3 -c '
import os,sys,json
try:
    d=json.load(sys.stdin)
except Exception:
    raise SystemExit
label=os.environ["SMOKE_LABEL"]
for w in d["workspaces"]:
    if w.get("label") == label:
        print(w["id"])
' 2>/dev/null); do
    api -X POST "$BASE_URL/v1/workspace/$id/close" >/dev/null 2>&1 || true
  done
  cp -f "$WORKDIR"/*.png "$ARTIFACTS"/ 2>/dev/null || true
  say "screenshots: $ARTIFACTS"
  rm -rf "$WORKDIR"
  rm -f /tmp/herdr-smoke-lines.txt
}
trap cleanup EXIT

# ---------------------------------------------------------------- preconditions
say "device $(adb_ get-serialno)"
api "$BASE_URL/v1/health" | grep -q '"ok":true' || { fail "daemon not reachable at $BASE_URL"; exit 1; }

# ------------------------------------------------------------------ scroll check
say "creating an isolated workspace running less on numbered lines"
# The pane runs on the DAEMON host, not on the phone: the fixture must live
# there. (Pushing it to /data/local/tmp was the first version's bug.)
LINES=/tmp/herdr-smoke-lines.txt
# Random-looking line bodies: identical digits made a whole-page scroll
# differ by only the line numbers, so a working page move looked like a
# dead scroll to the pixel-delta check.
awk 'BEGIN { srand(42); for (i = 1; i <= 400; i++) {
               body = "";
               for (j = 0; j < 60; j++) body = body sprintf("%c", 65 + int(rand() * 26));
               printf "LINE-%04d %s\n", i, body } }' > "$LINES"
FOCUS_BEFORE=$(api "$BASE_URL/v1/snapshot" | python3 -c '
import sys,json
print(",".join(sorted(w["id"] for w in json.load(sys.stdin)["workspaces"] if w.get("focused"))))')
say "focused workspace before: ${FOCUS_BEFORE:-none}"
WS=$(api -X POST "$BASE_URL/v1/workspace" -H 'Content-Type: application/json' \
      -d "{\"cwd\":\"/tmp\",\"label\":\"$LABEL\",\"focus\":false}" | python3 -c 'import sys,json;print(json.load(sys.stdin)["workspaceId"])')
sleep 2
api -X POST "$BASE_URL/v1/pane/$WS:p1/input" -H 'Content-Type: application/json' \
    -d "{\"text\":\"less $LINES\\n\"}" >/dev/null
sleep 4

adb_ shell am force-stop "$PKG"
adb_ shell am start -n "$ACTIVITY" >/dev/null
sleep 8

# Bring the card into view and open it. The Home list is a grid; swiping up a
# few times reaches the newest workspace regardless of how many exist.
for _ in $(seq 1 8); do
  adb_ shell rm -f /sdcard/smoke.xml >/dev/null 2>&1; adb_ shell uiautomator dump /sdcard/smoke.xml >/dev/null 2>&1 || true
  if adb_ shell cat /sdcard/smoke.xml 2>/dev/null | grep -q "text=\"$LABEL\""; then break; fi
  adb_ shell input touchscreen swipe 540 1900 540 700 250
  sleep 1
done

BOUNDS=$(adb_ shell cat /sdcard/smoke.xml 2>/dev/null |
  python3 -c '
import re,sys
x=sys.stdin.read()
import os,re as _re
label=os.environ["SMOKE_LABEL"]
m=re.search(r"text=\"" + _re.escape(label) + r"\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", x)
print("" if not m else f"{(int(m.group(1))+int(m.group(3)))//2} {(int(m.group(2))+int(m.group(4)))//2}")' || true)
[[ -n "$BOUNDS" ]] || { fail "workspace card not found on Home"; exit 1; }
adb_ shell input tap $BOUNDS
sleep 9

shot() { adb_ exec-out screencap -p > "$1"; }
changed() {
  python3 - "$1" "$2" <<'PY'
import sys
from PIL import Image, ImageChops
a = Image.open(sys.argv[1]).convert("L")
b = Image.open(sys.argv[2]).convert("L")
hist = ImageChops.difference(a, b).histogram()
print(sum(c for v, c in enumerate(hist) if v > 30))
PY
}

# Compose rails must be visible above the terminal. The AndroidView interop
# handler does not clip child views; an unclipped terminal background painted
# them black while the semantics tree still listed them.
shot "$WORKDIR/rails.png"
if python3 - "$WORKDIR/rails.png" <<'PY'
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert("RGB")
band = [im.getpixel((x, y)) for y in range(70, 260, 6) for x in range(0, im.width, 6)]
common = max(set(band), key=band.count)
ink = sum(1 for p in band if sum(abs(a - b) for a, b in zip(p, common)) > 30)
raise SystemExit(0 if ink > 200 else 1)
PY
then
  pass "tab/workspace rails are visible"
else
  fail "rails are not painted (terminal view clipped?)"
fi

# Tab switching must recreate the terminal view for the new backend. AndroidView
# captures its factory, so a controller swap without a key left the old (closed)
# session on screen. The tab is created without focus so the desktop's focus is
# untouched (the daemon only focuses when the caller asks).
TAB2=$(api -X POST "$BASE_URL/v1/tab" -H 'Content-Type: application/json' \
  -d "{\"workspaceId\":\"$WS\",\"label\":\"two\",\"focus\":false}" 2>/dev/null |
  python3 -c 'import sys,json;print(json.load(sys.stdin).get("tabId",""))' 2>/dev/null)
sleep 2
tab_bounds() {
  adb_ shell rm -f /sdcard/tabs.xml >/dev/null 2>&1; adb_ shell uiautomator dump /sdcard/tabs.xml >/dev/null 2>&1 || true
  adb_ shell cat /sdcard/tabs.xml 2>/dev/null |
    python3 -c '
import re,sys
x=sys.stdin.read()
m=re.search(r"text=\"" + sys.argv[1] + r"\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", x)
print("" if not m else f"{(int(m.group(1))+int(m.group(3)))//2} {(int(m.group(2))+int(m.group(4)))//2}")' "$1"
}
desc_bounds() {
  adb_ shell rm -f /sdcard/desc.xml >/dev/null 2>&1; adb_ shell uiautomator dump /sdcard/desc.xml >/dev/null 2>&1 || true
  adb_ shell cat /sdcard/desc.xml 2>/dev/null |
    python3 -c '
import re,sys
x=sys.stdin.read()
m=re.search(r"content-desc=\"" + re.escape(sys.argv[1]) + r"\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", x)
print("" if not m else f"{(int(m.group(1))+int(m.group(3)))//2} {(int(m.group(2))+int(m.group(4)))//2}")' "$1"
}

pane_scroll() {
  # offset / max / viewport rows for one pane, from the authoritative snapshot.
  api "$BASE_URL/v1/snapshot" | PANE="$1" python3 -c '
import json,os,sys
pane=os.environ["PANE"]
for w in json.load(sys.stdin).get("workspaces", []):
    for t in w.get("tabs", []):
        for p in t.get("panes", []):
            if p.get("id") == pane:
                s = p.get("scroll") or {}
                print(s.get("offsetFromBottom", 0), s.get("maxOffsetFromBottom", 0), s.get("viewportRows", 0))
                raise SystemExit(0)
raise SystemExit(1)'
}

keyboard_tap() {
  local bounds
  bounds="$(desc_bounds "Terminal keyboard")"
  if [[ -z "$bounds" ]]; then
    fail "keyboard button not found in the tab rail"
    return 1
  fi
  adb_ shell input tap $bounds
}

TWO_BOUNDS="$(tab_bounds two)"
if [[ -n "$TWO_BOUNDS" && -n "$TAB2" ]]; then
  # Tab two is a plain shell. Give it enough output for real host scrollback
  # (the CLI row count, not a pixel guess) and open it.
  TAB2_PANE=$(api "$BASE_URL/v1/snapshot" | TAB2="$TAB2" python3 -c '
import json,os,sys
tab=os.environ["TAB2"]
for w in json.load(sys.stdin).get("workspaces", []):
    for t in w.get("tabs", []):
        if t.get("id") == tab:
            for p in t.get("panes", []):
                print(p.get("id", ""))
                raise SystemExit(0)
raise SystemExit(1)' 2>/dev/null || true)
  if [[ -n "$TAB2_PANE" ]]; then
    api -X POST "$BASE_URL/v1/pane/$TAB2_PANE/input" -H 'Content-Type: application/json' \
        -d '{"text":"seq 1 320\n"}' >/dev/null
    HOST_MAX=0
    for _ in $(seq 1 40); do
      read -r HOST_OFF HOST_MAX HOST_VIEW <<< "$(pane_scroll "$TAB2_PANE" || true)"
      (( HOST_MAX > 0 )) && break
      sleep 0.5
    done
    if (( HOST_MAX <= 0 )); then
      fail "tab two never produced host scrollback (max=$HOST_MAX)"
    fi
  else
    fail "second tab pane id not found"
  fi

  shot "$WORKDIR/tab-one.png"
  adb_ shell input tap $TWO_BOUNDS
  sleep 3
  shot "$WORKDIR/tab-two.png"

  if [[ -n "${TAB2_PANE:-}" ]] && (( HOST_MAX > 0 )); then
    read -r HOST_BEFORE _ HOST_VIEW <<< "$(pane_scroll "$TAB2_PANE")"
    # Measured direction: finger DOWN moves toward history (offset grows),
    # finger UP returns toward live. Assert row counts, not just pixels.
    adb_ shell input touchscreen swipe 540 1000 540 1400 700
    sleep 3
    shot "$WORKDIR/host-scroll-older.png"
    read -r HOST_AFTER HOST_MAX_AFTER HOST_VIEW_AFTER <<< "$(pane_scroll "$TAB2_PANE")"
    HOST_MOVED=$(( HOST_AFTER - HOST_BEFORE ))
    HOST_PIXELS=$(changed "$WORKDIR/tab-two.png" "$WORKDIR/host-scroll-older.png")
    VIEW=${HOST_VIEW_AFTER:-$HOST_VIEW}
    if (( HOST_MOVED > 0 && HOST_MOVED <= 2 * (VIEW > 0 ? VIEW : 1) && HOST_AFTER <= HOST_MAX_AFTER )); then
      pass "host scroll moved ${HOST_MOVED} rows toward history (bounded, ${HOST_PIXELS} px)"
    else
      fail "host scroll delta ${HOST_MOVED} rows (before=${HOST_BEFORE}, after=${HOST_AFTER}, max=${HOST_MAX_AFTER}, view=${VIEW})"
    fi
    if (( HOST_PIXELS > HOST_SCROLL_THRESHOLD )); then
      pass "host scroll repainted the transcript (${HOST_PIXELS} px)"
    else
      fail "host scroll moved rows but the screen barely changed (${HOST_PIXELS} px)"
    fi

    adb_ shell input touchscreen swipe 540 1400 540 1000 700
    sleep 3
    shot "$WORKDIR/host-scroll-live.png"
    read -r HOST_BACK _ _ <<< "$(pane_scroll "$TAB2_PANE")"
    if (( HOST_BACK < HOST_AFTER )); then
      pass "host scroll returned toward live (${HOST_AFTER} -> ${HOST_BACK})"
    else
      fail "host scroll did not return toward live (${HOST_AFTER} -> ${HOST_BACK})"
    fi
  fi

  ONE_BOUNDS="$(tab_bounds 1)"
  [[ -n "$ONE_BOUNDS" ]] && adb_ shell input tap $ONE_BOUNDS
  sleep 3
  TAB_DELTA=$(changed "$WORKDIR/tab-one.png" "$WORKDIR/tab-two.png")
  if (( TAB_DELTA > 20000 )); then
    pass "tab switch replaces the terminal view ($TAB_DELTA px)"
  else
    fail "tab switch did not change the screen ($TAB_DELTA px)"
  fi
else
  fail "second tab chip not found"
fi

shot "$WORKDIR/scroll-before.png"
adb_ shell input touchscreen swipe 540 1400 540 1000 700
sleep 3
shot "$WORKDIR/scroll-after.png"
DELTA=$(changed "$WORKDIR/scroll-before.png" "$WORKDIR/scroll-after.png")
if (( DELTA > SCROLL_THRESHOLD )); then
  pass "one drag moves the pane (${DELTA} px)"
else
  fail "one drag moved almost nothing (${DELTA} px) — scroll is dead for this pane"
fi

# Repeatability: the same gesture must move a similar amount (the old bug was
# wildly different distances per swipe).
adb_ shell input touchscreen swipe 540 1400 540 1000 700
sleep 3
shot "$WORKDIR/scroll-again.png"
DELTA2=$(changed "$WORKDIR/scroll-after.png" "$WORKDIR/scroll-again.png")
RATIO=$(python3 -c "print(abs($DELTA - $DELTA2) / max(1, max($DELTA, $DELTA2)))")
if (( DELTA2 > SCROLL_THRESHOLD )); then
  pass "the same gesture moves again (${DELTA2} px)"
else
  fail "second identical gesture moved ${DELTA2} px"
fi
python3 -c "import sys; sys.exit(0 if $RATIO < 0.45 else 1)" \
  && pass "identical gestures move a similar distance (spread ${RATIO})" \
  || fail "scroll distance is unstable: $DELTA vs $DELTA2 px"

# ---------------------------------------------------------------- keyboard checks
ime_shown() { adb_ shell dumpsys input_method 2>/dev/null | grep -o 'mInputShown=[a-z]*' | head -1; }

keyboard_tap
sleep 2
[[ "$(ime_shown)" == "mInputShown=true" ]] && pass "keyboard button raises the IME" \
  || fail "keyboard button did not raise the IME ($(ime_shown))"

adb_ shell input keyevent 4     # back closes it
sleep 2
[[ "$(ime_shown)" == "mInputShown=false" ]] && pass "back hides the IME" \
  || fail "back did not hide the IME ($(ime_shown))"

# CJK page: the keyboard button must focus the field (typing lands there) or
# fall back to the shortcut panel with the terminal IME raised. Swipe from the
# middle of a key: the panel must claim horizontal drags over the buttons.
HOME_BOUNDS="$(tab_bounds HOME)"
if [[ -n "$HOME_BOUNDS" ]]; then
  read -r HX HY <<< "$HOME_BOUNDS"
  adb_ shell input swipe "$HX" "$HY" "$((HX - 380))" "$HY" 600
  sleep 1
  adb_ shell rm -f /sdcard/cjk.xml >/dev/null 2>&1; adb_ shell uiautomator dump /sdcard/cjk.xml >/dev/null 2>&1 || true
  adb_ shell cat /sdcard/cjk.xml 2>/dev/null | grep -q 'Type, convert, then send' \
    && pass "a swipe over the keys reaches the CJK field" \
    || fail "CJK field not found after swiping over the keys"
else
  fail "HOME key not found for the panel swipe"
fi
keyboard_tap
for _ in $(seq 1 20); do
  [[ "$(ime_shown)" == "mInputShown=true" ]] && break
  sleep 0.5
done
[[ "$(ime_shown)" == "mInputShown=true" ]] && pass "CJK keyboard button raises the IME" \
  || fail "CJK keyboard button did not raise the IME ($(ime_shown))"
# The field focus lands on the next frame; typing before that can go to the
# terminal on a slow emulator. Wait for the EditText node itself.
cjk_focused() {
  adb_ shell rm -f /sdcard/cjk-focus.xml >/dev/null 2>&1; adb_ shell uiautomator dump /sdcard/cjk-focus.xml >/dev/null 2>&1 || return 1
  adb_ shell cat /sdcard/cjk-focus.xml 2>/dev/null |
    python3 -c '
import re,sys
x=sys.stdin.read()
for node in re.findall(r"<node[^>]*>", x):
    if "android.widget.EditText" in node and "focused=\"true\"" in node:
        sys.exit(0)
sys.exit(1)'
}
for _ in $(seq 1 20); do
  cjk_focused && break
  sleep 0.5
done
if cjk_focused; then
  pass "CJK field holds focus"
  adb_ shell input text "zzz"
  sleep 2
  adb_ shell rm -f /sdcard/cjk-typed.xml >/dev/null 2>&1; adb_ shell uiautomator dump /sdcard/cjk-typed.xml >/dev/null 2>&1 || true
  adb_ shell cat /sdcard/cjk-typed.xml 2>/dev/null | grep -q 'text="zzz"' \
    && pass "CJK keyboard button focuses its field" \
    || fail "typing did not reach the CJK field"
else
  # The documented fallback: an IME that refuses the field switches back to
  # the shortcut panel and raises the terminal IME. The field is gone then.
  adb_ shell rm -f /sdcard/cjk-fallback.xml >/dev/null 2>&1; adb_ shell uiautomator dump /sdcard/cjk-fallback.xml >/dev/null 2>&1 || true
  if adb_ shell cat /sdcard/cjk-fallback.xml 2>/dev/null | grep -qE 'text="ESC"|content-desc="ESC"'; then
    pass "CJK keyboard falls back to the shortcut panel"
  else
    fail "CJK field never took focus and no shortcut panel appeared"
  fi
fi
adb_ shell input keyevent 4
sleep 1
[[ "$(ime_shown)" == "mInputShown=false" ]] && pass "back hides the CJK IME" \
  || fail "back did not hide the CJK IME ($(ime_shown))"
adb_ shell input keyevent 3     # home
sleep 2
adb_ shell am start -n "$ACTIVITY" >/dev/null
sleep 4
[[ "$(ime_shown)" == "mInputShown=false" ]] && pass "return from background leaves the IME hidden" \
  || fail "keyboard came back by itself ($(ime_shown))"

# ------------------------------------------------------------------- no hijack
# The test owns an unfocused workspace; the user's focused pane must not move.
FOCUS_AFTER=$(api "$BASE_URL/v1/snapshot" | python3 -c '
import sys,json
print(",".join(sorted(w["id"] for w in json.load(sys.stdin)["workspaces"] if w.get("focused"))))')
if [[ "$FOCUS_BEFORE" == "$FOCUS_AFTER" ]]; then
  pass "the user's focused workspace was never taken (${FOCUS_AFTER:-none})"
else
  fail "focus moved from ${FOCUS_BEFORE:-none} to ${FOCUS_AFTER:-none}"
fi

# ------------------------------------------------ terminal reconnect regression
# Only the isolated tools/ci.sh run passes a private daemon to kill. Never run
# this against the developer's live daemon.
green_prompt() {
  python3 - "$1" <<'PY'
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert("RGB")
w, h = im.size
n = 0
for y in range(int(h * 0.35), int(h * 0.88), 2):
    for x in range(0, w, 2):
        r, g, b = im.getpixel((x, y))
        if g > r + 20 and g > b + 10:
            n += 1
raise SystemExit(0 if n >= 15 else 1)
PY
}

# The cursor cell is a solid block: a glyph never fills an 8x8 rectangle.
# Accept any bright neutral (theme cursor #C0CAF5 or the white default) rather
# than pinning one theme's exact cursor colour; the shape is the signal.
cursor_block() {
  python3 - "$1" <<'PY'
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert("RGB")
w, h = im.size
for y in range(int(h * 0.55), int(h * 0.92) - 8, 2):
    for x in range(0, w - 8, 1):
        solid = True
        for yy in range(0, 8, 2):
            for xx in range(0, 8, 2):
                r, g, b = im.getpixel((x + xx, y + yy))
                if not (r >= 170 and g >= 180 and b >= 180):
                    solid = False
                    break
            if not solid:
                break
        if solid:
            raise SystemExit(0)
raise SystemExit(1)
PY
}

if [[ -n "$PRIVATE_DAEMON_PID" && -n "$PRIVATE_DAEMON_BIN" &&
      -n "$PRIVATE_SOCK" && -n "$PRIVATE_PORT" && -n "$PRIVATE_PIDFILE" ]]; then
  say "terminal reconnect regression (private daemon only)"
  PANE="$WS:p1"
  # Leave less, fill the viewport, and leave the shell prompt at the bottom.
  api -X POST "$BASE_URL/v1/pane/$PANE/input" -H 'Content-Type: application/json' -d '{"text":"q"}' >/dev/null 2>&1 || true
  sleep 1
  api -X POST "$BASE_URL/v1/pane/$PANE/input" -H 'Content-Type: application/json' -d '{"text":"seq 1 200\n"}' >/dev/null
  sleep 3
  adb_ logcat -c
  shot "$WORKDIR/reconnect-before.png"
  if green_prompt "$WORKDIR/reconnect-before.png"; then
    pass "terminal prompt is visible before the reconnect"
  else
    fail "terminal prompt missing before the reconnect"
  fi

  kill "$PRIVATE_DAEMON_PID" 2>/dev/null || true
  sleep 4
  shot "$WORKDIR/reconnect-frozen.png"
  if green_prompt "$WORKDIR/reconnect-frozen.png"; then
    pass "bottom prompt and cursor survive the reconnect row"
  else
    fail "bottom prompt disappeared behind the reconnect row"
  fi
  if cursor_block "$WORKDIR/reconnect-frozen.png"; then
    pass "cursor cell is painted into the frozen frame"
  else
    fail "frozen frame caught the cursor blink-off phase"
  fi
  adb_ shell rm -f /sdcard/reconnect.xml >/dev/null 2>&1
  adb_ shell uiautomator dump /sdcard/reconnect.xml >/dev/null 2>&1 || true
  if adb_ shell cat /sdcard/reconnect.xml 2>/dev/null | grep -q "Reconnecting"; then
    pass "reconnect row is visible under the tabs"
  else
    fail "reconnect row is missing under the tabs"
  fi
  if adb_ logcat -d -s HerdrGrid 2>/dev/null | grep -q "resize"; then
    fail "terminal grid resized while the reconnect row was visible"
  else
    pass "terminal grid stayed fixed while the reconnect row was visible"
  fi

  # A tab switch during the outage must discard the old pane's frozen frame.
  TWO_BOUNDS="$(tab_bounds two)"
  if [[ -n "$TWO_BOUNDS" ]]; then
    adb_ shell input tap $TWO_BOUNDS
    sleep 2
    shot "$WORKDIR/reconnect-other-tab.png"
    if green_prompt "$WORKDIR/reconnect-other-tab.png"; then
      fail "frozen frame leaked across the tab switch"
    else
      pass "tab switch discards the previous pane's frozen frame"
    fi
    ONE_BOUNDS="$(tab_bounds 1)"
    [[ -n "$ONE_BOUNDS" ]] && adb_ shell input tap $ONE_BOUNDS
    sleep 2
  else
    fail "second tab not found during the reconnect check"
  fi

  "$PRIVATE_DAEMON_BIN" --bind "127.0.0.1:$PRIVATE_PORT" --herdr-socket "$PRIVATE_SOCK" >"$WORKDIR/daemon-restart.log" 2>&1 &
  echo $! > "$PRIVATE_PIDFILE"
  RESTARTED=0
  for _ in $(seq 1 100); do
    if api "$BASE_URL/v1/health" 2>/dev/null | grep -q '"ok":true'; then
      RESTARTED=1
      break
    fi
    sleep 0.2
  done
  if (( RESTARTED )); then
    sleep 5
    shot "$WORKDIR/reconnect-restored.png"
    if green_prompt "$WORKDIR/reconnect-restored.png"; then
      pass "prompt returns after the daemon restart"
    else
      fail "prompt did not return after the daemon restart"
    fi
  else
    fail "private daemon did not restart for the reconnect check"
  fi
else
  say "skipping terminal reconnect regression (run via tools/ci.sh for an isolated daemon)"
fi

# ------------------------------------------------------------------------- verdict
if (( FAILED )); then
  echo "SMOKE FAILED"
  exit 1
fi
echo "SMOKE OK"
