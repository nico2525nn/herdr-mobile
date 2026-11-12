#!/usr/bin/env bash
# End-to-end smoke for the behaviours that unit tests cannot reach: a real
# TerminalView, a real daemon, a real swipe.
#
# Every check here is one I previously ran by hand with adb + screenshots.
# It creates its own workspace and closes it again, so it is safe against a
# live session — it never touches existing panes.
#
#   tools/smoke.sh                       # emulator-5554 + local daemon
#   SERIAL=emulator-5556 tools/smoke.sh  # another device
#
# Requires: adb on PATH, python3 with Pillow, curl, a running herdr daemon.
set -euo pipefail

SERIAL="${SERIAL:-emulator-5554}"
BASE_URL="${BASE_URL:-http://127.0.0.1:8765}"
APK="${APK:-app/build/outputs/apk/debug/app-debug.apk}"
PKG="${PKG:-dev.herdr.mobile.debug}"
ACTIVITY="$PKG/dev.herdr.mobile.MainActivity"
WORKDIR="$(mktemp -d)"
ARTIFACTS="${ARTIFACTS:-$(pwd)/build/smoke-artifacts}"
mkdir -p "$ARTIFACTS"
WS=""
FAILED=0

# Minimum changed pixels for "the screen moved". Calibrated on the fixture
# below (dense 90-column lines): a full page is ~200k pixels on a 1080x2400
# screen, a cursor blink is <1k. Sparse text changes far fewer pixels, so the
# fixture is dense on purpose.
SCROLL_THRESHOLD="${SCROLL_THRESHOLD:-60000}"

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
import sys,json
try:
    d=json.load(sys.stdin)
except Exception:
    raise SystemExit
for w in d["workspaces"]:
    if w.get("label") in ("smoke-scroll",):
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
      -d '{"cwd":"/tmp","label":"smoke-scroll","focus":false}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["workspaceId"])')
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
  adb_ shell uiautomator dump /sdcard/smoke.xml >/dev/null 2>&1 || true
  if adb_ shell cat /sdcard/smoke.xml 2>/dev/null | grep -q 'text="smoke-scroll"'; then break; fi
  adb_ shell input touchscreen swipe 540 1900 540 700 250
  sleep 1
done

BOUNDS=$(adb_ shell cat /sdcard/smoke.xml 2>/dev/null |
  python3 -c '
import re,sys
x=sys.stdin.read()
m=re.search(r"text=\"smoke-scroll\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", x)
print("" if not m else f"{(int(m.group(1))+int(m.group(3)))//2} {(int(m.group(2))+int(m.group(4)))//2}")')
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
  adb_ shell uiautomator dump /sdcard/tabs.xml >/dev/null 2>&1 || true
  adb_ shell cat /sdcard/tabs.xml 2>/dev/null |
    python3 -c '
import re,sys
x=sys.stdin.read()
m=re.search(r"text=\"" + sys.argv[1] + r"\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", x)
print("" if not m else f"{(int(m.group(1))+int(m.group(3)))//2} {(int(m.group(2))+int(m.group(4)))//2}")' "$1"
}
TWO_BOUNDS="$(tab_bounds two)"
if [[ -n "$TWO_BOUNDS" && -n "$TAB2" ]]; then
  shot "$WORKDIR/tab-one.png"
  adb_ shell input tap $TWO_BOUNDS
  sleep 3
  shot "$WORKDIR/tab-two.png"
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

adb_ shell input tap 860 255   # keyboard button in the tab rail
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
  adb_ shell uiautomator dump /sdcard/cjk.xml >/dev/null 2>&1 || true
  adb_ shell cat /sdcard/cjk.xml 2>/dev/null | grep -q 'Type, convert, then send' \
    && pass "a swipe over the keys reaches the CJK field" \
    || fail "CJK field not found after swiping over the keys"
else
  fail "HOME key not found for the panel swipe"
fi
adb_ shell input tap 860 255   # keyboard button
for _ in $(seq 1 20); do
  [[ "$(ime_shown)" == "mInputShown=true" ]] && break
  sleep 0.5
done
[[ "$(ime_shown)" == "mInputShown=true" ]] && pass "CJK keyboard button raises the IME" \
  || fail "CJK keyboard button did not raise the IME ($(ime_shown))"
# The field focus lands on the next frame; typing before that can go to the
# terminal on a slow emulator. Wait for the EditText node itself.
cjk_focused() {
  adb_ shell uiautomator dump /sdcard/cjk-focus.xml >/dev/null 2>&1 || return 1
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
cjk_focused && pass "CJK field holds focus" || fail "CJK field never took focus"
adb_ shell input text "zzz"
sleep 2
adb_ shell uiautomator dump /sdcard/cjk-typed.xml >/dev/null 2>&1 || true
adb_ shell cat /sdcard/cjk-typed.xml 2>/dev/null | grep -q 'text="zzz"' \
  && pass "CJK keyboard button focuses its field" \
  || fail "typing did not reach the CJK field"
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

# ------------------------------------------------------------------------- verdict
if (( FAILED )); then
  echo "SMOKE FAILED"
  exit 1
fi
echo "SMOKE OK"
