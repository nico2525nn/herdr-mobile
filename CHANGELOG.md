# Changelog

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
