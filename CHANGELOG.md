# Changelog

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
