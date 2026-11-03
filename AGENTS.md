# Working on herdr-mobile

Android client for Herdr (Codex, omp, OpenCode, Pi, Grok, Claude Code in panes on a
remote host). Kotlin/Compose app + Rust daemon in `daemon/`.

## Read first

- `docs/terminal-scroll.md` — scrolling contract, the audited per-agent behaviour,
  and the seven traps that each shipped as a regression. Do not touch scroll code
  without it.
- `CHANGELOG.md` — what was tried and what it broke.

## Non-negotiables

- **Verify on the device.** `tools/ci.sh` runs the smoke in a private herdr session
  and fails if the live session's workspaces or focus change. `tools/agent-scroll-matrix.sh`
  covers the agents. Unit tests alone have never caught a terminal regression here.
- **Never touch the user's session.** No focusing, closing or scrolling their panes
  from tests. Create workspaces with `{"focus": false}`; close them afterwards.
- **Never guess a protocol behaviour twice.** Measure it (logs, screenshots, the
  daemon), then write the measurement into `docs/terminal-scroll.md`.
- **No local scroll for a pane the host reported as full-screen.** It moves the
  terminal buffer instead of the app's viewport.
- Release convention: bump `versionCode`/`versionName`, commit, annotated tag, push
  main + tag, `gh release create` with the signed APK.

## Layout

| Module | Responsibility |
|---|---|
| `terminal-emulator`, `terminal-view` | Termux fork, `TermuxTerminalHost` (touch/scroll/IME), `ScrollRoute`/`ScrollMath`, remote session pump |
| `feature-terminal` | terminal screen, `PaneSessionController` (target/attach/recovery), `UiMessage` |
| `core-network` | `HerdrSession` contract, link lifecycle, snapshot sync, transport |
| `daemon/` | HTTP + websocket bridge, herdr TUI and herdr-core backends |

## Commands

```bash
./gradlew :app:assembleDebug --offline
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew test --offline
cd daemon && cargo test --offline
tools/ci.sh
```
