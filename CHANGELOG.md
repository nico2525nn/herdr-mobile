# Changelog

## 2.0.0 — 2026-11-07

The terminal engine is now **libghostty-vt 1.0**, the same VT engine Herdr runs
server-side. The Termux fork, its reflection layer and the scroll probe are gone.

- Engine: vendored MIT Android view/session library on libghostty-vt 1.0.0
  (arm64-v8a + x86_64 static builds, 16 KB pages), JNI ported to the 1.0 ABI.
  Covers parsing, render state, modes, key encoding (Kitty protocol), paste
  encoding and selection.
- Removed: `terminal-view`/`terminal-emulator` modules, `TermuxInternals`
  reflection, `ScrollProbe`, `ScrollRoute`/`ScrollMath`, `ImePolicy`,
  `WheelBurst`, `TermuxViewport`, box-drawing overlay, AgentMatrix scroll
  probing, paste payload heuristics.
- Scroll routing is explicit from the pane report: host rows (coalesced 40 ms),
  wheel reports for mouse agents, one page per gesture for other full-screen
  panes, local scrollback otherwise; the user's scroll-mode setting still wins.
- Input/paste/selection: engine encoder and formatter; long-press selects a
  word, drag extends, ActionMode copies. Bracketed paste follows the real mode.
- Fixed during the port: 1.0 sized-struct queries (row selection was silently
  empty until initialised with `GHOSTTY_INIT_SIZED`), style flags, grapheme
  buffers, cursor style enum, formatter API.
- Not supported: Kitty inline images (the 1.0 graphics API was redesigned and
  this client does not render them — Termux did not either).
- Verified: engine instrumented proof (bytes → pixels → keys), 11 terminal UI
  tests, full isolated smoke (`CI OK`), typed input over the real keyboard path.
## 1.9.1 — 2026-11-06

- JVM tests for the hardware-key path (`TermuxViewClient`): arrows, DEL, CTRL
  chords, ALT prefixes, printable bytes, and shift+PgUp routing. Robolectric
  runs the real Android `KeyEvent`/`TerminalView` without a device, closing the
  last gap where a key could silently fall through to Termux's dead local PTY.
  196 JVM tests now.

## 1.9.0 — 2026-11-06

Performance + platform-quality pass on top of the 1.8.0 refactor.

- Typing: `inputTick` is collected as a flow inside the terminal surface, so a
  keystroke no longer recomposes the screen. The `AndroidView` update pass now
  applies scheme/font/cursor only when they changed, caches the typeface, and
  binds the view's callbacks once (`rememberUpdatedState`). Side effect fixed:
  `mColors.reset()` ran on every recomposition, wiping OSC 4/10/11 palette
  changes from apps while typing.
- Transport: queued outbound frames are coalesced per turn (keystroke bytes
  concatenate, typed text becomes one `input.text`, control records flush
  first), so key rolls and IME bursts send one socket frame instead of one per
  character. The inbound replay buffer is dropped once the pump has consumed it.
- Pane switching: switching to a different pane releases the old socket in the
  background instead of waiting for its ~150ms close; same-pane reattach stays
  serial because the release restores the pane grid.
- Receive path: no per-chunk `Pair` allocation (direct `mColumns`/`mRows`), and
  the scroll probe hashes only the visible rows instead of the full transcript.
- The touch/gesture state machine moved to `TermuxScrollGestures`; the host is
  now routing and state (681 → 448 lines), with the `bridgeRef` state replaced
  by a typed holder.
- Accessibility: chips announce their label once plus the status as a state
  description (selection included), sticky SHIFT/CTRL/ALT announce their toggle
  state. Icon buttons were already described.
- Tests: 180 → 189 JVM (frame coalescing, parallel release, detail
  sanitisation, outbound merge), plus 18 instrumented (terminal 11, Home 4,
  Settings 3) and opt-in JaCoCo coverage (`-Pcoverage`).
- Baseline profile: a `:baselineprofile` macrobenchmark module generates the
  startup profile consumed by the release build.
- Dependency: Material3 moved from `1.5.0-alpha17` to stable `1.4.0`; the Rust
  daemon parses child records into typed structs instead of a JSON DOM; daemon
  status details are sanitised to one line (control bytes stripped, 160-char
  cap) instead of rendering raw multi-line errors.
