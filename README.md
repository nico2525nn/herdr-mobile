# Herdr Mobile

Android client for watching and operating [Herdr](https://herdr.dev) terminal workspaces —
coding agents, tmux sessions and tabs — from a phone.

> **Home for semantic overview; Terminal for deep interaction only when needed.**

Herdr on Linux is the single source of truth. This app never reimplements session state:
it shows state, opens a terminal stream only on demand, and sends input back.

## Modules

```text
app/                 shell: nav, DI, DataStore+Keystore settings, notifications relay
core-model/          shared model + wire DTOs + SnapshotReducer (+tests)
core-network/        OkHttp REST/WS, HerdrClient reconnect + seq-gap policy
core-designsystem/   M3 theme, HerdrColors status tokens, StatusDot/Chip, Settings rows
feature-home/        semantic overview: workspace cards, peer tab chips
feature-terminal/    rails + terminal surface + extra-keys/CJK bottom panel
feature-settings/    M3 grouped-list settings (appearance/connection/terminal/notifications)
terminal-emulator/   pure-Kotlin VT parser + screen buffer + themes (+48 tests)
terminal-view/       Canvas renderer, TerminalBridge, key encoder (+tests)
connection-ssh/      JSch tunnel provider (key auth, host-key verification, port forward)
connection-direct/   tailnet/LAN direct provider
notifications/       channels + semantic filters + deep-link intents
daemon/              Rust bridge: Herdr socket → /v1 HTTP+WS (see daemon/README.md)
```

## Daemon

```bash
cd daemon && cargo build --release
./target/release/herdr-mobile-daemon --bind 127.0.0.1:8765
```

Full install, token, systemd and API docs: [`daemon/README.md`](daemon/README.md).

## App development (emulator)

Prerequisites: Android SDK, KVM, an API 36+ AVD. From the project root:

```bash
./gradlew installDebug
```

The daemon runs on the host; from the emulator it is `http://10.0.2.2:8765`
(Settings → Transport → Direct (tailnet), debug builds allow cleartext).
Release builds use the SSH tunnel (loopback forward); the daemon speaks plain
HTTP by design and there is no TLS terminator yet, so Direct-over-https is
not currently available.

```bash
adb devices
./gradlew :app:assembleDebug
./gradlew testDebugUnitTest :core-model:test   # JVM tests
```

## Release APK

```bash
./gradlew :app:assembleRelease
# → app/build/outputs/apk/release/app-release-unsigned.apk
~/Android/Sdk/build-tools/36.0.0/apksigner sign \
  --ks ~/.android/debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out app/build/outputs/apk/release/app-release-signed.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk
```

The signed APK is installable directly (`adb install -r`). Production releases must
be signed with the real release key, never the debug key.

## Protocol (v1)

- `GET /v1/health /v1/snapshot /v1/workspaces /v1/panes`
- `WS /v1/events` — `stream.ready` then strictly increasing `seq` semantic events;
  any gap or `snapshot.required` forces a snapshot refetch, never a patch.
- `WS /v1/terminal/{paneId}` — binary frames are raw ANSI/input bytes both ways;
  JSON text frames carry `ready`/`closed`/`error` and `resize`/`scroll`/`mouse`/`release`.
- `POST /v1/pane/{id}/input|interrupt|resize`, `/v1/agent/report`, `/v1/tab`

## Security

- Private keys and bearer tokens live in the Android Keystore (AES-256-GCM), referenced
  by alias from DataStore profiles. Raw secrets never appear in UI or logs.
- Host keys are verified; unknown keys never pass silently.
- The daemon binds loopback by default and refuses remote binds without an explicit flag.
- Terminal content and tokens are never logged on either side.

## Status

MVP per `plan.md` §25, except on-device verification (IME, Doze, Tailscale/SSH over real
networks, OEM behaviour), which needs physical hardware.
