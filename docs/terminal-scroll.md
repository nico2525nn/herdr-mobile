# Terminal scrolling: the contract, and the traps that broke it

Read this before touching `TermuxTerminalHost`, `ScrollRoute`, `RemoteTermuxSession`,
`tools/smoke.sh` or the daemon's scroll handling. Every rule below is measured on
this repo's device, not inferred.

## 1. Three sources of scroll, and how to choose

| Source | When | Mechanism |
|---|---|---|
| **HOST** | herdr reports scrollback for the pane: `scroll.max_offset_from_bottom > 0` | daemon calls `pane.scroll { offset_from_bottom }`; rows travel as `unit="rows"` |
| **APP_INPUT** | herdr reports `max_offset_from_bottom == 0` (the pane is full-screen and owns its viewport), or the host has no scroll info and the pane is alt-screen / mouse-tracking / carries an agent label | the app types the gesture into the pane (wheel reports or page keys) |
| **LOCAL** | plain shell: no host scroll info, no agent label, and the mirror banked rows | Termux scrolls its own transcript |

`ScrollRoute.kt:scrollSource()` is the only place that decides. The tri-state
matters: **"the host says max == 0" is not the same as "the host says nothing"**.
Collapsing them was a real bug (a `less` pane with a little prelude history was
routed local and the gesture moved nothing).

Corollary: **never scroll the local buffer for a pane the host reported.** That
produced the "Pi is full-screen but the whole terminal scrolled" report.

## 2. The agents, audited from their own binaries

All of these enable the alternate screen and SGR mouse reporting, so app input is
the correct lever when there is no host scrollback:

Per-agent wheel stride (rows advanced by one notch), measured on device:
codex 1, opencode 1, pi 2, qwen 3. A wrong stride is exactly "half speed" or
"double speed"; the table is the default and `ScrollLearning` refines it.

| Agent | Evidence (strings in the installed binary) |
|---|---|
| codex | `?1049h`, `?1000h`, `?1002h`, `?1003h`, `?1006h`; config keys `mouse_scroll_speed`, `fullscreen_transcript` |
| opencode | `?1049h`, `?1000h`, `?1002h`, `?1003h`, `?1006h` |
| omp | `?1049h`, `?1000h`, `?1003h`, `?1006h` |
| grok | `?1049h`, `?1000h`, `?1002h`, `?1003h`, `?1006h` |
| pi | `?1049h`, `?1000h`, `?1002h`, `?1003h`, `?1006h`, `?2004h` |

Audited table (`AgentMatrix`): codex, pi, opencode, qwen use **wheel**; omp, grok,
claude use **page keys**. Treat it as the default, not as truth: `Settings >
Terminal > Scroll mode` lets the user override it per setup (Automatic / Mouse
wheel / Page keys / Terminal history). Do not "fix" a report by re-guessing —
ask which mode works, then change the default.

## 3. The wire, exactly

**Wheel** (input channel, `RemoteTermuxSession.scrollWheel`):

```
ESC [ < 64 ; 45 ; 15 M    one notch older  (button 64)
ESC [ < 65 ; 45 ; 15 M    one notch newer  (button 65)
```

- Sent with `backend.send(bytes)` — the **input** channel.
- A whole burst is ONE frame. Sending one frame per notch made opencode redraw
  itself to a standstill; bursts are also coalesced to at most one per 80 ms.
- Notches per gesture: `rows / rowsPerNotch(agent)`. `rowsPerNotch` defaults to
  the agent table (1 for codex/pi/opencode, 3 for qwen) and is refined from
  measurements. Assuming 3 for everyone sent a third of the notches — the
  "half speed" report.
- A gesture that accumulates less than one notch emits one notch on finger lift.

**Page keys** (input channel):

```
ESC [ 5 ~   older        ESC [ 6 ~   newer
```

- `pages = rows / viewportRows` where `viewportRows` is the **host's** viewport
  (`scroll.viewportRows`), not the phone's row count. Herdr attached at 30 rows
  while the phone drew 44; using the phone's count drifted every page.
- Accumulate across the drag and flush on finger lift; a replay has no lift, so
  flush explicitly.

**Do not** use:
- `terminal.scroll { source: page_key }` for panes without host scrollback. It
  does not reach the app: measured 0 changed pixels on codex while the same
  PgUp typed as input moved a full page.
- `terminal.mouse` for wheel. Same result.

## 4. Traps (each one shipped as a regression)

| Trap | Symptom it produced |
|---|---|
| Gating wheel on the mirror's `mouseTracking` | The TUI mirror receives rendered frames, never DECSET, so the flag is always false → wheel was never sent |
| Treating "the screen changed" as scroll success | Live panes repaint (spinners, agent output). The probe concluded "wheel works" on a repaint, or "wheel failed" and silently downgraded to whole pages |
| A stride default of 3 for every app | Notches sent at 1/3 of what the app expects → half speed |
| One websocket frame per notch | opencode became unresponsive |
| Local-scroll fallback after remote attempts | Full-screen panes moved the terminal buffer as well as the app |
| Trusting the agent label alone | Herdr keeps `agent=codex` on a pane that exited to a shell |
| Judging speed by changed pixels | A full repaint inflates the delta. Measure the *text shift* (see §6) |

## 5. Measuring "does it scroll"

Reliable: compare the visible screen before/after and estimate the vertical shift
that best matches the old lines against the new ones (vote over all non-blank
lines; require a meaningful fraction). That is what the host's probe does.

Unreliable: raw pixel deltas (a repaint changes everything), the first line only
(a diagnostics overlay or a spinner may own it), and the agent's status.

## 6. Latency budget (why two coalescing layers were wrong)