- CI: coverage artifact upload, cached AVD boot for the smoke job.

## 1.8.0 — 2026-11-05

No user-facing feature: this release is the quality pass requested after 1.7.0.

- Structure: `TerminalScreen` (763 lines) split into screen/chrome, surface,
  IME-effects and diagnostics; `TermuxTerminalHost` (681) split out `ScrollProbe`
  math, `WheelBurst` coalescing and `TermuxViewport` geometry;
  `PaneSessionController` (517) split out `TerminalAttach`, `TargetResolution`
  and `PendingInput`; `TerminalSocket` control-record parsing extracted into
  testable `TerminalControl`; `SettingsRepositoryImpl` (409) reduced to
  DataStore access on top of a pure `SettingsCodec`.
- Dead code removed: `ScrollMechanism.kt` stub, `nextMechanism`,
  `wheelScrollable`, duplicate `rowsPerNotch` wrapper.
- Test suite: 108 → 180 JVM tests. New coverage for the ready-record
  carry-over, burst merging, daemon error mapping, HTTP request shape, wheel
  coalescing, probe shift estimation, target resolution and fallback notices,
  pending-input bounds, diagnostics rendering, settings codec round-trip,
  Home and Settings view-model behaviour. Shared fakes moved to the `:testing`
  module so Home/Terminal tests use one `FakeHerdrSession`.
- Automation: `tools/check.sh` local gate (JVM + daemon) and
  `.github/workflows/ci.yml` (Kotlin unit, Rust, isolated emulator smoke).
- `HomeViewModel` now depends on the `HerdrSession` contract instead of the
  concrete client, so its failure paths are testable.

## 1.7.0 — 2026-11-04

- Pane switcher: a tab can hold several Herdr panes (splits), but the tab rail
  only ever opened the tab's active pane — splits were reachable from Home and
  nowhere else. The terminal now shows a compact pane row when the tab has more
  than one pane; tapping one re-attaches that pane. Chips are numbered ("1",
  "2 · codex") because split panes often share a title.
- Selected chips (tabs and panes) get a hairline accent ring: on the AMOLED
  theme every container is black, so selection was previously only a slight
  text-brightness difference.
- Verified on device with an isolated split workspace: tapping pane 2 attached
  it (its output appeared), pane 1 kept its own content. Instrumented tests
  cover the rail and its absence for single-pane tabs.

## 1.6.6 — 2026-11-03

- Pi scroll ran at 2x: one SGR notch moves Pi two rows, so the table must ask
  for one notch per two rows. `AgentMatrix` now carries that measured stride
  (codex 1, opencode 1, pi 2, qwen 3).
- Redraws are coalesced to one per 16 ms frame. Herdr sends a frame per notch;
  a drag could deliver several inside one display frame, and each one redrew
  the whole terminal. Bytes are applied immediately, only the draw waits.
- `docs/terminal-scroll.md`: per-agent stride table and the raw-attach finding
  (`herdr terminal attach` streams raw ANSI, which removes base64 in both
  directions and makes DECSET state real).

## 1.6.5 — 2026-11-03

- Scroll latency: a gesture used to pass two coalescing windows (150 ms drag +
  80 ms wheel), so the first byte could leave 230 ms after the finger moved and
  the view updated on a 150 ms cadence. Wheel now sends on the leading edge with
  a 16 ms (~60 Hz) throttle, host offsets on 40 ms, and page keys keep the wide
  window because each one repaints a whole screen.
- Probe cost: one visible-screen read per gesture instead of two, shift scan
  capped at 80 rows, and no screen reads while the finger is moving.
- `docs/terminal-scroll.md` gained a latency-budget section; `AGENTS.md` points
  future work at it before touching scroll code.
- Smoke: same drag moves 387k px twice with 0.07% spread, focus untouched.

## 1.6.4 — 2026-11-03

- Wheel speed: the stride default was wrong. `rowsPerNotch` started at 3 for
  every app, so a drag sent a third of the notches codex/pi/opencode expect —
  the "half speed" report. The default now comes from the audited agent table
  (1 row per notch, 3 for qwen), and measurements refine it.
- OpenCode responsiveness: wheel notches were sent as one websocket frame each,
  so a fast drag produced dozens of frames and the app redrew per frame. A
  burst is now one frame, and bursts are coalesced to at most one per 80 ms.
