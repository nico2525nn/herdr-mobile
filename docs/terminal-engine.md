# Terminal engine (libghostty-vt 1.0)

The terminal is **libghostty-vt 1.0.0**, the same VT engine Herdr runs on the
server. The Android view, session, theme and selection code is the vendored MIT
library `SagerNet/libghostty-android`, ported to the 1.0 C API
(`terminal-ghostty/`).

## Why

- One parser on both ends: no Termux fork, no reflection into private fields, no
  scroll probe guessing DECSET state.
- Modes are authoritative: alt screen, mouse tracking, keypad/cursor keys and
  bracketed paste are queried through the C API, so scrolling, key encoding and
  paste routing are decisions, not heuristics.
- ABI-stable C library: vendored `libghostty-vt.a` per ABI (arm64-v8a,
  x86_64) with headers; upgrades are a header/lib swap plus the drift fixes
  documented below.

## Attach model (important)

The daemon's attach prelude is **text**, not the original byte stream: it
replays history and the visible screen, but not the app's DECSET history. A
fresh engine therefore starts on the primary screen with no mouse mode even when
the pane is a running TUI. Two consequences:

- Gesture routing comes from the **snapshot** (`Pane.scroll`, `hostScrollable`,
  `agent`), not from the engine, until the app repaints. See
  `scrollRoutingFor` in `feature-terminal`.
- The prelude is not visible-only: the daemon fetches `pane.read recent` (up to
  ~1000 rows, overlap-trimmed against the visible screen) and feeds it as text,
  so the local VT has a shallow scrollback from the first frame. Deeper history
  is the host's (`pane.scroll`).
- Rendering is correct immediately (prelude text paints the screen); modes
  converge as soon as the app writes again (any key press or output).

Routing policy (`ScrollMode.AUTO`):

| Pane report | Route |
|---|---|
| `pane.scroll` present, `maxOffsetFromBottom > 0` | `REMOTE_HOST`: row deltas to `pane.scroll` (server owns full history), coalesced every 40 ms |
| `pane.scroll` present, no scrollback | wheel agents (`AgentMatrix.useWheelScroll`) → raw SGR wheel reports; other full-screen apps → one PageUp/PageDown per gesture |
| no `pane.scroll`, agent unknown, no host scrollback | `REMOTE_SERVER`: rows go to the daemon, which checks `pane.process_info`; shells never get PgUp/PgDn, full-screen apps do |
| no `pane.scroll` (core backend) | `AUTO`: raw bytes give the engine real modes and local scrollback; OMP and shells scroll locally, alt-screen apps use wheel/1007 |

Core focus lives in the daemon: `terminal.attach` and `tab.focus` update a
process-wide focused pane, and `translate_snapshot` fills workspace/tab/pane
`focused`, `activeTabId` and the top-level focus block. It is not persisted and
is shared by clients of the same daemon.

Core snapshot ordering has no native RPC: workspaces/tabs are reordered by
`herdr.space_order` / `herdr.tab_order` labels, written on every terminal in the
group and sorted when translating the core snapshot.

Explicit settings (`Local`, `Mouse wheel`, `Page keys`) override the table,
except that a pane with host scrollback keeps the host's row-accurate scroll.

In `AUTO`, alternate-screen wheel handling follows DEC mode **1007**
(alternate scroll): arrows are sent only when the program enabled it, so vim
does not get cursor keys from a scroll gesture. Core backend attaches request
`history=2000` and report the server's `historyRows` in `ready`.

## Input

- Hardware/IME keys: the engine's key encoder (`ghostty_key_encoder_*`), synced
  from terminal state before every encode (DECCKM, keypad, Kitty flags).
- Paste: `ghostty_paste_encode` (control-byte strip + bracketed-paste wrap from
  the real mode).
- Extra-keys panel: our own `TerminalKeyEncoder` tables (tested, app-specific);
  bytes go over the same transport.
- Selection: long-press selects a word, drag extends; the ActionMode copies
  through the engine's formatter.

## Vendor + upgrade notes

- `terminal-ghostty/vendor/include` + `vendor/<abi>/libghostty-vt.a` are built
  from the **release tarball** `libghostty-vt-1.0.0.tar.gz` with Zig 0.17:
  `zig build -Doptimize=ReleaseFast -Dtarget=<abi>-linux-android`.
  Android requires 16 KB page alignment; CMake passes
  `-Wl,-z,max-page-size=16384`.
- 1.0 drift that had to be fixed (a future bump will hit the same shape):
  `*_setopt*` → `*_set*`; selection formatting moved to `GhosttyFormatter`;
  `GhosttyStyle.size/bold/...` → `flags` + colors; `GraphemesBuf` takes a sized
  `GhosttyCodepointBuffer`; `GridRef`/`Selection` lost `size`; row selection is
  a **sized** struct (`GHOSTTY_INIT_SIZED`); Kitty graphics API was redesigned
  (inline images are not supported by this client); mode names drift
  (`SYNC_OUTPUT` → `SYNCHRONIZED_OUTPUT`, `COLOR_SCHEME_REPORT` →
  `REPORT_COLOR_SCHEME`).

## Verification

- `tools/ci.sh` — isolated session smoke: drag scroll, IME summon/hide,
  background/restore, live session untouched.
- `terminal-ghostty` instrumented test: canned bytes → screen text → pixels →
  key reaches the transport.
- `feature-terminal` tests: routing policy, theme mapping, view-model behaviour.
