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
terminal-ghostty/    libghostty-vt 1.0.1 JNI, Android view/session, key + scroll handling
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

## Install the daemon

On the host that runs Herdr:

```bash
curl -fsSL https://raw.githubusercontent.com/nico2525nn/herdr-mobile/main/install.sh | sh
```

The installer downloads the matching GitHub release binary (or builds from the
tag when no prebuilt asset exists), installs it under `~/.local/bin`, and enables
a user systemd unit on `127.0.0.1:8765`. Options: `HERDR_MOBILE_VERSION`,
`HERDR_MOBILE_PREFIX`, `HERDR_MOBILE_NO_SERVICE=1`, `HERDR_MOBILE_TOKEN`.
SSH tunneling remains the recommended phone → daemon path.

## App development (emulator)

Prerequisites: Android SDK, KVM, an API 36+ AVD. From the project root:

```bash
./gradlew installDebug
```

The daemon runs on the host. Debug builds default Direct to
`http://127.0.0.1:8766`; run `adb reverse tcp:8766 tcp:8765` so the emulator
reaches the host daemon. Release Direct defaults to `127.0.0.1:8765` as well;
10.0.2.2 is no longer treated as a loopback exemption.
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
export HERDR_RELEASE_KEYSTORE_PASSWORD=$(cat ~/.config/herdr-mobile/keystore.password)
mkdir -p build/release

# Default arm64 artifact; copy it before the next build overwrites the output.
./gradlew :app:assembleRelease -PrequireSigning --offline
cp app/build/outputs/apk/release/app-release.apk \
  build/release/herdr-mobile-arm64-v8a.apk

# 32-bit device artifact.
./gradlew :app:assembleRelease -PrequireSigning -Pabi=armeabi-v7a --offline
cp app/build/outputs/apk/release/app-release.apk \
  build/release/herdr-mobile-armeabi-v7a.apk

~/Android/Sdk/build-tools/36.0.0/apksigner verify --print-certs \
  build/release/herdr-mobile-arm64-v8a.apk
```

`-PrequireSigning` fails the build instead of silently producing an unsigned
APK. Production releases use `~/.config/herdr-mobile/release.keystore`, never the
debug key. Attach one signed APK per ABI; never a universal APK.

Full checklist (tag before build, checksum, installer, `gh release create`):
[docs/release.md](docs/release.md).

## Protocol (v1)

- `GET /v1/health /v1/snapshot /v1/workspaces /v1/panes`
- `WS /v1/events` — `stream.ready` then strictly increasing `seq` semantic events;
  any gap or `snapshot.required` forces a snapshot refetch, never a patch.
- `WS /v1/terminal/{paneId}` — binary frames are raw ANSI/input bytes both ways;
  JSON text frames carry `ready`/`closed`/`error` and `resize`/`scroll`/`mouse`/`release`.
  `scroll` is `{"direction":"up"|"down","lines":N}` in ROWS; panes with host
  scrollback move by absolute `pane.scroll` offset, the rest convert to whole pages.
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
