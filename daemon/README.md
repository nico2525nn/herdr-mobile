# herdr-mobile-daemon

Thin bridge between a running [Herdr](https://herdr.dev) server and the Herdr Mobile
Android client. Herdr stays the single source of truth: this daemon caches Herdr state only
to answer the phone quickly, and rebuilds everything from Herdr on every reconnect and on
every structural change.

## Build

```bash
cd daemon
cargo build --release
# binary: target/release/herdr-mobile-daemon
```

No TLS in this binary by design: it speaks plain HTTP on loopback and is meant to sit
behind an SSH tunnel (recommended) or a tailnet.

## Run

```bash
./target/release/herdr-mobile-daemon --bind 127.0.0.1:8765
```

Options:

| Flag | Default | Meaning |
|---|---|---|
| `--bind <ADDR>` | `127.0.0.1:8765` | Listen address |
| `--allow-remote-bind` | off | Required to bind a non-loopback address |
| `--token <TOKEN>` | `$HERDR_MOBILE_TOKEN` | Bearer token; omit for loopback-only dev use |
| `--herdr-socket <PATH>` | `$HERDR_SOCKET_PATH` → `~/.config/herdr/herdr.sock` | Herdr Unix socket |
| `--herdr-bin <PATH>` | `herdr` on `PATH` | Herdr CLI used for terminal attach |
| `--print-token` | — | Print a fresh random token and exit |

Generate a token once and store it in the phone's secure storage (never in a file next
to the binary):

```bash
./target/release/herdr-mobile-daemon --print-token
# HERDR_MOBILE_TOKEN=<output> ./target/release/herdr-mobile-daemon
```

## Install as a systemd user unit

```ini
# ~/.config/systemd/user/herdr-mobile-daemon.service
[Unit]
Description=Herdr Mobile bridge daemon
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=%h/path/to/herdr-mobile-daemon --bind 127.0.0.1:8765
Environment=HERDR_MOBILE_TOKEN=replace-with-print-token-output
Restart=on-failure
RestartSec=2

[Install]
WantedBy=default.target
```

```bash
systemctl --user daemon-reload
systemctl --user enable --now herdr-mobile-daemon
systemctl --user status herdr-mobile-daemon
journalctl --user -u herdr-mobile-daemon -f
```

## Phone → daemon paths

SSH tunnel (recommended):

```text
Android (SSH key) → Linux sshd → 127.0.0.1:8765
```

Direct tailnet (bind the tailnet address explicitly):

```bash
./target/release/herdr-mobile-daemon --bind 100.x.y.z:8765 --allow-remote-bind --token <secret>
```

## API (all under `/v1`)

| Method | Path | Notes |
|---|---|---|
| `GET` | `/v1/health` | `ok` only when Herdr is reachable and primed |
| `GET` | `/v1/snapshot` | Full camelCase session snapshot |
| `GET` | `/v1/workspaces` | Workspaces with tabs |
| `GET` | `/v1/panes` | Flat pane list |
| `WS` | `/v1/events` | `stream.ready` then `seq`-ordered semantic events |
| `WS` | `/v1/terminal/{paneId}?cols=&rows=` | `ready` text frame, raw ANSI binary frames, `closed`/`error` |
| `POST` | `/v1/pane/{id}/input` | `{"encoding":"utf-8"\|"base64","text":"…"}` |
| `POST` | `/v1/pane/{id}/interrupt` | Sends `ctrl+c` |
| `POST` | `/v1/pane/{id}/resize` | `{"cols":n,"rows":n}` via a short-lived control child |
| `POST` | `/v1/agent/report` | `{"paneId","status","agent","message?","source?"}` → `pane.report_agent` |
| `POST` | `/v1/tab` | `{"workspaceId","label?"}` → `{"ok":true,"tabId"}` |

Terminal socket client→daemon frames: binary = raw input bytes; text = `resize`, `scroll`,
`mouse`, `release` control records. Unknown text frames are ignored, never fatal.

Semantic event types: `stream.ready`, `snapshot.required`, `workspace.created|closed|
renamed|status_changed`, `tab.created|closed|renamed|status_changed`,
`pane.created|closed|status_changed`. `seq` is global and strictly increasing; a gap means
the client must refetch `/v1/snapshot`.

## Herdr subscription notes

Two behaviours discovered against Herdr 0.9.1 and encoded here:

- `pane.agent_status_changed` requires a concrete `pane_id`; the daemon subscribes
  per known pane and resubscribes with the fresh pane set after every structural change.
- High-frequency passive subscriptions (`pane.updated` etc.) flood the retained history
  and get slow readers disconnected, so the daemon subscribes narrowly (structure +
  renames + per-pane status) and heals the rest via snapshot refetch.
- The event-stream write half is kept open for the life of the subscription; dropping it
  half-closes the socket and Herdr hangs up.

## Logging

Event types and pane ids at `debug`; connection lifecycle at `info`. Bearer tokens and
terminal content are never logged. `RUST_LOG=herdr_mobile_daemon=debug` for subscribe
tracing.
