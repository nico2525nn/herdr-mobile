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
| `terminal-emulator`, `terminal-view` | Termux fork; `TermuxTerminalHost` (gestures + routing), `ScrollProbe`/`WheelBurst`/`TermuxViewport`, `ScrollRoute`/`ScrollMath`, remote session pump |
| `feature-terminal` | `TerminalScreen` (chrome) + `TerminalSurface` (view hosting) + `TerminalIme`, `PaneSessionController` → `TerminalAttach`/`TargetResolution`/`PendingInput`, `UiMessage` |
| `core-network` | `HerdrSession` contract, `TerminalControl` (record parsing), link lifecycle, snapshot sync, transport |
| `testing` | Shared fakes for tests (`FakeHerdrSession`, `FakeTerminalConnection`); test-only, never imported by app code |
| `daemon/` | HTTP + websocket bridge, herdr TUI and herdr-core backends |

## Tests

- Pure logic lives in `src/test` and runs on the JVM: routing (`ScrollRoute`,
  `ImePolicy`, `ScrollMath`), transport contracts (`TerminalControl`,
  `DaemonApi`), view-model behaviour with `FakeHerdrSession`, and settings via
  `SettingsCodec`.
- Compose/Android wiring lives in `src/androidTest` and needs a device.
- Fakes shared by more than one module belong in `:testing`, not copied.

## Commands

```bash
./gradlew :app:assembleDebug --offline
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
tools/check.sh              # JVM unit tests + daemon tests (the local gate)
./gradlew test --offline
./gradlew :feature-terminal:connectedDebugAndroidTest   # instrumented UI
cd daemon && cargo test --offline
tools/ci.sh                 # boots AVD from scratch if no device, provisions
                            # the IME, runs the smoke, tears everything down
# CI (.github/workflows/ci.yml): kotlin unit, rust, and the isolated emulator
# smoke. Note the workflow runs Gradle ONLINE: a cold runner has no cache.
tools/deploy-daemon.sh      # the step that actually updates the service clone
```