- New Settings > Terminal > **Scroll mode**: Automatic (audited table), Mouse
  wheel, Page keys, or Terminal history. The protocol has no universal answer —
  a TUI may enable mouse reporting in one setup and not another — so this is
  the escape hatch instead of another round of guessing.
- Full-screen panes still never move the local buffer unless that mode is
  chosen explicitly.

## 1.6.3 — 2026-11-03

Scrolling is smooth again; the previous two releases regressed it.

- Restored the wheel path that worked before the routing rewrite: SGR wheel
  reports are typed on the input channel (`ESC[<64;45;15M`), not sent as
  Herdr's `terminal.mouse` event (which never reached apps without host
  scrollback). Codex now moves a few lines per short drag instead of a page.
- The wheel/page choice is table-driven again (codex, pi, opencode, qwen:
  wheel; omp, grok, claude: page keys) and is never downgraded by a runtime
  probe: the probe mistook a live repaint for "wheel did nothing" and silently
  switched those panes to whole pages.
- A short drag that accumulates less than one notch now emits one notch when
  the finger lifts, so small gestures are never swallowed.
- Full-screen panes still never scroll the local buffer, and host scrollback
  (`max_offset_from_bottom > 0`) still wins with a line-exact offset.
- Evidence: live Codex pane, 150 px drag → about two lines of transcript (the
  pixel delta is a full repaint; the text shift is what was measured); Pi
  welcome → ~1-3 lines per short drag; `less` → one page per gesture, no local
  drift; the device matrix reports no local drift and no composer input for
  codex/omp/opencode/grok/pi.

## 1.6.2 — 2026-11-03

Scrolling rebuilt from measured behaviour instead of guesses.

- What was wrong: Herdr's `terminal.scroll` does not reach an app that has no
  host scrollback (Codex ignored it; the app looked "dead"). SGR wheel also
  does not move Codex/OMP. Meanwhile the mirror's prelude rows made full-screen
  panes look "local", so a drag moved the terminal's own buffer — the "Pi
  scrolled the whole terminal" bug.
- New rule set, decided by the host's own report: scrollback known and > 0 →
  host offset in rows (line-exact); scrollback known and 0 → the pane is
  full-screen, so the app types the page key on the input channel; unknown
  (older daemon) → mirror evidence, then local transcript as before.
- Page keys are sent as input bytes, not through Herdr's scroll control:
  verified on device — Codex moved 239k px and `less` 388k px per gesture,
  where the scroll control moved nothing.
- A full-screen pane never scrolls the local buffer, and there is no local
  fallback when the host reported the pane (that fallback was the terminal
  drift). Instrumented tests still pass; the device matrix asserts "no local
  drift, no composer input" for codex/omp/opencode/grok/pi and real movement for
  the deterministic `less` fixture.

## 1.6.1 — 2026-11-03

- Full-screen agents scroll again. A pane with an agent label is now routed to
  the remote path even when the mirror banked a few rows (Codex/Pi prelude):
  those rows made the pane "local", and a drag moved that tiny buffer while the
  app's own viewport stayed still. Verified on the live Codex pane: 400k/204k
  changed pixels per drag where it previously did nothing.
- Scroll routing follows the agent identity as it arrives. The routing flags
  were written once when the stream was created; if the snapshot learned the
  agent later (Pi), the pane stayed local for its lifetime.
- Wheel is offered before page keys for wheel-known agents, so a gesture moves
  notches (a few lines) instead of a full screen; the runtime probe still falls
  back to page keys when the app ignores wheel, and to local scrolling when
  every remote mechanism is inert (stale agent label on an exited shell).
- Instrumented UI tests and `tools/ci.sh` still green; live session verified
  untouched by the runner.

## 1.6.0 — 2026-11-03

Architecture and test infrastructure; no intended behaviour change.

- `HerdrClient` decomposed: 746 -> 216 lines. Link lifecycle/backoff, link state
  and generations, snapshot sync, transport and REST commands are separate
  types with a fake-transport, virtual-clock test suite (backoff sequence,
  generation bump on target change, `snapshot.required`, seq gaps, stop/scope
  cancellation).
- `SettingsScreen` 720 -> 131 lines and `HomeScreen` 701 -> 274: one file per
  section/list, item keys and order unchanged (settings verified pixel-
  identical before/after on device).
