# Working on herdr-mobile

Android client for Herdr (Codex, omp, OpenCode, Pi, Grok, Claude Code in panes on a
remote host). Kotlin/Compose app + Rust daemon in `daemon/`.

## Read first

- `docs/terminal-engine.md` — libghostty 1.0 engine, attach/prelude limits,
  scroll routing table, input/selection, upgrade drift. Read before touching the
  terminal engine or scroll routing.
- `CHANGELOG.md` — what was tried and what it broke.

## Non-negotiables

- **Verify on the device.** `tools/ci.sh` runs the smoke in a private herdr session
  and fails if the live session's workspaces or focus change. `tools/agent-scroll-matrix.sh`
  covers the agents. Unit tests alone have never caught a terminal regression here.
- **Never touch the user's session.** No focusing, closing or scrolling their panes
  from tests. Create workspaces with `{"focus": false}`; close them afterwards.
- **Never guess a protocol behaviour twice.** Measure it (logs, screenshots, the
  daemon), then write the measurement into `docs/terminal-engine.md`.
- **Scroll routing follows the pane report.** The attach prelude carries no
  modes; choose host/wheel/page/local from the snapshot (`scrollRoutingFor`).
- Release convention: bump `versionCode`/`versionName`, commit, annotated tag, push
  main + tag, `gh release create` with the signed APK.

## Layout

| Module | Responsibility |
|---|---|
| `terminal-ghostty` | Vendored libghostty-vt 1.0 engine + Android view/session (MIT, `LICENSE-sagernet`), JNI in `src/main/cpp` |
| `feature-terminal` | `TerminalScreen` (chrome), `TerminalSurface` + `GhosttyTerminalController` (engine hosting), `PaneSessionController` → `TerminalAttach`/`TargetResolution`/`PendingInput`, `ScrollRoutingPolicy`, `UiMessage` |
| `core-network` | `HerdrSession` contract, `TerminalControl` (record parsing), link lifecycle, snapshot sync, transport |
| `testing` | Shared fakes for tests (`FakeHerdrSession`, `FakeTerminalConnection`); test-only, never imported by app code |
| `baselineprofile` | Macrobenchmark module that generates `app/src/release/generated/baselineProfiles/baseline-prof.txt` |
| `daemon/` | HTTP + websocket bridge, herdr TUI and herdr-core backends |

## Tests

- Pure logic lives in `src/test` and runs on the JVM: routing (`ScrollRoute`,
  `ImePolicy`, `ScrollMath`), transport contracts (`TerminalControl`,
  `DaemonApi`), view-model behaviour with `FakeHerdrSession`, and settings via
  `SettingsCodec`.
- Compose/Android wiring lives in `src/androidTest` and needs a device.
- Android framework seams that are not Compose (hardware keys, views) use
  Robolectric on the JVM (`terminal-view` `TermuxViewClientKeyTest`).
- Fakes shared by more than one module belong in `:testing`, not copied.
- Coverage is opt-in (`-Pcoverage`), so `./gradlew test --offline` never needs
  the JaCoCo agent; reports land in `<module>/build/reports/jacoco/`.

## Performance rules

Measured before/after, do not regress casually:

- **Never read a per-keystroke counter in composition.** `inputTick` is a Flow
  consumed inside the terminal surface; as a parameter it recomposed the whole
  screen per key.
- Theme/font/cursor setters are idempotent; apply them from keyed effects, not
  from the AndroidView update pass (which runs on every recomposition).
- Releasing another pane's socket is background work; only a same-pane reattach
  waits for the release (grid restore order).
- Host scroll deltas are coalesced (40 ms) before they reach the socket.

## Commands

```bash
./gradlew :app:assembleDebug --offline
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
tools/check.sh              # JVM unit tests + daemon tests (the local gate)
./gradlew test --offline
./gradlew test -Pcoverage --offline        # + JaCoCo per module
./gradlew :feature-terminal:connectedDebugAndroidTest   # instrumented UI
./gradlew :feature-home:connectedDebugAndroidTest :feature-settings:connectedDebugAndroidTest
cd daemon && cargo test --offline

# Regenerate the startup profile after changing startup/Home composition
# (needs a booted device and HERDR_RELEASE_KEYSTORE_PASSWORD):
./gradlew :app:generateBaselineProfile
# The generated app/src/release/generated/baselineProfiles/baseline-prof.txt
# is committed and consumed by the release build.
tools/ci.sh                 # boots AVD from scratch if no device, provisions
                            # the IME, runs the smoke, tears everything down
# CI (.github/workflows/ci.yml): kotlin unit, rust, and the isolated emulator
# smoke. Note the workflow runs Gradle ONLINE: a cold runner has no cache.
tools/deploy-daemon.sh      # the step that actually updates the service clone
```