A gesture used to pass two windows: the drag coalescer (150 ms) and the wheel
coalescer (80 ms). Worst case: **230 ms before the first byte leaves**, and a
150 ms cadence while the finger moves — that is the "unresponsive" feel.

Current budget:

| Route | Window | Why |
|---|---|---|
| Wheel | leading edge + 16 ms throttle (~60 Hz) | bytes are tiny; the app redraws per report, so one per frame |
| Host offset (`pane.scroll`) | 40 ms | moves lines; no full-screen repaint |
| Page keys | 150 ms + flush on lift | one page is a whole-screen repaint |

Redraws are coalesced to one per 16 ms display frame: herdr sends a frame per
wheel notch, and a drag can deliver several within one frame. The emulator
applies bytes immediately; only the draw waits.

Also: the probe reads the visible screen **once** per gesture (hash of the same
lines it compares), scans at most 80 rows of shift, and never on the UI thread
during motion (it settles 260 ms after the last request).

## 7. Verification

```bash
tools/ci.sh                    # smoke in a private herdr session; proves the live
                               # session's workspaces and focus are untouched
tools/agent-scroll-matrix.sh   # per agent: movement, composer unchanged (no typed
                               # escape bytes), no local drift (top=0)
./gradlew test                 # 105 unit tests, including the scroll policy
./gradlew :feature-terminal:connectedDebugAndroidTest   # 6 UI tests
cd daemon && cargo test --offline                        # 50 tests
```

Matrix notes: the `less` fixture is the strict case (two pages of deterministic
text). A freshly started agent may show a welcome that fits one screen, so "no
movement" there is a warning, not a failure. The `LOCAL-DRIFT` column reads the
debug overlay's `top=`; it must stay 0 for panes the host reported.

## 8. Raw attach: implemented, then discarded (it cannot report current modes)

`herdr terminal attach <terminal_id>` streams raw ANSI, so it looked like the fix
for mode detection. Measured on this machine:

- It is **read-only**: nothing written to its stdin reaches the pane (raw bytes,
  JSON control and a pty-fed stdin were all tried). Input stays on the control
  channel, which is local JSON+base64 and never touches the phone's traffic.
- It requires a **tty**: without a pty the child dies with `Not a tty`. A pty
  wrapper works (`openpty` + `setsid`), and it coexists with the control client.
- **It does not replay current modes.** Attaching mid-session yields only
  disables — `ESC[?1006l ESC[?1016l ESC[?1015l ESC[?1005l ESC[?1003l
  ESC[?1002l ESC[?1000l ESC[?9l` — and no enable sequences arrive afterwards,
  because the app enabled them before we attached and never re-emits them. A
  DECSET parser fed by that stream therefore reports "nothing is on" for a pane
  that is in the alternate screen with mouse reporting — worse than the agent
  table.

A pty watcher, a DECSET parser and a `terminal.modes` record were built and
verified to compile and stream, then reverted for that reason.

Consequence: mode detection cannot be fixed client-side. The ask upstream is
small — either `PaneInfo.modes { alternate_screen, mouse_tracking }`, or have
`terminal attach` replay the current modes once on start. Until then the agent
table plus `Settings > Terminal > Scroll mode` is the contract.

## 9. Related contracts (same hard-won status)

- **Keyboard**: raise the IME only from the keyboard button. Focus, pane switches
  and returning from the background must never show it; switching the floating
  input panel moves the IME target and never dismisses it. Policy lives in
  `ImePolicy.kt`, owner in `TerminalKeyboardController`.
- **Paste**: inside a 2004 bracketed block the payload is literal text — newlines
  stay LF. CR is only for a plain paste. The paste payload skips the normalizing
  text path (`sendPaste`).
- **Reconnect**: the daemon restarting bumps `targetGeneration`; re-open the same
  pane. A dropped attach stream re-attaches with a bounded retry.
- **Status line**: `UiMessage` + one renderer; never build English strings in the
  view-model.

## 10. Re-check against herdr 0.9.5 / herdr-core 0.1.15 (2026-11-04)

Measured again on this machine, because both upstreams move fast:

- The server already tracks every mode internally. `src/pane/terminal.rs`
  exposes (server-side only) `alternate_screen_active()`,
  `mouse_reporting_enabled()`, `bracketed_paste_enabled()`,
  `plain_page_keys_use_host_scrollback()` and `wheel_routing()`
  (MouseReport / AlternateScroll / HostScroll).
- The socket API still does not expose any of it: the bundled schema of the
  installed 0.9.5 (protocol 22) has no modes field on `PaneInfo` and no
  alternate/mouse_tracking/bracketed strings anywhere.
- herdr-core (`hc` 0.1.15) `terminal.get` returns `foreground {name, argv,
  pid}`, `screen: "exact"`, cols/rows — but no modes. The daemon core path
  therefore still hardcodes `alt_screen: false, mouse_tracking: false`
  in `daemon/src/herdrcore/terminal.rs`.
- The official client attach protocol does have server-side scroll routing:
  `apply_terminal_attach_scroll` (src/server/pane_input.rs) lets the server
  decide wheel-vs-host from `wheel_routing()`, so an attached client needs
  neither coordinates nor mode knowledge. Neither daemon transport can use
  it: the CLI attach is read-only (§8) and the core attach is raw PTY data
  plus daemon-local control messages.
- Consequence: the §8 conclusion stands. The smallest upstream ask is now
  on herdr-core (the daemon main path): add `alternate_screen`,
  `mouse_tracking`, `bracketed_paste` to `terminal.get` / attach facts.
  A local fork is technically cheap on the herdr side (the accessors
  exist) but inherits weekly upstream churn.
- Available today without upstream: key `AgentMatrix` / scroll-source
  inference on the core `foreground` process instead of the agent label,
  which kills the "label says codex, pane is back at a shell" trap (§4).