- `TerminalViewModel` 892 -> 411 (done in 1.5.2), now `PaneSessionController`.
- Instrumented Compose UI tests: badge appears only after a second of downtime
  and is announced (liveRegion), recovering hides it, extra-key taps deliver
  the key, the rail routes a tap to the right tab, the keyboard button asks for
  the IME.
- `UiCopy` seam: screens render status messages through a composition local, so
  a translation is a new implementation rather than a rewrite (English default
  unchanged).
- `tools/ci.sh`: runs the device smoke against a private herdr session + daemon
  on port 8799, points the debug app at it, restores the saved URL, tears the
  session down, and fails loudly if the live session's workspaces or focus
  changed. Verified: CI OK, live session untouched.
- Kotlin 105 tests + 6 instrumented, Rust 50.

## 1.5.3 — 2026-11-02

- Midnight (AMOLED) now covers the whole app, not just the background: every
  Material surface role — containers lowest…highest, dim/bright, variant and
  the accent containers — is #000000, and the elevation tint is black.
  Separation comes from hairline outlines instead of lifted greys.
- Extra-key buttons: a tonal fill on black would render as floating text, so
  unarmed keys carry a 1 dp outlineVariant border and the armed state is the
  accent colour.
- Verified on device by pixel sampling: Home 97% pure black (the rest is text
  and status dots), dialog surfaces #000, extra-keys panel #000 with visible
  key outlines.

## 1.5.2 — 2026-11-02

- Scroll: a mirrored TUI that banked a few local rows used to be routed to the
  local transcript, so a drag moved a buffer nobody was looking at and the app's
  own viewport stayed still ("scroll is dead"). The host now verifies: if a real
  drag leaves the view's own offset at 0, that pane switches to the remote path
  and the same gesture is replayed. Also flushes a discrete replay immediately
  instead of waiting for a page boundary.
- Tests no longer touch the user's session: `workspace.create` accepts
  `focus:false` (daemon + client), so a test workspace never takes Herdr's
  focus. Verified: focus stayed on the user's workspace across create, attach,
  scroll, keyboard checks and close.
- `tools/smoke.sh`: the manual device checks are now a script — isolated
  workspace, one-drag scroll, repeatability within a tolerance, keyboard
  show/hide/background-return, and an explicit "focus was not taken" assertion.
- `TerminalViewModel` split: attach/target/recovery moved to
  `PaneSessionController` (892 -> 409 lines) with the behaviour tests covering
  the rules end to end.
- `UiMessage` moved to the design system and now covers Home and Settings too;
  a settings failure that used to be swallowed is rendered.
- Kotlin 98 tests / Rust 50; smoke OK on device.

## 1.5.1 — 2026-11-02

Refactor pass with tests written from behaviour, not implementation.

- `HerdrSession`: the view-model depends on a narrow contract (state,
  generations, openTerminal, the five commands) instead of the concrete
  `HerdrClient`. A scripted session now drives the screen's state machine, so
  the rules are executable: active pane opens; a workspace closed elsewhere
  retargets and says so; an empty session drops the target; a daemon restart
  reopens the same pane; a dropped stream re-attaches without user action;
  input typed during re-attach is delivered.
- `UiMessage`: the status line is data (category + parameters) with one
  renderer, replacing 19 inline strings. Tests assert the contract: every
  message renders, failures name the action and the reason, a missing reason
  never leaks null, notices that name a destination include it, every command
  has distinct wording.
- `ScrollMath`: drag→rows, rows→pages and the sub-page flush are pure
  functions with tests for proportionality, accumulation, direction, unknown
  pitch and whole-screen conversion — the manual device checks are now code.
- Kotlin 98 tests / Rust 50. Device smoke: pane renders, scroll moves 369k px,
  keyboard opens by button and stays hidden on return from background.

## 1.5.0 — 2026-11-02

Scroll is now negotiated at runtime instead of guessed from an app name.

- **Mechanisms**: host offset (`pane.scroll`, line-exact), page keys (whole
  screens), SGR wheel notches. Candidate order comes from capabilities: host
  scrollback first, then wheel for wheel-known apps, page keys always.
