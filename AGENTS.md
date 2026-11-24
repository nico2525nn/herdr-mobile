# Repository Guidelines

## Project Overview

Herdr Mobile is an Android client for [Herdr](https://herdr.dev) on a remote Linux
host: a Kotlin/Compose UI, a vendored libghostty terminal engine, and a Rust
HTTP/WebSocket daemon. Herdr remains the single source of truth. The app renders
semantic snapshots/events and opens a terminal byte stream only for the visible
pane.

**Non-negotiables**

- **Never touch the user's live Herdr session.** Tests create workspaces with
  `{"focus": false}` and close them. Use `tools/ci.sh`, never `tools/smoke.sh`
  directly against the default daemon.
- **Verify on a device.** `tools/ci.sh` runs an isolated private-session smoke and
  fails if the live workspace/focus signature changes. Unit tests alone have
  missed terminal regressions here.
- **Measure protocol behaviour before changing it.** Record the measurement in
  `docs/terminal-engine.md` or `CHANGELOG.md`.
- **Scroll routing follows the snapshot pane report** (`scrollRoutingFor`), not
  the attach prelude.
- **One signed APK per ABI; never a universal APK.** Releases also carry the
  daemon tarball and `install.sh`.

## Architecture & Data Flow

```text
Compose UI (Home / Terminal / Settings)
  -> AppContainer (manual service locator)
     -> HerdrClient (single app-wide link, HerdrSession contract)
        -> SSH tunnel or Direct provider
        -> OkHttp REST /v1/*, WS /v1/events, WS /v1/terminal/{paneId}
           -> Rust daemon (axum)
              -> TUI backend: Herdr NDJSON socket + `herdr terminal session control`
              -> core backend: herdr-core JSON-RPC / framed socket
```

- `MainActivity` -> `HerdrApp` -> `AppContainer` owns the singleton
  `HerdrClient`, active provider, settings repository, and `NotificationRelay`.
  There is no Hilt/Koin; ViewModels use explicit `Factory` classes.
- `/v1/events` carries a `stream.ready` cursor, then semantic events with strictly
  increasing `seq`. Fold only contiguous events. Any gap, structural event,
  `snapshot.required`, or tick-hash mismatch triggers an authoritative
  `/v1/snapshot` refetch. Never patch a gap.
- The 60 s `snapshot.tick` carries an FNV-1a-64 hash over pane status, order and
  host-scroll metrics. The Kotlin (`SessionSnapshot.statusHash`) and Rust
  (`status_hash_of`) implementations must stay byte-identical.
- Terminal path: `TerminalScreen` -> `TerminalViewModel` -> `PaneSessionController`
  -> `TerminalAttach`/`TerminalSocket` -> daemon `/v1/terminal/{paneId}` ->
  libghostty. Binary frames are raw PTY bytes; JSON text frames are
  `ready`/`closed`/`error` and `resize`/`scroll`/`host_scroll`/`mouse`/`release`.
- The daemon owns one `TerminalSession` per pane and shares it across bridges.
  The last bridge leaving restores the host viewport and kills the child.
- Read-ack is pane-scoped: a DONE transition on the visible pane calls
  `focusPane(paneId)`, then refreshes. Core updates daemon-local read/focus;
  TUI calls Herdr `pane.focus`. Do not use `tab.focus` for read-ack.
- Host scrollback is absolute (`pane.scroll` rows). The daemon subscribes to
  `pane.scroll_changed`, relative phone gestures re-read the live offset, and the
  app's remote scrollbar sends `host_scroll` with an absolute offset.

## Key Directories

| Path | Responsibility |
|---|---|
| `app/` | Android shell, navigation, manual DI, DataStore/Keystore settings, notification relay, foreground monitor |
| `core-model/` | Pure JVM wire DTOs, snapshot reducer, settings model, agent matrix, terminal protocol |
| `core-network/` | OkHttp REST/WS, link lifecycle, snapshot sync, terminal socket, daemon API |
| `core-designsystem/` | Material3 theme, status tokens/dots/chips, settings rows, reorder math, `UiMessage` |
| `feature-home/` | Workspace/tab overview, create/rename/close/reorder commands, update pill |
| `feature-terminal/` | Terminal chrome, rails, input panels, attach/retarget/scroll state machine |
| `feature-settings/` | Grouped settings, connection profiles, TOFU host-key approval |
| `connection-ssh/` | JSch tunnel, host-key verification, port forwarding |
| `connection-direct/` | Direct loopback/LAN/tailnet endpoint with cleartext-token guard |
| `terminal-ghostty/` | Vendored libghostty-vt 1.0.1 JNI, Android view/session, key/scroll handling |
| `notifications/` | Channels, semantic filters, stable IDs, deep-link intents |
| `testing/` | Shared test-only fakes; never imported by production code |
| `baselineprofile/` | Macrobenchmark generator for the committed startup profile |
| `daemon/` | Rust bridge: `api.rs`, `cache.rs`, `events.rs`, `terminal.rs`, `herdr/`, `herdrcore/` |
| `tools/` | Check, isolated smoke, agent matrix, daemon package/deploy, JNI check |
| `docs/` | Terminal-engine and SSH protocol notes; `CHANGELOG.md` records regressions |

## Development Commands

```bash
# App
./gradlew :app:assembleDebug --offline
./gradlew installDebug
adb reverse tcp:8766 tcp:8765                  # debug Direct URL is 127.0.0.1:8766

# Fast local gate: JVM tests + native build + JNI symbols + Rust tests
tools/check.sh

# Tests
./gradlew test --offline
./gradlew test -Pcoverage --offline            # opt-in JaCoCo
./gradlew :feature-terminal:connectedDebugAndroidTest
./gradlew :feature-home:connectedDebugAndroidTest :feature-settings:connectedDebugAndroidTest
./gradlew :terminal-ghostty:connectedDebugAndroidTest
cd daemon && cargo test --release --offline

# Isolated device smoke (private herdr-ci session + daemon :8799)
tools/ci.sh
SERIAL=emulator-5556 tools/ci.sh
SKIP_BUILD=1 tools/ci.sh
KEEP_EMULATOR=1 tools/ci.sh

# Release (run from repo root; one ABI per APK)
export HERDR_RELEASE_KEYSTORE_PASSWORD=$(cat ~/.config/herdr-mobile/keystore.password)
./gradlew :app:assembleRelease -PrequireSigning --offline
./gradlew :app:assembleRelease -PrequireSigning -Pabi=armeabi-v7a --offline
tools/package-daemon.sh
tools/deploy-daemon.sh                        # separate service clone + systemd restart

# Daemon directly
cd daemon && cargo build --release
./target/release/herdr-mobile-daemon --bind 127.0.0.1:8765
```

Local development is offline-first. CI is online (cold runners have no cache), and
runs Kotlin/JVM tests, Rust tests, release assembly/lint/JNI/ABI inspection, and
API 29/33/37 emulator smokes. The API 37 smoke job also runs the
`terminal-ghostty` connected suite (the scroll-routing regression); other
instrumented suites are not run, and APKs are not signed.

## Code Conventions & Common Patterns

**Kotlin/Compose**

- Compose + Material3 only; immutable `@Immutable data class` UI state exposed as
  `StateFlow`, collected with `collectAsStateWithLifecycle`; use
  `stateIn(..., WhileSubscribed(5_000))`.
- Manual dependency injection in `AppContainer`; ViewModels take narrow interfaces
  plus explicit `Factory`. Do not add a DI framework or a second app-wide client.
- Use `viewModelScope` for screen work, `Dispatchers.IO` for REST/DataStore/
  Keystore/JSch blocking work, and `NonCancellable` for terminal release/cleanup.
  Rethrow `CancellationException`.
- Use typed errors: `DaemonException`, `UnknownHostKeyException`,
  `KeystoreCorruptException`, and `UiMessage`; do not surface raw English strings.
- Use generation counters and sealed states for link/terminal lifecycle. Stale
  async work must be dropped, not applied to a new target.
- kotlinx.serialization uses camelCase wire names, `ignoreUnknownKeys=true`, and
  `@EncodeDefault` discriminators. Keep Kotlin DTOs and Rust models in lockstep.
- Terminal engine: only `feedOutput`/`finish` are safe off the main thread; all
  other session/view methods are main-thread. `external fun` declarations need
  matching JNI symbols.

**Rust daemon**

- Edition 2021, Tokio, `anyhow::Result`, `tracing`; exact dependency pins and
  committed `Cargo.lock`.
- Shared state uses `Arc`; `tokio::sync::Mutex` for await-holding state,
  `std::sync::Mutex` for short non-await state, `mpsc` for ordered commands,
  `broadcast` for frames/events, `Notify` for teardown.
- `EventBus` is the single sequencer: allocation, cursor, broadcast and snapshot
  stamping happen under one mutex. Do not mint/broadcast synthetic `seq` values
  elsewhere.
- Keep decision logic in pure modules (`scroll_policy.rs`, reducer/agent matrix)
  and side effects thin. All TUI/core event and JSON shapes need tests.
- Do not block the Tokio runtime on filesystem/network I/O; use timeouts and
  `spawn_blocking` where unavoidable.

**Performance invariants**

- Never read `inputTick` in composition; it is collected inside `TerminalSurface`.
- Apply theme/font/cursor from keyed effects, not from every AndroidView update.
- Coalesce host scroll (40 ms), resize settle (180 ms), daemon resize (120 ms), and
  reattach retries; reuse terminal epochs rather than spawning duplicate loops.
- Keep remote scrollbar absolute; relative gestures must re-read live `pane.get`
  so a desktop bar move cannot cause a jump.
- Avoid per-frame allocations in `terminal-ghostty`; use the existing dirty-row
  invalidation and background run batching.

**Safety boundaries**

- `core-model` is pure JVM; do not add Android/Compose dependencies.
- `:testing` is test-only; put cross-module fakes there.
- Never edit vendored `terminal-ghostty/vendor/**`, generated baseline profiles by
  hand, or `daemon/target/`.
- `SettingsKeys` strings are on-disk names; renaming drops user data.
  `ConnectionTargetKey` must contain only transport-affecting fields.
- Raw secrets live in the Android Keystore, never DataStore/UI/logs. Profile
  deletion removes secrets only after the durable profile write.

## Important Files

| Path | Why |
|---|---|
| `app/src/main/kotlin/dev/herdr/mobile/AppContainer.kt` | Manual composition root, provider debounce, link ownership |
| `app/src/main/kotlin/dev/herdr/mobile/MainActivity.kt` | Navigation, deep links, ViewModel factories |
| `core-network/.../HerdrClient.kt` | Public link/command surface |
| `core-network/.../SnapshotSync.kt` | Event folding, gap/refetch, generation checks |
| `core-network/.../TerminalSocket.kt` | Byte lanes, outbound merge, release |
| `feature-terminal/.../PaneSessionController.kt` | Attach/reconnect/scroll/read-ack state machine |
| `feature-terminal/.../TerminalSurface.kt` | Engine hosting, routing, inputTick flow |
| `core-model/.../SemanticEvent.kt` / `SnapshotReducer.kt` | Wire event contract and gap rules |
| `daemon/src/main.rs` / `api.rs` | Runtime config and `/v1` route surface |
| `daemon/src/cache.rs` | Herdr cache, subscriptions, tick hash, resync loop |
| `daemon/src/terminal.rs` | Per-pane sessions, prelude, bridge lifecycle, scroll/input |
| `daemon/src/herdr/terminal.rs` | NDJSON child protocol with Herdr |
| `daemon/src/herdrcore/` | Core JSON-RPC/framed protocol |
| `docs/terminal-engine.md` | Authoritative engine/attach/scroll/read-ack notes |
| `CHANGELOG.md` | Historical bug classes and verification evidence |
| `tools/ci.sh` | Only safe on-device smoke entry point |
| `.github/workflows/ci.yml` | CI jobs and API/ABI matrix |

## Runtime/Tooling Preferences

- **Android:** compile/target SDK 37, min SDK 26, JDK 21 in CI, Java/Kotlin target 17.
- **Gradle:** wrapper 8.14.3, AGP 8.13.2, Kotlin 2.2.10, Compose BOM 2026.06.01,
  Material3 1.4.0. Configuration cache, parallel and build cache are enabled.
- **Rust:** stable toolchain, edition 2021; daemon uses Tokio 1.53, axum 0.8,
  pinned dependencies. `cargo test --release --offline` is the local gate.
- **Native:** CMake 3.22.1, C++17, static STL, four vendored ABIs; NDK follows AGP.
- **Scripts:** `adb`, `curl`, Python 3 + Pillow, `herdr >= 0.9.5`, `nm`, `rsync`,
  `systemctl --user`, `apksigner`, `gh`, and a bootable API 37 AVD/KVM for smoke.
- **Installer:** `install.sh` downloads a release asset or builds the tagged source,
  installs `~/.local/bin/herdr-mobile-daemon`, and enables a systemd user unit on
  loopback. Daemon tarballs are built by `tools/package-daemon.sh`.
- Use `--offline` locally; CI resolves dependencies online. Coverage requires a
  warm JaCoCo cache when offline.

## Testing & QA

| Layer | Location / command |
|---|---|
| JVM unit tests | `*/src/test`, `./gradlew test --offline`; pure policy, contracts, reducers, ViewModels |
| Shared fakes | `testing/.../FakeHerdrSession.kt`, `FakeTerminalConnection`; never copy across modules |
| Network fixtures | `core-network/src/test/.../LinkTestSupport.kt` (`ManualClock`, `FakeDaemonTransport`) |
| Compose/device UI | `*/src/androidTest`, `connectedDebugAndroidTest`; not run in CI |
| Native engine | `terminal-ghostty/src/test` pure math + instrumented `GhosttyEngineSmokeTest` |
| Rust | inline `#[cfg(test)]` in `daemon/src/**`; `cargo test --release --offline` |
| Local gate | `tools/check.sh` (JVM + native + JNI + Rust, offline) |
| Device smoke | `tools/ci.sh` (private Herdr session/daemon, screenshots/uiautomator) |
| CI | Kotlin/JVM, Rust, API 29/33/37 smoke, release/lint/JNI/32-bit ABI checks |

- Use JUnit 4 + `kotlinx-coroutines-test`; set/reset Main dispatcher and advance
  virtual time. Prefer behavior-named tests and observable fake calls/semantics.
- Add a regression test for every bug fix when a contract seam exists; update shared
  vectors when changing hashes/protocols.
- Coverage is opt-in (`-Pcoverage`/`COVERAGE=1`) with no threshold. Reports land
  under `<module>/build/reports/jacoco/jacocoUnitTestReport/`.
- Known gaps: no Robolectric usage; `daemon/tests/` is empty; CI runs only the
  `terminal-ghostty` instrumented suite; `app`'s SSH E2E has real-tailnet defaults.
- Before calling a task done, run the narrow test, `tools/check.sh`, and the isolated
  smoke for terminal/device-facing changes; then update `CHANGELOG.md`.

**Stale docs:** `plan.md` is historical product intent, not current architecture.
`daemon/README.md`/`README.md` may lag protocol/flag additions. Trust source,
`docs/terminal-engine.md`, and `CHANGELOG.md` over old comments.
