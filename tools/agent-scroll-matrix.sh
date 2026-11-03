#!/usr/bin/env bash
# Scroll matrix: every supported agent, measured on the device.
#
# The rule this verifies (from the audited terminal behaviour of codex, omp,
# opencode, grok and pi — all of them enable the alternate screen and SGR mouse
# reporting):
#
#   host scrollback > 0  -> host offset (line-exact)
#   full-screen pane     -> wheel notches, page keys as fallback
#   plain shell          -> local transcript
#
# For each agent we open an isolated workspace (never focused), scroll, and
# assert two things from the screenshots:
#   * the transcript region changed (the gesture did something), and
#   * the composer/footer region did NOT change (the gesture did not type
#     escape bytes into the app).
#
#   tools/agent-scroll-matrix.sh            # all agents
#   AGENTS="codex pi" tools/agent-scroll-matrix.sh
set -euo pipefail

SERIAL="${SERIAL:-emulator-5554}"
BASE_URL="${BASE_URL:-http://127.0.0.1:8765}"
PKG="${PKG:-dev.herdr.mobile.debug}"
ACTIVITY="$PKG/dev.herdr.mobile.MainActivity"
AGENTS="${AGENTS:-less codex omp opencode grok pi}"
WORKDIR="$(mktemp -d)"
CREATED=()
FAILED=0

adb_() { adb -s "$SERIAL" "$@"; }
api()  { curl -s --max-time 10 "$@"; }
cleanup() {
  for id in "${CREATED[@]:-}"; do
    [ -n "$id" ] && api -X POST "$BASE_URL/v1/workspace/$id/close" >/dev/null 2>&1 || true
  done
  rm -rf "$WORKDIR"
}
trap cleanup EXIT

shot() { adb_ exec-out screencap -p > "$1"; }

# Changed pixels between two screenshots inside a vertical band (as fractions).
region_delta() {
  python3 - "$1" "$2" "$3" "$4" <<'PY'
import sys
from PIL import Image, ImageChops
a, b, top, bottom = sys.argv[1], sys.argv[2], float(sys.argv[3]), float(sys.argv[4])
ia = Image.open(a).convert("L"); ib = Image.open(b).convert("L")
w, h = ia.size
box = (0, int(h * top), w, int(h * bottom))
ha = ImageChops.difference(ia.crop(box), ib.crop(box)).histogram()
print(sum(c for v, c in enumerate(ha) if v > 30))
PY
}

start_agent() {
  local agent=$1
  local ws
  ws=$(api -X POST "$BASE_URL/v1/workspace" -H 'Content-Type: application/json' \
        -d "{\"cwd\":\"/tmp\",\"label\":\"scroll-$agent\",\"focus\":false}" |
        python3 -c 'import sys,json;print(json.load(sys.stdin)["workspaceId"])')
  CREATED+=("$ws")
  sleep 2
  if [[ "$agent" == "less" ]]; then
    awk 'BEGIN{pad="";for(j=0;j<8;j++)pad=pad "0123456789";for(i=1;i<=400;i++)printf "LINE-%04d %s\n",i,pad}' > /tmp/scroll-matrix-lines.txt
    api -X POST "$BASE_URL/v1/pane/$ws:p1/input" -H 'Content-Type: application/json' \
        -d '{"text":"less /tmp/scroll-matrix-lines.txt\n"}' >/dev/null
  else
    api -X POST "$BASE_URL/v1/pane/$ws:p1/input" -H 'Content-Type: application/json' \
        -d "{\"text\":\"$agent\n\"}" >/dev/null
  fi
  sleep 9
  echo "$ws"
}

open_workspace() {
  local label=$1
  adb_ shell am force-stop "$PKG"
  adb_ shell am start -n "$ACTIVITY" >/dev/null
  sleep 7
  for _ in $(seq 1 8); do
    adb_ shell uiautomator dump /sdcard/scroll.xml >/dev/null 2>&1 || true
    adb_ shell cat /sdcard/scroll.xml 2>/dev/null | grep -q "text=\"$label\"" && break
    adb_ shell input touchscreen swipe 540 1900 540 700 250
    sleep 1
  done
  local tap
  tap=$(adb_ shell cat /sdcard/scroll.xml 2>/dev/null | python3 -c "
import re,sys
x=sys.stdin.read()
m=re.search(r'text=\"$label\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', x)
print('' if not m else f'{(int(m.group(1))+int(m.group(3)))//2} {(int(m.group(2))+int(m.group(4)))//2}')
")
  [ -n "$tap" ] || { echo "  card for $label not found"; return 1; }
  adb_ shell input tap $tap
  sleep 9
}

# Scroll once and classify the result. Prints "moved composer_changed".
scroll_probe() {
  local tag=$1
  shot "$WORKDIR/$tag-0.png"
  # A fresh agent shows its welcome screen at the top; page DOWN (finger up)
  # is the direction that always has room to move.
  adb_ shell input touchscreen swipe 540 1500 540 900 700
  sleep 3
  shot "$WORKDIR/$tag-1.png"
  local moved composer
  moved=$(region_delta "$WORKDIR/$tag-0.png" "$WORKDIR/$tag-1.png" 0.10 0.80)
  composer=$(region_delta "$WORKDIR/$tag-0.png" "$WORKDIR/$tag-1.png" 0.88 0.99)
  echo "$moved $composer"
}

printf '%-10s %-10s %-12s %-12s %s\n' AGENT MOVED COMPOSER-DIFF LOCAL-DRIFT VERDICT
for agent in $AGENTS; do
  if ! command -v "$agent" >/dev/null 2>&1; then
    printf '%-10s %s\n' "$agent" "skipped (not installed)"
    continue
  fi
  label="scroll-$agent"
  ws=$(start_agent "$agent")
  if ! open_workspace "$label"; then
    printf '%-10s %s\n' "$agent" "could not open in the app"
    FAILED=1
    continue
  fi
  total_moved=0
  worst_composer=0
  moved_count=0
  for i in 1 2; do
    read -r moved composer < <(scroll_probe "$agent-$i")
    total_moved=$((total_moved + moved))
    (( composer > worst_composer )) && worst_composer=$composer
    (( moved > 20000 )) && moved_count=$((moved_count + 1))
  done
  # Local drift: read the debug overlay if it is enabled (top= must stay 0 for
  # app-owned panes). Absent overlay -> unknown, not a failure.
  adb_ shell uiautomator dump /sdcard/scroll.xml >/dev/null 2>&1 || true
  top=$(adb_ shell cat /sdcard/scroll.xml 2>/dev/null |
        python3 -c "
import re,sys
m=re.search(r'top=(-?\d+)', sys.stdin.read())
print(m.group(1) if m else '?')
")
  # A fresh agent's welcome may fit one screen, so "no movement" is only a
  # failure for the deterministic fixture (less). Routing and the absence of
  # local drift are checked for every agent.
  verdict="PASS"
  if [[ "$agent" == "less" ]] && (( moved_count == 0 )); then
    verdict="FAIL no movement"; FAILED=1
  fi
  if (( worst_composer > 3000 )); then verdict="FAIL composer changed"; FAILED=1; fi
  if [[ "$top" != "?" && "$top" != "0" ]]; then verdict="FAIL local drift top=$top"; FAILED=1; fi
  printf '%-10s %-10s %-12s %-12s %s\n' "$agent" "$total_moved" "$worst_composer" "$top" "$verdict"
  printf '  workspace %s\n' "$ws"
done

if (( FAILED )); then echo "MATRIX FAILED"; exit 1; fi
echo "MATRIX OK"
echo "screenshots: $WORKDIR (kept until this line, then removed)"