- **Probe**: the first request of a pane hashes the visible screen
  (`TerminalBuffer.transcriptText`), sends, and checks again 260 ms later. An
  inert mechanism is swapped for the next candidate and the same rows are
  re-sent, so the gesture is never lost; a working one is remembered per app
  (process lifetime). This is how Codex/OMP/OpenCode/Pi/Grok/Claude Code and
  arbitrary TUIs get an answer without a hand-maintained table.
- **Verified on device**: `less` in a mirrored pane — two identical drags move
  exactly 43 rows each (one screen), no double-apply from the probe; panes with
  a local transcript keep native scrolling.
- `unit: rows | pages` on the wire, page size from the host viewport, and
  `scroll{offsetFromBottom,maxOffsetFromBottom,viewportRows}` in the snapshot
  (from 1.4.7) remain the transport underneath the negotiation.

## 1.4.7 — 2026-11-02

- Host scrolling: Herdr exposes `pane.scroll { offset_from_bottom }`, so panes
  that have host scrollback now scroll line-exactly instead of in page jumps.
  The daemon calls it, clamps to `[0, max_offset_from_bottom]`, memoizes the
  live offset and falls back to page keys when a pane has no scrollback.
- The snapshot now carries `scroll` per pane (`offsetFromBottom`,
  `maxOffsetFromBottom`, `viewportRows`), and the app uses `viewportRows` as
  the page unit — Herdr attached at 30 rows while the phone drew 44, so page
  gestures drifted between screens.
- Scroll requests carry an explicit `unit` ("rows" | "pages") so the daemon
  never has to guess what `lines` counts: rows go through `pane.scroll`, pages
  are used verbatim by the PgUp/PgDn fallback. Verified on device: two
  identical drags move the same distance on `less` (one screen each), and the
  local-transcript path is unchanged.
- Daemon tests: 50 (scroll policy, pane.scroll clamping, unit conversion).

## 1.4.6 — 2026-11-02

- Anything with no local transcript now scrolls remotely. Mirrored full-screen
  TUIs (less, vim, top, agent panes) never accumulate local scrollback, so the
  previous "local unless the pane runs an agent" rule made them dead to a
  swipe. Routing is now "can anything actually move?" — alt-screen/mouse
  tracking, or no local rows → remote; otherwise local.
- Page scrolls use the view's real row count (height / renderer pitch). The
  mirror's grid reported 22 rows for a 44-row view mid-resize, so every page
  gesture scrolled twice as far as the gesture suggested.
- Daemon: remote scroll of a pane with host scrollback now moves by the exact
  requested rows through Herdr's absolute `pane.scroll` offset instead of
  whole-screen jumps; falls back to page keys where the app owns the viewport.
- Daemon: the `scroll` frame's `lines` is treated as ROWS on every backend —
  page-key fallbacks convert with the attachment height (min 1 page, max 3).
- `/v1/snapshot` panes carry `scroll` (`offsetFromBottom`, `maxOffsetFromBottom`,
  `viewportRows`) when Herdr reports it.

## 1.4.5 — 2026-11-02

- Clipboard multi-line paste: inside a 2004 bracketed block the payload is
  literal text, so newlines stay LF. They used to be normalized to CR — the
  same byte Enter sends — so a multi-line paste into Pi/codex/omp fired one
  submit per line instead of inserting the text.
- Paste is also no longer re-normalized on the way out: the prepared payload
  went through the text-send path, which stripped the 2004 markers and turned
  LF into CR a second time. Clipboard now has its own verbatim send path.
