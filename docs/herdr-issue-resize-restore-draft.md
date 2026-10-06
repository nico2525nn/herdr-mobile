# Direct-attach resize does not reach the pane grid, and release cannot restore it

## Setup

- Herdr server 0.9.3, local socket client using the **numbered binary protocol**
  (`terminal session control <pane>` + `terminal.resize` / `terminal.release`).
- One desktop TUI attached to the same session (the pane under test is NOT focused
  in the TUI).

## What happens

1. `terminal.resize {cols:60, rows:20}` returns a `terminal.frame` with
   `width:60, height:20` — the controller viewport changes.
2. But `pane.read (source: visible)` still returns the old grid (68 rows here), and
   `pane.get` still reports the old `scroll.viewport_rows`.
3. `terminal.release` closes the controller, but the pane keeps whatever grid it had.
   The desktop TUI keeps rendering the old size until the user focuses another tab
   (which triggers `claim_shell_tab_geometry`).

## Source reading (herdr @ master)

- `ServerEvent::ClientResize` → `headless.rs`: only a `TerminalAttach` controller gets
  `runtime.resize(rows, cols)`.
- The real pane grid is only resized via `resize_tab_surface` → `resize_tab_panes`,
  which is only reachable through `tab_geometry_controllers`.
- Those controllers only accept `is_active_shell_client()` connections, and
  `promote_client_to_foreground` returns early unless `shell_surface_active`.
- A `terminal session control` connection is `TerminalAttach`, never a shell client,
  so it can neither drive nor restore the pane grid.

## Ask

Is there, or could there be, a supported way for a non-TUI client to:

1. resize a pane's actual grid (not just the controller viewport), and
2. on detach, hand the grid back (e.g. restore the geometry controller's size)?

Use case: a mobile remote client that attaches at phone size and must leave the
desktop pane usable after detach. Right now the only recovery is focusing another
tab in the desktop TUI.

## Workaround attempted

Sending `terminal.resize` back to a desktop-like size before `terminal.release`
does not help: the controller viewport changes, then dies with the controller,
and the pane grid never moves.