- Scroll speed is consistent across panes: remote scrolling used
  view.height/rows (off by the renderer's real pitch), the fling added a fixed
  ±12-row jump on top of the drag, and page-key panes moved a whole screen per
  request no matter how short the gesture was. Now one row unit (the renderer's
  pitch), a fling capped at 6 rows, and page scrolls accumulated from the drag
  distance with a single page flushed on release.

## 1.4.4 — 2026-11-02

- Connection badge: a small pill appears over the terminal's top rows when the
  link stays down for more than a second (Connecting / Reconnecting /
  Refreshing / Connection lost) and disappears the moment it is back.
- Reconnect keeps the pane: a daemon restart or tunnel drop used to leave
  "Detached (closed)" behind; the app now re-attaches the same pane with a
  bounded retry and only falls back to Retry when that really fails.
- Scroll routing reads live mouse tracking: SGR wheel bytes are only sent to a
  program that asked for them. Agent panes without mouse tracking page the
  host's scrollback instead of typing inert sequences.
- Architecture pass (no behaviour change intended, all covered by tests):
  `ScrollRoute` (one scroll policy), `ImePolicy` (one keyboard policy),
  `TerminalKeyboardController` (one IME owner), `TermuxInternals` (one
  reflection door), `TargetReconciler` (pure retarget decision),
  `TerminalScreen` split into screen/rails/dialogs/status/input-panel,
  Home screen dialogs moved out, daemon scroll gating shared by both backends.
- Fixed a stale `connection-ssh` test that did not compile with the current
  `HostProfile`.

## 1.4.3 — 2026-11-02

- Shell scroll fixed: Herdr TUI keeps a stale `agent` label on panes the agent
  already exited (a plain `nico@…$` prompt still reported `agent=codex`), so
  those panes were routed to remote scrolling and swallowed SGR wheel bytes —
  "scroll does nothing". Live alt-screen / mouse tracking still routes remote;
  anything else scrolls the local transcript, and a main-screen agent with no
  local history (omp) still uses the host scrollback.
- Keyboard is button-only now. Focus and pane switches never raise the IME,
  and a focused terminal no longer re-shows the keyboard when the app returns
  from the background. Switching the floating input panel keeps an open
  keyboard open (it used to clear focus and dismiss it).
- Closing a workspace no longer strands the screen on "Detached (closed)":
  the app retargets the nearest surviving workspace and says so.
- Floating input panel sat on a doubled inset (nav bar + IME); one union
  inset now, so the card hugs the gesture area with no dead band.

## 1.4.2 — 2026-11-02

- Midnight theme: pure-black AMOLED app theme + matching terminal scheme.
  All M3 surfaces #000 (dynamic color bypassed), terminal background
  #000000. Selectable separately in Settings > Appearance.
- Shell scroll investigated: no app regression found — local Termux scroll
  works in all zones when history exists (verified by zone mapping).
  Reported symptom matches empty-history panes (nothing above to reveal)
  or bottomed-out viewports.

## 1.4.1 — 2026-10-30

- Harden herdr-core input and event routing: bracketed paste keeps ESC markers
  on core DATA bytes while the TUI retains its `input.text` path; PageUp/PgDn
  scrolling requires app/alt-screen evidence; app identity comes from core's
  `status.app`; and event cursors are isolated per subscriber. Connection-target
  changes clear cached snapshots and release old terminal attachments, stale
  refreshes cannot republish prior state, and core-only unavailable errors stay
  typed and visible to the user.
- Fixes Flow-invariant crash on backend/target switch (events flow never swapped).

## 1.4.0 — 2026-10-30

- Backend choice: each connection selects Herdr TUI or Herdr app (core) in
  Settings. Daemon speaks herdr-core JSON-RPC (default socket
  $XDG_RUNTIME_DIR/herdr-core/default.sock) and serves the same /v1 API:
  snapshot/events/terminal/input/resize/interrupt/create/rename/close.
  Move/focus return typed unavailable (no core equivalent); scroll sends
  PgUp/PgDn keys; mouse becomes SGR bytes. TUI path unchanged (default).

## 1.3.29 — 2026-10-29

- AgentMatrix covers all 24 supported `--kind` values with per-entry
  upstream evidence. Qwen's documented 3-line wheel stride is preserved
  explicitly. copilot/hermes stay on conservative page_key (wheel stride
  unestablished); all unverified kinds keep safe OMP defaults.

## 1.3.28 — 2026-10-28

- AgentMatrix: per-agent scroll/paste behavior in one measured table.
  Opencode joins wheel scroll + always-bracket (PgUp=full page, wheel=1
  row, 2004 honored — verified on live pane). Pi/codex/omp unchanged.
  Unknown agents keep the safe OMP defaults. Sources cited per entry.

## 1.3.27 — 2026-10-28

- Pi paste: bracket by agent kind (Pi's TUI renders server-side so the local
  emulator never sees its 2004 DECSET) and send bracketed payloads via the
  bytes path (the text path strips the ESC in the markers). Multiline
  clipboard now lands as one block in Pi's input box; no more line-by-line
  submits or stray `[200~`/`m` fragments.

## 1.3.26 — 2026-10-28

- Pi scroll: route to SGR wheel like codex. Pi binds PgUp/PgDn to full
  viewport jumps (lines ignored) but honors one wheel event as exactly one
  row — small drags no longer teleport across the transcript.

## 1.3.25 — 2026-10-28

- Termux parity (verified vs v0.118.3 sources): NUM_LOCK key mapping, key-up
  unicode decision unified with key-down, inherited IME contract documented
  with upstream citations, redundant tap-focus removed.
- Fix: native-CTRL soft-keyboard commits (e.g. penti) now route through the
  CTRL table instead of arriving as plain letters.

## 1.3.24 — 2026-10-28

- Paste: bracketed-paste (2004) wrap when the app opts in — multiline
  clipboard lands as one block in agent input boxes instead of line-by-line
  submits. Gate reads the live emulator's 2004 bit (Termux `paste()` parity);
  plain shells get raw text exactly as before.
- Debug-only ClipSeedActivity for E2E paste tests (never shipped in release).

## 1.3.23 — 2026-10-26

- Extra Keys: SHIFT sticky modifier (replaces PGDN slot, 7-key grid kept).
  SHIFT+letter uppercases, SHIFT+arrows/HOME/END/PGUP/PGDN emit `;2` modified
  CSI, stacks with CTRL/ALT and the soft keyboard.
- Extra Keys: PGUP is dual-action — tap = PGUP, flick down = PGDN.
- Gboard Backspace/Enter still arrive only as InputConnection callbacks
  (TerminalView is final, cannot wrap); tracked separately.

## 1.3.22 — 2026-10-24

- Settings: Daemon URL field uses the Uri keyboard (no autocorrect mangling URLs).
- Refactor: single remote-routing check, shared Versions.isNewer (daemon-twin
  tested), single scroll-send entry point. No behavior change.

## 1.3.21 — 2026-10-24

- IME explicit-only: open/tab-switch/Home always land keyboard-down; only the
  keyboard button and direct taps summon. Verified via mInputShown on emulator.
- Home update pill: daemon checks herdr.dev/latest.json (startup + 6h), shows
  `Update <ver>` when newer; tap runs `herdr update --handoff` on the host.
- Terminal: pager dots removed (swipes intact); remote-scroll coalescing +
  inbound burst-merge fix the judder while OMP/codex stream output.
- Daemon: `/v1/herdr/update` (GET status / POST run), `herdr.update_available`
  event forces client refetch; `HERDR_MOBILE_FAKE_LATEST` override for E2E.

## 1.0.0 — 2026-10-06

Initial MVP.

**App**

- Home: workspace cards with status dots, condensed paths, peer tab chips (never
  fake-selected), offline/stale banners, per-workspace recomposition.
- Terminal: compact workspace/tab rails with horizontal overflow, `+` new-tab action,
  Canvas-rendered ANSI terminal (256-colour, truecolour, alt screen, CJK wide chars,
  scrollback, selection, pinch zoom), Extra Keys panel (Termux default layout, CTRL/ALT
  latch), CJK pre-composition panel (compose → commit → send as UTF-8).
- Settings: M3 grouped lists for Appearance, Connection (SSH profiles + key import,
  direct tailnet URL, per-kind connection test), Terminal, Notifications toggles.
- Transports: SSH tunnel (key auth, host-key verification, loopback forward) and direct.
- Secrets in Android Keystore (AES-256-GCM); values in DataStore; i18n en/ja.
- Notifications for done/blocked/failed transitions with workspace/tab deep links.

**Daemon (`daemon/`)**

- Herdr Unix-socket client, snapshot cache with revision/seq, narrow event subscription
  with `snapshot.required` invalidation, terminal attach via
  `herdr terminal session control`, full `/v1` REST + WebSocket API, bearer auth,
  systemd-ready, secret-free logs.

**Verified**

- `cargo test` (14), JVM unit tests (65), `:app:lintDebug`, emulator install with live
  data: Home cards, Terminal ANSI render, Extra Keys labels, CJK panel, connection test,
  terminal input round-trip on a scratch workspace.

**Deferred to physical hardware**

- IME composition feel, keyboard-driven resize, haptics, Doze/background, Tailscale/SSH
  over real networks, OEM behaviour, high-refresh rendering.
