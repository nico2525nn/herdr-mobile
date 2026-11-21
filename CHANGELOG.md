# Changelog

## Unreleased — post-2.2.0 deep-review fixes

- **CI safety:** `tools/ci.sh` never closes/stops a Herdr session without its
  own `.ci-owned` marker, refuses a private port that matches the live daemon,
  and matches a stale daemon by its exact private socket as well as the port.
  Isolation now compares a fresh read-only `herdr api snapshot` (workspaces and
  focused workspace/tab/pane) before and after; a running live session whose
  snapshot cannot be read fails the run instead of being skipped.
- **Extra keys:** normal keys use one pointer path (no onClick/PressInteraction
  double send) and send on tap release or a real 400ms hold, so a page swipe
  starting on a key no longer types it. PGUP tap/flick/accessibility coverage
  remains, with ratio-based axis arbitration (1.5x dominance, 2x slop for the
  pager) so a downward flick that starts slightly sideways is not stolen.
- **CJK panel:** when `Panel swipe` is not PAGE, an explicit Keys/Input switch
  appears so the CJK page remains reachable, and the page buttons expose the
  selected state to accessibility services.
- **Terminal lifecycle:** snapshots that arrive while stopped are resolved on
  resume (no empty terminal after a background return), a reconnect wait
  timeout now surfaces `ReconnectTimedOut`, the last pane removal releases its
  socket, and the terminal owns the full safe-drawing/IME bottom inset.
- **Data integrity:** SnapshotSync folds events atomically with the current
  snapshot; the notification relay keeps its cursor and notification update in
  one critical section, resets the cursor on a redial/daemon restart, and
  ignores older event/snapshot cursors. Credential saves are serialized per
  view model so an older alias cannot be resurrected, and Direct URLs reject
  paths/queries that REST and WebSocket would resolve differently.
- **Reorder:** a rejected Home/Terminal move that lands mid-drag is held and
  applied when the finger lifts; the Core workspace move uses the same
  `(space_order, min tab_order, index)` tie-breaker as the snapshot.
- **Daemon:** Core event lines are scanned from the new tail and capped at
  16 MiB; a completion read-mark stores the completion identity (seq/turn) so
  the next DONE on the same pane is not hidden, independent of subscribers.
  `stream.ready` uses the cursor captured with its subscription; shutdown
  rejects new attaches and keeps registry entries until teardown; terminal
  teardown keeps its entry until the close reason is set; `restore_cols=0`
  remains distinct from "learn the layout".
- **JNI:** PNG dimensions are checked before allocating pixels and the
  Bitmap+jintArray+output allocation has a 256 MiB byte budget; a failed
  `NewIntArray`/`GetPixels` clears the exception and recycles the Bitmap. The
  per-cell text buffer still reserves the JNI maximum and marks truncation.
- **Installer/deploy:** source fallback ignores the temp directory; an existing
  unit is restarted only when it was active and the restart result is checked;
  a failed service start now exits non-zero; deploy health uses the bearer
  token (env or unit) and parses the top-level `ok`/`herdrConnected` JSON flags;
  the notification tap goes through a non-exported proxy instead of the
  exported launcher activity.

### Release-gate fixes (fifth pass)

- Deploy reads the post-restart token from `/proc/<pid>/cmdline` with an
  argv walk that preserves `--token=` prefixes, and sends the Bearer header via
  stdin `-H @-` so literal quotes/backslashes are not curl-config syntax.
- Notification events post their transitions immediately; the initial seed
  only retracts a different persisted status and never re-posts the live one.
  Per-pane `lastAlertSeq` suppresses a late duplicate DONE/BLOCKED that was
  already announced by snapshot reconciliation, while a genuine later
  completion still posts. The map is cleared with the cursors when the
  connection epoch changes, so a daemon restart with lower seq still alerts.
  On epoch change the per-pane notified seqs are rebased to -1 while statuses
  remain for retraction; an unchanged snapshot adopts the new seq without
  pretending it announced an alert, so a late DONE after a snapshot that
  overtook events still posts once. Stale non-alert events are ignored.

### Release-gate fixes (fourth pass)

- Deploy resolves the bearer from the running service's `/proc/<pid>/cmdline`
  and `/proc/<pid>/environ` after restart, so systemctl's lossy ExecStart text
  cannot break quoted `;`/apostrophe tokens.
- Notifications before the first seed record state but suppress posting; the
  seed emits the single authoritative transition, preventing DONE/BLOCKED
  reposting during initial sync.

### Release-gate fixes (third pass)

- Terminal create-tab rechecks the target generation after the snapshot wait.
- Deploy parses ExecStart with shlex (quoted `;`/`'` preserved) and chooses the
  last `--token`, matching the daemon's parser.
- Notification seed retracts only the old status id.
- TUI attach waits up to 1.5s for the controller's first successful record, so
  a 750ms busy failure is not acknowledged as Attached.

### Release-gate fixes (second pass)

- Core where ids escape both components unambiguously.
- TUI waits for the first controller record before geometry Ready; prelude
  history metadata survives into Attached.
- Terminal create/close/move/rename/read-ack check the target generation after
  success before navigating, so an old host response cannot open a same-id pane
  on the new host.
- Deploy parses the first `--token` (including values containing `--token`).
- Notification persistence is versioned (`v2;`) so old `%3B`/`%2B` values are
  read literally; seed retracts only the old status notification id.
- CI startup stale cleanup is not retried by the EXIT trap; installer doubles
  `%` for systemd.

### Release-gate fixes

- **Core ids:** where-derived tab ids escape `%`/`:` in both components, so
  `(one:two, Same / Tab)` and `(one, two:Same / Tab)` no longer collide.
  Terminal rail create/move commands also carry the row generation.
- **Attach readiness:** the TUI waits up to 600 ms for the controller's first
  successful record (not just process life); the history prelude's metadata is
  carried through Attaching into the geometry Ready.
- **Terminal state:** the prelude → geometry transition preserves
  historyRows/historyTruncated/historyError/visibleOk.
- **Deploy:** quoted `--token` and Environment values are parsed, single quotes
  stripped, and a pre-restart 401 warns instead of blocking; post-restart health
  is authoritative.
- **CI:** startup stale cleanup is not retried by the EXIT trap when it fails.
- **Installer:** `%` in ExecStart/Environment is doubled for systemd.
- **Notifications:** seed retracts only the old status's notification id; the
  persisted map uses %3B/%25 escaping so old `+`/`%` values still load.

### Seventh deep-review pass

- **Target identity:** `HerdrClientState.targetGeneration` is published with
  the snapshot; Home/Terminal UI uses that value for menu actions instead of a
  fresh read. Old Terminal routes stay unresolved after a host switch.
- **Core IDs:** fallback tab IDs include the workspace ID, so equal `where`
  labels in different spaces no longer close/rename/focus the wrong tab.
- **Daemon shutdown:** a 6s forced-exit watchdog bounds Axum's wait for wedged
  REST/Core handlers.
- **TUI attach:** the history prelude is kept separate from geometry Ready; a
  geometry Ready no longer clears history/visible diagnostics.
- **Notifications:** seed only retracts an alerting old status, so a live DONE
  is not dismissed; persisted keys/values are URL-escaped, preserving `;` in
  workspace IDs; Core `missed` snapshot failure closes the stream.
- **Reorder:** a structural list change during a drag invalidates the old drop
  index and resyncs instead of sending a wrong move.
- **UI/install:** the long-press chip exposes `selected`; the systemd unit
  quotes ExecStart/Environment values; Direct bearer resolution uses the same
  active-profile fallback as the reconnect key.
- **Cache:** refresh revision advance is inside the payload write lock.

### Sixth deep-review pass

- **Target-safe rows:** Terminal rail tab/workspace menus and read-ack carry
  the generation captured when the row was selected; a host switch never
  re-resolves the old route by ID. Home menu targets carry the same generation.
- **Attach truth:** the TUI history prelude (cols=0) is no longer reported as
  `Attached`; only the geometry Ready is. `UiFocusState` suppresses alerts only
  while the backend is actually `Attached`.
- **Core/paths:** path segments percent-encode spaces (`%20`, not `+`); a Core
  rename freezes where-derived IDs; a partial multi-pane close returns failure.
- **CI:** `/proc/<pid>/exe` accepts the ` (deleted)` suffix after a rebuild;
  an explicit teardown failure is not retried by the EXIT trap.
- **Deploy:** `--token` wins, EnvironmentFiles override Environment in systemd
  order (empty clears), shell fallback last.
- **Notifications:** same-seq snapshot reconciliation, a 1024-event buffer, and
  EventSocket closes on overflow instead of silently dropping; persisted
  `status|workspace` splits only at the first pipe; seed retracts a persisted
  DONE even when a live event is present; storage failure blocks writes.
- **Settings:** backend-mode edits share the profile lock; host/port TOFU
  approval is rejected if the profile moved since the probe.
- **Cache/reorder:** revision check, payload install, and revision advance share
  one lock; a drag releases only its own row's state and requires a real UP.

### Fifth deep-review pass

- **Target generations everywhere:** tab close/rename, workspace rename/close,
  and pane read-ack now carry the generation that produced the row all the way
  to `endpointFor`. A profile/transport switch clears the old Terminal route
  instead of reopening a same-id pane on the new host, and Home menu dialogs
  keep the generation captured when they opened.
- **Deploy token:** `--token` wins, service Environment/EnvironmentFiles follow
  systemd order (later files win, empty assignment clears), and only then the
  shell override. The bearer still travels through curl stdin config.
- **CI:** stop/delete failures keep `.ci-owned`; a marker-less startup now fails
  when `session list` is unreadable; the daemon binary is matched by
  `/proc/<pid>/exe`, not basename.
- **Shutdown/attach:** all child writes race the shutdown notification; TUI
  does not send `ready` until the controller survives a short stability check;
  read-ack re-evaluates a DONE pane when a slow attach finally becomes
  `Attached`.
- **Notifications:** a removed pane retracts the transient DONE, and event
  buffers are 1024 instead of 64. AppContainer waits for DataStore load before
  treating settings as a target change.
- **Core/cache:** a raced full refresh returns an error instead of a false
  success; Core rename freezes where-derived IDs before changing `where`; a
  Core read-ack broadcasts `snapshot.required`; reorder labels remove absent
  originals with an empty label.
- **Reorder:** a disposed row releases drag state in `finally`.
- **Settings:** a profile save keeps non-dialog daemonHost/useTls/backendMode
  from the current record.

### Fourth deep-review pass

- **Notification ordering:** separate event and snapshot cursors let a short
  `working -> done -> working` still reach the event path; a newer snapshot is
  re-applied after the event so no stale DONE alert remains. The persisted map
  is not written until the first seed merges it, so a live transition cannot
  erase other panes.
- **Connection epoch publish:** event emission and epoch validation share one
  lock; a late GET is captured with its endpoint/epoch and discarded after a
  redial.
- **Core:** attach no longer pre-acks a DONE; Core read-acks broadcast a
  `snapshot.required` control event so other phones refetch. Reorder labels
  preserve an absent label as an empty removal, not `i64::MAX`.
- **CI:** `server stop` and `session delete` failures propagate and keep the
  `.ci-owned` marker. CI compares the live signature only after the explicit
  private teardown.
- **Shutdown:** Herdr RPCs and child writes race the shutdown notification; all
  sessions share one 5s deadline.
- **Input/reorder:** Compose consumed cancel events are rejected in the Initial
  pass for keys, the pager, and reorder handles; a real UP still commits.
- **Deploy:** `EnvironmentFiles` override `Environment` in order, matching
  systemd; the bearer is passed via stdin `--config -`, never argv.
- **Target changes:** an old Terminal route no longer falls back to the new
  host's focused workspace; Home close/move commands are dropped if the target
  generation changed before the coroutine runs. A profile host/port change
  clears the old known_hosts alias so TOFU can approve the new endpoint.

### Third deep-review pass

- **CI ownership/teardown:** process matching now compares exact argv
  boundaries (binary basename, `--bind`, `--herdr-socket`), so a
  `herdr.sock.other` process is never stopped. Session stop/delete failures or
  an unreadable session list keep the `.ci-owned` marker and fail the run; the
  live signature is taken after all private teardown.
- **Core ordering/shutdown:** reorder uses the same aggregated
  `(space_order, tab_order, index)` keys as the snapshot, serializes concurrent
  reorders, restores every target on an uncertain write, and Core attach no
  longer marks a DONE read before the WebSocket succeeds.
- **Connection epoch:** target invalidation and every redial advance the epoch
  immediately; fold, GET result, and emit are guarded by both generation and
  epoch, so a late REST response or queued old event cannot overwrite a new
  daemon's snapshot.
- **Shutdown:** one shared 5s deadline for all sessions, child writes bounded,
  and the select no longer prioritizes input over child output/resize.
- **Notifications:** seed compares live event seq, retracts a DONE that a newer
  snapshot shows as working, and avoids re-notifying the same DONE after a
  persisted WORKING seed.
- **Credentials:** edits are update-only (a deleted profile is not re-created),
  the current record supplies host-key/other fields, and TOFU/save/remove share
  one repository lock. Secret-only edits fail for a deleted profile.
- **Deploy:** token priority matches the daemon (`--token`, then Environment,
  then `EnvironmentFiles` plural); curl receives the bearer through stdin
  `--config -`, never argv; the success body reuses the authenticated response.
- **Input/reorder/URL:** a Compose pointer cancel (pressed=false, consumed) no
  longer sends a key; a long-press without motion adopts the latest remote list;
  Direct errors no longer echo a legacy URL's userinfo.

### Second deep-review pass

- **Core completion identity:** the read mark now includes Core's `status_ms`
  (plus `turn`/completion seq), so an `idle -> done` completion in the same turn
  is not hidden behind the pane's previous read. Core event lines are length
  checked before parsing and the subscribe/hello lines use the same 16 MiB
  bound.
- **Connection epochs:** every connection attempt gets an epoch; snapshots and
  stamped events carry it, and notifications reject queued events from an older
  attempt. This resets the seq cursor after an automatic reconnect where the
  daemon restarted at seq 0.
- **Notification correctness:** all status events (including working) update the
  dedupe map, and snapshots diff against the last notified status instead of the
  previous snapshot, so a fast done -> working transition retracts its alert.
- **Credential/profile serialization:** saves, removals, and TOFU host-key
  approval share the repository profile lock; a removal can no longer be
  resurrected by a save waiting on Keystore I/O.
- **Daemon shutdown:** shutdown wakes sessions through `Notify` instead of the
  bounded input queue, bounds child writes, and keeps registry entries until
  teardown; an already-closed session is not waited on again.
- **Core reorder:** workspace order uses the same minimum
  `(space_order, tab_order, index)` key as the snapshot, and a failed label
  update restores already-applied labels instead of leaving a half order.
- **CI isolation:** an unreadable `herdr session list` is an isolation failure,
  the live signature is checked again after private daemon/session teardown, and
  process diagnostics redact bearer arguments. The installer writes the
  service unit with `0600`.
- **Deploy/URL/input/JNI:** deploy resolves a bearer from `Environment`,
  `EnvironmentFile`, or `ExecStart --token` and refuses an unauthenticated 401
  before restart; a legacy Direct URL with a path/query is rejected at connect
  time; a cancelled key gesture no longer types; and PNG `PushLocalFrame`/
  `Options` allocation failures clear their JNI exception.
- **Auth:** bearer comparison keeps the full length difference, rejecting
  prefixes whose lengths differ by a multiple of 256.

## 2.2.0 — 2026-11-20

- Home uses the expressive large-title header (`displaySmall` 36/44 +
  `titleMedium` 16/24), 28dp workspace cards with 20dp padding, 12dp status
  dots, `bodyMedium` paths and filled tonal chips. Reordering keeps the
  explicit handle in the phone list; the tablet grid no longer renders a dead
  handle.
- The bottom navigation keeps the official full-width `NavigationBar`:
  active icon filled, inactive icon outlined, and the existing
  `alwaysShowLabel = false` selected-only label (the floating caption).
- Settings groups use `surfaceContainer`. Terminal rails use the expressive
  chip shape (12dp unselected, measured-height full pill selected).
- Extra keys are 40dp with 12dp square corners. PGUP keeps the downward-flick
  PGDN action (`PGUP↓`) and now exposes a "Page down" accessibility action for
  TalkBack/switch access; the flick no longer competes with the panel pager.
- Panel swipes only switch pages when Swipe behavior is PAGE; NONE and
  SCROLLBACK no longer trigger the pager.
- Midnight keeps its AMOLED fallbacks: hairline chips/cards/keys, a visible
  nav indicator and a nav-bar divider, and a primary FAB container.
- Status/navigation-bar icon appearance now follows the resolved app theme,
  not only the system setting, so Dark/Midnight no longer hides the status bar
  icons on a light system.
- `tools/ci.sh` now logs the device screen and fails an emulator smaller than
  1080x1920px / 360x640dp, because the smoke uses fixed pixel swipe
  coordinates. HerdrTest was booting at 320x480@160 (HVGA) and is now
  1080x2400@420 (411x914dp); the debug override poll waits past the activity's
  30s DataStore timeout and the activity writes both state files.

## 2.1.0 — 2026-11-18

- **TUI host scrollbar support.** The daemon now subscribes to Herdr
  `pane.scroll_changed` for every known pane, folds the current
  offset/max/viewport into its snapshot, and broadcasts a foldable
  `pane.scroll_changed` event. The client no longer waits for a full snapshot
  to notice a desktop scrollbar/wheel move.
- **No more jump after a desktop scroll.** Relative phone scrolls resolve the
  live `pane.get` offset on every coalesced gesture instead of trusting a cached
  base from before the desktop moved. The app paints a host scrollbar overlay
  (replacing the local prelude bar in REMOTE_HOST mode) and supports dragging it:
  a new `host_scroll` terminal record sets the absolute `pane.scroll` offset,
  coalesced to 40 ms.
- Scroll position is included in the 60 s consistency hash, so a dropped scroll
  event is healed by the tick instead of leaving a stale thumb forever.
- Added `install.sh`: `curl -fsSL .../install.sh | sh` resolves the latest
  release, installs `herdr-mobile-daemon` under `~/.local/bin`, enables a
  systemd user unit on loopback, and falls back to a tagged source build when
  no prebuilt asset exists. `tools/package-daemon.sh` builds the release
  tarball.
- Tests cover scroll-event folding, host-scroll control framing, daemon cache
  updates, and the existing API 29/33 + 32-bit matrix.

## 2.0.27 — 2026-11-17

- Fixed the attach storm on a missing/stale pane (reported as `w1:p1`). The
  reconnect watchdog reset its retry budget on every brief `Attached` emission,
  so a pane that attached and immediately dropped reconnected forever. The
  budget now survives short-lived attaches and resets only after a stable 5 s
  session.
- A detach whose reason contains `not found`/`pane_not_found` is treated as
  permanent: no automatic retry; the screen reports `Cannot attach: …` and
  leaves recovery to the user instead of hammering the daemon.
- Added tests for the bounded retry budget and permanent missing-pane detach.

## 2.0.26 — 2026-11-16

- Read acknowledgment is now pane-scoped. The daemon exposes
  `POST /v1/pane/{id}/focus` (Herdr `pane.focus`; core updates its local read
  set) and refreshes the cached snapshot before returning. The app calls it
  instead of `tab.focus`, which only activates the tab's last pane and leaves a
  finished split sibling unseen.
- A DONE transition for the pane already on screen now triggers read-ack
  immediately. Previously the ack ran only in `openTarget`, so an agent that
  finished while its terminal was open stayed DONE until the screen was
  recreated; this matches the OMP "reopen" report.
- Read-ack is deduplicated per done episode, throttled, cancelled on pane
  transitions, and targets exactly the pane ID rather than the tab. Verified on
  the emulator against a synthetic OpenCode done pane: opening and a visible
  working→done transition both became idle.
- Added regression tests asserting pane focus (not tab focus) on a visible done
  transition and on the read-ack helper.

## 2.0.25 — 2026-11-14

- Terminal cell backgrounds are coalesced into contiguous runs instead of one
  `drawRect` per colored cell per frame. The pixels are unchanged; a colored TUI
  region now costs one rectangle per run rather than hundreds per repaint.
  Verified with the on-device scroll/tab smoke.

## 2.0.24 — 2026-11-14

- TUI event sequencing now has one sequencer. `EventBus::publish` allocates the
  seq, updates the cursor, broadcasts and stamps the snapshot under one mutex;
  concurrent update/structural producers can no longer publish duplicate or
  out-of-order numbers. `snapshot.required` still deliberately does not stamp
  the stale snapshot. Added a 32-producer concurrency test.
- The terminal view paints only the engine's dirty rows (union of the previous
  and current cursor row), instead of re-blitting every pixel for an echoed
  keystroke. Full repaint is retained for selection, images, smooth transitions,
  scrollback and geometry changes. Bounds are extracted and unit-tested.
- Attachment-scoped jobs (attach, read-ack, queued flush, reconnect retry,
  scroll coalescing, resize settle) are cancelled through one lifecycle helper,
  so a stale ack/scroll/resize can no longer outlive the pane transition.
- CI adds a runtime compatibility matrix for API 29 and API 33 (notification
  permission and ServiceCompat/foreground-service paths) and verifies the
  32-bit release APK contains the armeabi-v7a native library.

## 2.0.23 — 2026-11-12

- The terminal inbound lane is bounded (1024 records). OkHttp callbacks cannot
  suspend, so the old unlimited lane could pin memory when the UI stalled;
  overflow now closes the socket for a clean `lagged_resync` reattach instead
  of silently dropping bytes or growing without limit.
- Notification seeding no longer overwrites a live transition with the older
  snapshot state, and alerts disabled at startup (monitoring/category off) are
  retracted as soon as the persisted map is seeded.
- Removing a host profile deletes its keystore aliases only after the profile
  write succeeds, so a failed DataStore edit no longer loses credentials for a
  profile that still exists. Probe closes are bounded to 5 s.
- The Home create-workspace dialog and its fields survive rotation.
- Verified with `tools/check.sh`, the on-device smoke, and CI (Kotlin, Rust,
  release/lint/JNI, emulator).

## 2.0.22 — 2026-11-12

Twenty-plus audit fixes across the link, terminal, settings, notifications and
daemon. Each item has a narrow test or a reproducible path.

Connection and recovery:
- An explicit `stop()` is now sticky: a network-regain `kick()` no longer
  resurrects a link the user turned off (background monitoring off + cached
  process), and `publishStopped()` collapses a failed first dial from
  `CONNECTING` to `IDLE` instead of leaving a permanent "Connecting…".
- A bootstrap alone no longer resets reconnect backoff; only a delivered event
  proves the long-lived event stream healthy. An accept-then-close proxy would
  otherwise redial every 500 ms forever.
- Foregrounding with monitoring off starts/kicks the link again, so rotation or
  returning from another app no longer leaves Home on a dead stale cache.
- `/v1/events` has a 1024-slot buffer instead of silently dropping frames at 64;
  malformed `type`/`seq`/ready fields are ignored instead of crashing the socket
  callback. Closing emits one Closed signal, not two.
- Concurrent snapshot refetches are serialized and an older response can no
  longer overwrite a newer snapshot (seq monotonicity).

Terminal:
- Input typed while the stream is detached is queued and flushed on reattach
  instead of vanishing into a closed socket; the 40 ms scroll coalescer is
  cancelled/retargeted on a pane switch so the first gesture is not swallowed.
- The reconnect overlay is rendered again (last frame stays under "Reconnecting…
  / Retry"); the dead 2-second snapshot poll on every agent scroll was removed.
- A read-ack focus call is cancelled when the user switches panes, preventing a
  late focus steal; cancellation during `awaitConnected` is propagated instead
  of being reported as a timeout.
- Wheel accumulation keeps sub-notch remainders for fractional/agent strides and
  mouse-tracking; a fling over the cap still drops its excess. Generic-motion
  page scrolling flushes one notch (mouse/trackpad had no ACTION_UP).
- Raw SGR wheel bytes are restricted to verified wheel agents; the explicit
  Mouse wheel mode no longer types escape sequences into a shell.
- Extra-keys buttons expose a real accessibility click action; the reconnect
  overlay, box-drawing `RectF` reuse, cached accessibility text, volatile frame
  post flag and `@Immutable` terminal state cut per-frame/per-gesture work.
- OpenCode's global `cli.json` (`scroll.speed`, JSONC) is stamped on the pane;
  the config path is resolved once per process and the last parsed value is
  reused until the file's metadata changes.

Settings and app lifecycle:
- Scroll mode is persisted (it silently reset to Automatic on every restart).
- Settings load ordering is fixed (`loaded`/corrupt-raw initialized before the
  loader), initial read failures retry before an edit overwrites the file, and
  writes persist before publishing so a failed DataStore edit cannot leave the
  UI/transport on unsaved values.
- Active profile removal promotes a survivor instead of showing no radio while
  connecting to the first profile; profile ports are validated (1–65535) instead
  of silently becoming 22/8765; target keys ignore cosmetic label edits.
- POST_NOTIFICATIONS is asked once per install (not on every cold start), posted
  alerts retract when their category or monitoring is disabled, and the monitor
  foreground-service type is only SPECIAL_USE on API 34+ (passing it on 29–33 is
  rejected by the platform). STOP clears the running flag synchronously so an
  immediate OFF→ON toggle re-arms.
- Home command errors are laid out above the list (they had zero height), clear
  after 5 s or on success, and IDLE/error-without-snapshot renders the offline
  body instead of "No workspaces".

Daemon and release:
- Health no longer deep-clones the snapshot just to test presence; status
  folding no longer deep-clones it either. Snapshot+ping overlap with a 2 s ping
  bound, structural resubscribes skip a duplicate refresh, and the first 60 s
  reconcile no longer runs immediately.
- Synthetic event sequence reservations are unique; update-available events
  stamp the reserved seq into the snapshot; resize coalescing clears a stale
  pending geometry; core tab numbering uses max+1, focus resolves `where`-only
  tabs, resize only reports success after a frame, and error mapping no longer
  treats "deliver" as "live".
- Update runs have a timeout/kill-on-drop and preserve known version/notes on a
  transient manifest failure; a successful update re-reads the Herdr version.
- Release builds validate `-Pabi`, require signing with `-PrequireSigning`, use
  a phone-safe Direct default (127.0.0.1, no 10.0.2.2 cleartext exemption), and
  the benchmark build includes x86_64. CI now builds release/lint/JNI, scripts
  restore the IME setting, only CI uninstalls the debug app, smoke labels are
  unique, JNI checks fail clearly on missing artifacts, and daemon deploy rolls
  back on a failed health check.

## 2.0.21 — 2026-11-12

- OpenCode TUI scrolled 3x too fast: its `packages/tui/src/util/scroll.ts`
  defaults to `CustomSpeedScroll(3)`, so one SGR notch moves three dialog rows.
  The daemon now reads OpenCode's global `cli.json` (XDG or
  `OPENCODE_CONFIG_DIR`, JSONC comments/trailing commas supported) and stamps
  `scroll.speed` onto the pane as `wheelRowsPerEvent`; the client sends one SGR
  notch per that many rows. Missing config or `scroll.acceleration` falls back
  to the matrix default (OpenCode 3). Fractional speeds are supported in the
  view's wheel accumulator; Settings → Scroll mode remains the escape hatch.
- CI: tool scripts are executable in git and the smoke uses `bash tools/ci.sh`;
  a fresh emulator gets POST_NOTIFICATIONS pre-granted so the dialog cannot
  cover Home.
- CJK keyboard raises the IME only after the draft field reports focus, and
  retries focus with the terminal View cleared. Smoke accepts the documented
  shortcut-panel fallback when the IME refuses the field.


## 2.0.20 — 2026-11-12

- Core terminals without `herdr.space`/`herdr.tab` labels now group by their
  `where` label instead of each becoming its own workspace. This removes the
  duplicate "herdr-mobile" cards and makes close/rename/focus/ordering work for
  hc-created terminals.
- App launch with no explicit target opens the focused workspace/tab/pane
  (core focus or TUI focus) instead of the first workspace.
- Rust test for where-only grouping; TargetResolution test for focused start.

## 2.0.19 — 2026-11-12

- Updated the vendored engine to **libghostty-vt 1.0.1** (from 1.0.0) for all
  four ABIs. The 1.0.1 headers differ only in `version.h`, so the C ABI and the
  JNI bridge are unchanged; rebuilt engine, JNI symbol checks and smoke verify
  the new static libraries.

## 2.0.18 — 2026-11-11

- Core attach already applies the requested geometry; the Android view then
  sent the same size after its first layout. That redundant SIGWINCH made OMP
  replay its transcript a second time (black screen, then visible, then a
  second replay). The daemon now skips a resize identical to the attach size.
  Verified OMP reopen: 14 half-second captures, no layout/black/thrash.
- The 180 ms resize debounce from 2.0.17 remains; core read-ack/focus and
  ordering from 2.0.16/15 are unchanged.

## 2.0.17 — 2026-11-11

- Core PTY resizes are debounced (180 ms). Opening a tab emitted several
  layout sizes (insets/IME/panel), and each one made OMP replay its whole
  transcript, producing the seconds-long thrash while scrolling and the
  sluggish Codex/OpenCode redraws after recent updates. Only the settled
  geometry is sent now.
- Verified: core OMP with a transcript scrolls and stays put (no movement
  change after 4 s); a synthetic core `done` pane opened in the app becomes
  `idle` (read acknowledgement) and the snapshot focus follows the attach.

## 2.0.16 — 2026-11-10

- Core read acknowledgement: a focused core pane reporting `done` is presented
  as `idle` to this daemon (core has no seen/focus RPC); the mark clears on the
  next status event. The app refreshes after the focus call so the notification
  retracts and the status dot clears. Verified with a synthetic OSC 7501 done
  pane and a live tab.focus.
- Slow-link detach no longer replaces the terminal with an error screen: the
  last frame and scroll position stay visible under a Reconnecting/Retry
  overlay. This removes the screen/error flashing on flaky links.
- Wheel/arrow input from a fling is capped per frame (remote wheel apps kept
  scrolling long after the finger stopped; mouse-tracking panes now use a
  3-row notch).
- Core focus/ordering from 2.0.15 retained.

## 2.0.15 — 2026-11-10

- Core focus is now a first-class daemon state: attaching a core terminal sets
  the focus, `POST /v1/tab/{id}/focus` resolves and stores it, and translated
  snapshots mark the focused workspace/tab/pane plus `activeTabId` and the
  top-level `focus` block. A closed pane no longer leaves a dangling focus.
- Core workspace/tab ordering via `herdr.space_order` / `herdr.tab_order`
  labels (from 2.0.14) is documented and covered by tests.
- Core scroll remains engine-owned (`AUTO`): shells, OMP and pi were exercised
  on real core terminals; codex/opencode were attached and focus/input checked
  (their landing screens have no transcript to scroll).

## 2.0.14 — 2026-11-10

- Core backend: workspace and tab reordering now works. Core has no ordering
  RPC, so the daemon writes `herdr.space_order` / `herdr.tab_order` labels and
  `translate_snapshot` sorts spaces/tabs by them (labels persist across core
  restarts). Verified against a live herdr-core session.
- Core scroll: panes without `Pane.scroll` (all core panes) are now routed to
  the engine's AUTO instead of agent page/wheel keys. The core stream carries
  raw bytes, so libghostty owns the real modes and 2000 rows of local
  scrollback; OMP's append-only transcript scrolls again.
- Debug builds can select the backend in the CI/daemon override activity
  (`--es backend core|tui`); the smoke forces TUI so the developer's core
  setting is never mutated by a test run.
- Core labels must be strings: order values are written as strings (core
  rejects integer label values).

## 2.0.13 — 2026-11-09

- Alt screen scroll follows DEC 1007 (alternate scroll): arrow keys are sent
  only when the program enabled it. Previously any alt-screen pane got
  DPAD_UP/DOWN, which moved vim's cursor instead of scrolling.
- Core backend reports the attach `historyRows` it actually received instead
  of a hardcoded 0.
- Documented the existing scroll seeding (recent ≤1000 rows) and the
  server-decided routing for unknown panes; added tests for 1007 and the shell
  process gate.

## 2.0.12 — 2026-11-09

- Scroll facts are refreshed on demand: a known-agent pane that started with
  `max_offset_from_bottom = 0` (OMP landing, fresh shell) and later gained host
  scrollback no longer stays stuck on page/wheel routing. The first gesture
  triggers a snapshot refresh (2 s debounce); the next one host-scrolls.
- The "Connection target changed" state now offers a **Choose a session**
  action instead of trapping the user in a terminal screen with no rails.
- Retains 2.0.11's direction revert and the shell page-key fix.

## 2.0.11 — 2026-11-08

- Reverted the 2.0.10 touch-direction change. Finger up scrolls toward live
  again (natural touch scrolling); the inversion was not requested and made
  the gesture feel wrong.
- Kept the real scroll fixes: plain shells never receive PgUp/PgDn (daemon
  foreground-process check), host row scroll wins over manual key modes, and
  unknown/no-agent panes route through the daemon.

## 2.0.10 — 2026-11-08

- Scroll direction: finger **up** now scrolls toward older history on every
  route (local engine, host rows, wheel, page). The old mapping sent a finger-up
  drag toward live, so a pane sitting at the bottom (OMP) appeared frozen.
- Plain shells never receive PgUp/PgDn: a pane with no agent and no host
  scrollback is routed through the daemon, which checks the foreground process
  (`pane.process_info`) and refuses to type page keys into bash/zsh/fish/etc.
  (their inputrc bindings made page keys recall old commands). Full-screen apps
  such as less still page normally.
- Manual "Mouse wheel"/"Page keys" modes keep using the host's row-accurate
  scroll when the pane reports host scrollback; the explicit key transports
  only apply to panes without it. This makes OMP scroll under any mode.
- Smoke waits for the IME before typing into the CJK field (flake).
- Tests: unknown/no-agent routing is server-decided; host scroll wins over the
  explicit key modes.

## 2.0.9 — 2026-11-08

- Input panel: the KEYS/CJK control is gone; switching is swipe-only again.
  Horizontal swipes now work when they start on a key button (the keys' touch
  targets used to swallow the drag): the panel claims horizontal drags before
  the buttons and animates to the next page on release. Taps still reach the
  keys.
- Rail chips: selected workspace and tab chips use the same 1 dp primary ring;
  pixel measurement confirmed identical thickness/colour (no visual change).

## 2.0.8 — 2026-11-07

Audit pass over terminal, UI, notifications, daemon and protocol seams.

- Terminal rendering: Kitty placements now come from the render-state image
  list (virtual/Unicode-placeholder images included) with Ghostty layer and
  z-index ordering; below-background, below-text and above-text images are
  composited in the right pass, frames with images are forced full so text is
  not stamped over on partial updates, deleted images no longer ghost, and
  animations schedule a render instead of repainting cached bitmaps.
- Input: mouse-tracking panes get primary-button press/drag/release (not a
  synthetic tap at the up position); SGR 1016 reports surface pixels; per-agent
  wheel stride is accumulated (pi 2 rows, qwen 3) and reset on direction or
  route changes; explicit "Mouse wheel" mode no longer degrades to PgUp/PgDn;
  long-press selection keeps the word prefix and never adopts a stale anchor;
  a horizontal swipe starting on PGUP↓ goes to the pager instead of paging.
- Lifecycle: terminal release outlives `viewModelScope` (closed screens no longer
  leak sockets); auto-reattach is cancelled when backgrounded; CJK draft flows
  are cached instead of leaking an Eagerly-shared flow per keystroke.
- Notifications: transition decisions are serialized across the event and
  snapshot paths; alerts for the pane currently on screen are suppressed;
  tapping a DONE alert without a link no longer destroys it (autoCancel off);
  host switches cancel the old host's shade entries and reseed silently;
  notification deep links survive activity recreation.
- UI: the input panel has an explicit KEYS/CJK switch (swiping over key buttons
  is unreliable); the "panel swipe" setting is honored; session reordering uses
  live callbacks instead of the first composition's workspace; rename/close and
  profile dialogs survive rotation; CJK fallback does not select a disabled
  panel page.
- Daemon/protocol: `closed` reasons are parsed (lagged_resync retry works);
  attach failures are replayed to bridges that subscribed after the failure;
  core RPCs have timeouts; closing an unknown tab/workspace returns an error;
  reserved broadcast sequences are published in the snapshot (no spurious gap
  refetch); live clients receive agent-detection changes; the core socket
  fallback derives the uid from `/proc/self`; the update call gets a 180s read
  timeout instead of failing while the daemon keeps updating (no per-read
  deadline; a drop guard clears the single-flight flag); core event and terminal
  streams have idle timeouts; duplicate tab/workspace closes are idempotent and
  a multi-pane close reports success when most panes closed; `snapshot.required`
  no longer stamps the stale pre-refresh snapshot with the reserved seq.

## 2.0.7 — 2026-11-07

- **CJK page keyboard button**: it focused the terminal, not the CJK field,
  because the panel's `FocusRequester` was never passed to the terminal surface
  (`cjkFocus == null` made the whole branch dead). The button now focuses the
  field and raises the IME; if the field cannot take focus, it switches to the
  shortcut panel and raises the terminal IME. The pager follows external page
  changes, and backgrounding the screen clears field focus so the IME does not
  reopen on resume.
- **Notifications**: the notification identity no longer includes the tab id —
  moving a pane between tabs changed the id and orphaned the shade entry (the
  retraction computed a different id). The relay state dropped its unused tab
  field. Only Done/Blocked alert; Herdr 0.9.x has no failure status.
- **PGUP key label**: "PGUP ↓PGDN" was ellipsized to "PGUP …" in the seven-key
  row; it now shows "PGUP↓" with a full accessibility description.
- **Smaller APK**: release builds are per-ABI (arm64-v8a 3.38 MB, armeabi-v7a
  3.42 MB, down from 5.68 MB universal) and the multi-megabyte BouncyCastle
  Picnic (PQC) resource tables are excluded — R8 strips the classes but not
  those blobs, which the app never uses.
- `showIme()` retries once the view is attached (a tab switch could show the
  keyboard button before the new view existed); the kitty animation tick is
  skipped when no images are displayed.

## 2.0.6 — 2026-11-07

Tab/workspace switching and a batch of engine details.

- **Tab switching was dead**: AndroidView captures its factory on first
  composition, so a backend change created a new controller/session while the
  old (closed) view stayed on screen. The view is now keyed on the controller.
- **Daemon tab creation always stole host focus**: `/v1/tab` hardcoded
  `focus: true`, so creating a tab from a phone moved the desktop's keyboard
  focus (our own smoke caught it). Focus is now a request field, default false;
  the app asks for focus explicitly.
- The terminal view now fills its box (`fillMaxSize`).
- High-throughput output enqueued one main-thread Runnable per socket chunk;
  posts are coalesced per frame.
- Kitty: per-placement x/y pixel offsets are applied, the bitmap cache key can
  no longer collide (id+generation), GRAY/GRAY_ALPHA images decode, and animated
  images schedule their next frame (the missing JNI symbol crashed the first
  frame — now covered by `tools/check-jni.sh`, which verifies every `external
  fun` against the built libraries for all ABIs).
- Long-press selects text first; a link menu only opens when there is nothing
  selectable.
- Sticky CTRL/ALT: syncing handles LOCKED state and no longer clears a latch on
  a fresh controller's initial emission (tab switches kept clearing it).
- Switching tabs no longer closes the keyboard (IME hide keys on a missing
  target, not the transient missing backend).
- Smoke now creates a second tab without focus and asserts that tapping it
  replaces the terminal view.

## 2.0.5 — 2026-11-07

- Kitty inline images are supported again (and now actually work): the 1.0
  graphics API is bridged (placement iterator + decoded RGBA/RGB pixels), the
  image storage limit is raised from 0, and the renderer draws placements above
  the text with a generation-keyed bitmap cache. A 2×2 RGBA fixture is decoded
  and placed in the engine test.
- 32-bit x86 is vendored as well; debug builds ship arm64/armv7/x86/x86_64
  (release stays arm64 + armv7 for size).

## 2.0.4 — 2026-11-07

- The terminal font picker only offers the platform monospace family; the other
  entries never resolved to their named typeface and silently rendered
  monospace, so they promised a font the app could not deliver. Stored values
  are normalised on load.

## 2.0.3 — 2026-11-07

- Selection now uses the theme's selection colour (with a readable contrasting
  text colour) instead of always inverting video.
- The extra-keys sticky latch clears when the engine consumes it on a hardware
  key; the engine and the panel no longer disagree about CTRL/ALT state.
- 32-bit ARM phones can install again: libghostty is vendored for armeabi-v7a
  as well (arm64 + armv7 in release; x86_64 stays debug/emulator only).

## 2.0.2 — 2026-11-07

More terminal-engine correctness fixes from the 50-item audit.

- Engine-originated input (typed keys, IME) no longer bumps the input counter, so
  it cannot trigger a second scroll-to-bottom on every keystroke.
- The inbound replay cache is released after 2 s instead of after the first
  frame: a multi-frame prelude can no longer lose its tail.
- Sticky CTRL/ALT in the extra-keys panel now also arm the engine's hardware-key
  latch; page switches retarget a visible IME between the CJK field and the
  terminal.
- Pinch-to-zoom font size is persisted (debounced, clamped to 8..24) instead of
  resetting on the next attach.
- Scroll routing: an explicit "Mouse wheel" mode falls back to page keys when the
  engine reports no mouse tracking (raw SGR bytes used to be typed into shells);
  "Page keys" accumulates rows and sends at most half a screen per page, flushing
  the remainder at gesture end.
- URL auto-detection is off by default (long-press selects text again; OSC 8
  links still open).
- Unsafe-paste confirmation uses a night-aware dialog instead of the platform
  default; the diagnostics overlay stops polling JNI while the app is stopped.
- The terminal session survives a scrollback-limit change (history is no longer
  dropped by a controller recreate); message sanitisation uses a precompiled
  regex; dead paste helpers removed.

## 2.0.1 — 2026-11-07

Regression fixes for 2.0.0, found by comparing against the 1.9.1 build on the
same fixture.

- **Chrome invisible**: the terminal view painted its background outside its
  own bounds (Compose's AndroidViewsHandler does not clip child views), blacking
  out the workspace/tab rails and clipping the first grid row. The view now
  clips itself in `onDraw`.
- **Box-drawing gaps restored**: system-font box glyphs stop short of cell
  edges. The cell-exact stroke painter (light/heavy/double rails, double and
  rounded corners) is back inside the renderer.
- **Font size**: the engine now gets the user's typeface/size before the
  session attaches; previously the library default (14sp, default palette)
  rendered first and then resized, which read as a size change. Grid parity
  with 1.9.1 at the same setting (56×44 in the reference layout).
- Tapping the terminal no longer raises the IME (manual summon only), bell
  haptics fire once (library's own haptic disabled), and DEC 2031 colour-scheme
  reports follow the selected theme.
- Long-press selection no longer clears instantly: 1.0's row-selection query is
  a sized struct (`GHOSTTY_INIT_SIZED`).
- Release APK: arm64-v8a only and compressed native libraries (8.7 MB → 4.6 MB);
  startup baseline profile regenerated for the engine.
- `tools/smoke.sh` now fails when the rails are not painted; the engine module
  guards clipping and the box table with tests.

## 2.0.0 — 2026-11-07

The terminal engine is now **libghostty-vt 1.0**, the same VT engine Herdr runs
server-side. The Termux fork, its reflection layer and the scroll probe are gone.

- Engine: vendored MIT Android view/session library on libghostty-vt 1.0.0
  (arm64-v8a + x86_64 static builds, 16 KB pages), JNI ported to the 1.0 ABI.
  Covers parsing, render state, modes, key encoding (Kitty protocol), paste
  encoding and selection.
- Removed: `terminal-view`/`terminal-emulator` modules, `TermuxInternals`
  reflection, `ScrollProbe`, `ScrollRoute`/`ScrollMath`, `ImePolicy`,
  `WheelBurst`, `TermuxViewport`, box-drawing overlay, AgentMatrix scroll
  probing, paste payload heuristics.
- Scroll routing is explicit from the pane report: host rows (coalesced 40 ms),
  wheel reports for mouse agents, one page per gesture for other full-screen
  panes, local scrollback otherwise; the user's scroll-mode setting still wins.
- Input/paste/selection: engine encoder and formatter; long-press selects a
  word, drag extends, ActionMode copies. Bracketed paste follows the real mode.
- Fixed during the port: 1.0 sized-struct queries (row selection was silently
  empty until initialised with `GHOSTTY_INIT_SIZED`), style flags, grapheme
  buffers, cursor style enum, formatter API.
- Not supported: Kitty inline images (the 1.0 graphics API was redesigned and
  this client does not render them — Termux did not either).
- Verified: engine instrumented proof (bytes → pixels → keys), 11 terminal UI
  tests, full isolated smoke (`CI OK`), typed input over the real keyboard path.
## 1.9.1 — 2026-11-06

- JVM tests for the hardware-key path (`TermuxViewClient`): arrows, DEL, CTRL
  chords, ALT prefixes, printable bytes, and shift+PgUp routing. Robolectric
  runs the real Android `KeyEvent`/`TerminalView` without a device, closing the
  last gap where a key could silently fall through to Termux's dead local PTY.
  196 JVM tests now.

## 1.9.0 — 2026-11-06

Performance + platform-quality pass on top of the 1.8.0 refactor.

- Typing: `inputTick` is collected as a flow inside the terminal surface, so a
  keystroke no longer recomposes the screen. The `AndroidView` update pass now
  applies scheme/font/cursor only when they changed, caches the typeface, and
  binds the view's callbacks once (`rememberUpdatedState`). Side effect fixed:
  `mColors.reset()` ran on every recomposition, wiping OSC 4/10/11 palette
  changes from apps while typing.
- Transport: queued outbound frames are coalesced per turn (keystroke bytes
  concatenate, typed text becomes one `input.text`, control records flush
  first), so key rolls and IME bursts send one socket frame instead of one per
  character. The inbound replay buffer is dropped once the pump has consumed it.
- Pane switching: switching to a different pane releases the old socket in the
  background instead of waiting for its ~150ms close; same-pane reattach stays
  serial because the release restores the pane grid.
- Receive path: no per-chunk `Pair` allocation (direct `mColumns`/`mRows`), and
  the scroll probe hashes only the visible rows instead of the full transcript.
- The touch/gesture state machine moved to `TermuxScrollGestures`; the host is
  now routing and state (681 → 448 lines), with the `bridgeRef` state replaced
  by a typed holder.
- Accessibility: chips announce their label once plus the status as a state
  description (selection included), sticky SHIFT/CTRL/ALT announce their toggle
  state. Icon buttons were already described.
- Tests: 180 → 189 JVM (frame coalescing, parallel release, detail
  sanitisation, outbound merge), plus 18 instrumented (terminal 11, Home 4,
  Settings 3) and opt-in JaCoCo coverage (`-Pcoverage`).
- Baseline profile: a `:baselineprofile` macrobenchmark module generates the
  startup profile consumed by the release build.
- Dependency: Material3 moved from `1.5.0-alpha17` to stable `1.4.0`; the Rust
  daemon parses child records into typed structs instead of a JSON DOM; daemon
  status details are sanitised to one line (control bytes stripped, 160-char
  cap) instead of rendering raw multi-line errors.
- CI: coverage artifact upload, cached AVD boot for the smoke job.

## 1.8.0 — 2026-11-05

No user-facing feature: this release is the quality pass requested after 1.7.0.

- Structure: `TerminalScreen` (763 lines) split into screen/chrome, surface,
  IME-effects and diagnostics; `TermuxTerminalHost` (681) split out `ScrollProbe`
  math, `WheelBurst` coalescing and `TermuxViewport` geometry;
  `PaneSessionController` (517) split out `TerminalAttach`, `TargetResolution`
  and `PendingInput`; `TerminalSocket` control-record parsing extracted into
  testable `TerminalControl`; `SettingsRepositoryImpl` (409) reduced to
  DataStore access on top of a pure `SettingsCodec`.
- Dead code removed: `ScrollMechanism.kt` stub, `nextMechanism`,
  `wheelScrollable`, duplicate `rowsPerNotch` wrapper.
- Test suite: 108 → 180 JVM tests. New coverage for the ready-record
  carry-over, burst merging, daemon error mapping, HTTP request shape, wheel
  coalescing, probe shift estimation, target resolution and fallback notices,
  pending-input bounds, diagnostics rendering, settings codec round-trip,
  Home and Settings view-model behaviour. Shared fakes moved to the `:testing`
  module so Home/Terminal tests use one `FakeHerdrSession`.
- Automation: `tools/check.sh` local gate (JVM + daemon) and
  `.github/workflows/ci.yml` (Kotlin unit, Rust, isolated emulator smoke).
- `HomeViewModel` now depends on the `HerdrSession` contract instead of the
  concrete client, so its failure paths are testable.

## 1.7.0 — 2026-11-04

- Pane switcher: a tab can hold several Herdr panes (splits), but the tab rail
  only ever opened the tab's active pane — splits were reachable from Home and
  nowhere else. The terminal now shows a compact pane row when the tab has more
  than one pane; tapping one re-attaches that pane. Chips are numbered ("1",
  "2 · codex") because split panes often share a title.
- Selected chips (tabs and panes) get a hairline accent ring: on the AMOLED
  theme every container is black, so selection was previously only a slight
  text-brightness difference.
- Verified on device with an isolated split workspace: tapping pane 2 attached
  it (its output appeared), pane 1 kept its own content. Instrumented tests
  cover the rail and its absence for single-pane tabs.

## 1.6.6 — 2026-11-03

- Pi scroll ran at 2x: one SGR notch moves Pi two rows, so the table must ask
  for one notch per two rows. `AgentMatrix` now carries that measured stride
  (codex 1, opencode 1, pi 2, qwen 3).
- Redraws are coalesced to one per 16 ms frame. Herdr sends a frame per notch;
  a drag could deliver several inside one display frame, and each one redrew
  the whole terminal. Bytes are applied immediately, only the draw waits.
- `docs/terminal-scroll.md`: per-agent stride table and the raw-attach finding
  (`herdr terminal attach` streams raw ANSI, which removes base64 in both
  directions and makes DECSET state real).

## 1.6.5 — 2026-11-03

- Scroll latency: a gesture used to pass two coalescing windows (150 ms drag +
  80 ms wheel), so the first byte could leave 230 ms after the finger moved and
  the view updated on a 150 ms cadence. Wheel now sends on the leading edge with
  a 16 ms (~60 Hz) throttle, host offsets on 40 ms, and page keys keep the wide
  window because each one repaints a whole screen.
- Probe cost: one visible-screen read per gesture instead of two, shift scan
  capped at 80 rows, and no screen reads while the finger is moving.
- `docs/terminal-scroll.md` gained a latency-budget section; `AGENTS.md` points
  future work at it before touching scroll code.
- Smoke: same drag moves 387k px twice with 0.07% spread, focus untouched.

## 1.6.4 — 2026-11-03

- Wheel speed: the stride default was wrong. `rowsPerNotch` started at 3 for
  every app, so a drag sent a third of the notches codex/pi/opencode expect —
  the "half speed" report. The default now comes from the audited agent table
  (1 row per notch, 3 for qwen), and measurements refine it.
- OpenCode responsiveness: wheel notches were sent as one websocket frame each,
  so a fast drag produced dozens of frames and the app redrew per frame. A
  burst is now one frame, and bursts are coalesced to at most one per 80 ms.
- New Settings > Terminal > **Scroll mode**: Automatic (audited table), Mouse
  wheel, Page keys, or Terminal history. The protocol has no universal answer —
  a TUI may enable mouse reporting in one setup and not another — so this is
  the escape hatch instead of another round of guessing.
- Full-screen panes still never move the local buffer unless that mode is
  chosen explicitly.

## 1.6.3 — 2026-11-03

Scrolling is smooth again; the previous two releases regressed it.

- Restored the wheel path that worked before the routing rewrite: SGR wheel
  reports are typed on the input channel (`ESC[<64;45;15M`), not sent as
  Herdr's `terminal.mouse` event (which never reached apps without host
  scrollback). Codex now moves a few lines per short drag instead of a page.
- The wheel/page choice is table-driven again (codex, pi, opencode, qwen:
  wheel; omp, grok, claude: page keys) and is never downgraded by a runtime
  probe: the probe mistook a live repaint for "wheel did nothing" and silently
  switched those panes to whole pages.
- A short drag that accumulates less than one notch now emits one notch when
  the finger lifts, so small gestures are never swallowed.
- Full-screen panes still never scroll the local buffer, and host scrollback
  (`max_offset_from_bottom > 0`) still wins with a line-exact offset.
- Evidence: live Codex pane, 150 px drag → about two lines of transcript (the
  pixel delta is a full repaint; the text shift is what was measured); Pi
  welcome → ~1-3 lines per short drag; `less` → one page per gesture, no local
  drift; the device matrix reports no local drift and no composer input for
  codex/omp/opencode/grok/pi.

## 1.6.2 — 2026-11-03

Scrolling rebuilt from measured behaviour instead of guesses.

- What was wrong: Herdr's `terminal.scroll` does not reach an app that has no
  host scrollback (Codex ignored it; the app looked "dead"). SGR wheel also
  does not move Codex/OMP. Meanwhile the mirror's prelude rows made full-screen
  panes look "local", so a drag moved the terminal's own buffer — the "Pi
  scrolled the whole terminal" bug.
- New rule set, decided by the host's own report: scrollback known and > 0 →
  host offset in rows (line-exact); scrollback known and 0 → the pane is
  full-screen, so the app types the page key on the input channel; unknown
  (older daemon) → mirror evidence, then local transcript as before.
- Page keys are sent as input bytes, not through Herdr's scroll control:
  verified on device — Codex moved 239k px and `less` 388k px per gesture,
  where the scroll control moved nothing.
- A full-screen pane never scrolls the local buffer, and there is no local
  fallback when the host reported the pane (that fallback was the terminal
  drift). Instrumented tests still pass; the device matrix asserts "no local
  drift, no composer input" for codex/omp/opencode/grok/pi and real movement for
  the deterministic `less` fixture.

## 1.6.1 — 2026-11-03

- Full-screen agents scroll again. A pane with an agent label is now routed to
  the remote path even when the mirror banked a few rows (Codex/Pi prelude):
  those rows made the pane "local", and a drag moved that tiny buffer while the
  app's own viewport stayed still. Verified on the live Codex pane: 400k/204k
  changed pixels per drag where it previously did nothing.
- Scroll routing follows the agent identity as it arrives. The routing flags
  were written once when the stream was created; if the snapshot learned the
  agent later (Pi), the pane stayed local for its lifetime.
- Wheel is offered before page keys for wheel-known agents, so a gesture moves
  notches (a few lines) instead of a full screen; the runtime probe still falls
  back to page keys when the app ignores wheel, and to local scrolling when
  every remote mechanism is inert (stale agent label on an exited shell).
- Instrumented UI tests and `tools/ci.sh` still green; live session verified
  untouched by the runner.

## 1.6.0 — 2026-11-03

Architecture and test infrastructure; no intended behaviour change.

- `HerdrClient` decomposed: 746 -> 216 lines. Link lifecycle/backoff, link state
  and generations, snapshot sync, transport and REST commands are separate
  types with a fake-transport, virtual-clock test suite (backoff sequence,
  generation bump on target change, `snapshot.required`, seq gaps, stop/scope
  cancellation).
- `SettingsScreen` 720 -> 131 lines and `HomeScreen` 701 -> 274: one file per
  section/list, item keys and order unchanged (settings verified pixel-
  identical before/after on device).
- `TerminalViewModel` 892 -> 411 (done in 1.5.2), now `PaneSessionController`.
- Instrumented Compose UI tests: badge appears only after a second of downtime
  and is announced (liveRegion), recovering hides it, extra-key taps deliver
  the key, the rail routes a tap to the right tab, the keyboard button asks for
  the IME.
- `UiCopy` seam: screens render status messages through a composition local, so
  a translation is a new implementation rather than a rewrite (English default
  unchanged).
- `tools/ci.sh`: runs the device smoke against a private herdr session + daemon
  on port 8799, points the debug app at it, restores the saved URL, tears the
  session down, and fails loudly if the live session's workspaces or focus
  changed. Verified: CI OK, live session untouched.
- Kotlin 105 tests + 6 instrumented, Rust 50.

## 1.5.3 — 2026-11-02

- Midnight (AMOLED) now covers the whole app, not just the background: every
  Material surface role — containers lowest…highest, dim/bright, variant and
  the accent containers — is #000000, and the elevation tint is black.
  Separation comes from hairline outlines instead of lifted greys.
- Extra-key buttons: a tonal fill on black would render as floating text, so
  unarmed keys carry a 1 dp outlineVariant border and the armed state is the
  accent colour.
- Verified on device by pixel sampling: Home 97% pure black (the rest is text
  and status dots), dialog surfaces #000, extra-keys panel #000 with visible
  key outlines.

## 1.5.2 — 2026-11-02

- Scroll: a mirrored TUI that banked a few local rows used to be routed to the
  local transcript, so a drag moved a buffer nobody was looking at and the app's
  own viewport stayed still ("scroll is dead"). The host now verifies: if a real
  drag leaves the view's own offset at 0, that pane switches to the remote path
  and the same gesture is replayed. Also flushes a discrete replay immediately
  instead of waiting for a page boundary.
- Tests no longer touch the user's session: `workspace.create` accepts
  `focus:false` (daemon + client), so a test workspace never takes Herdr's
  focus. Verified: focus stayed on the user's workspace across create, attach,
  scroll, keyboard checks and close.
- `tools/smoke.sh`: the manual device checks are now a script — isolated
  workspace, one-drag scroll, repeatability within a tolerance, keyboard
  show/hide/background-return, and an explicit "focus was not taken" assertion.
- `TerminalViewModel` split: attach/target/recovery moved to
  `PaneSessionController` (892 -> 409 lines) with the behaviour tests covering
  the rules end to end.
- `UiMessage` moved to the design system and now covers Home and Settings too;
  a settings failure that used to be swallowed is rendered.
- Kotlin 98 tests / Rust 50; smoke OK on device.

## 1.5.1 — 2026-11-02

Refactor pass with tests written from behaviour, not implementation.

- `HerdrSession`: the view-model depends on a narrow contract (state,
  generations, openTerminal, the five commands) instead of the concrete
  `HerdrClient`. A scripted session now drives the screen's state machine, so
  the rules are executable: active pane opens; a workspace closed elsewhere
  retargets and says so; an empty session drops the target; a daemon restart
  reopens the same pane; a dropped stream re-attaches without user action;
  input typed during re-attach is delivered.
- `UiMessage`: the status line is data (category + parameters) with one
  renderer, replacing 19 inline strings. Tests assert the contract: every
  message renders, failures name the action and the reason, a missing reason
  never leaks null, notices that name a destination include it, every command
  has distinct wording.
- `ScrollMath`: drag→rows, rows→pages and the sub-page flush are pure
  functions with tests for proportionality, accumulation, direction, unknown
  pitch and whole-screen conversion — the manual device checks are now code.
- Kotlin 98 tests / Rust 50. Device smoke: pane renders, scroll moves 369k px,
  keyboard opens by button and stays hidden on return from background.

## 1.5.0 — 2026-11-02

Scroll is now negotiated at runtime instead of guessed from an app name.

- **Mechanisms**: host offset (`pane.scroll`, line-exact), page keys (whole
  screens), SGR wheel notches. Candidate order comes from capabilities: host
  scrollback first, then wheel for wheel-known apps, page keys always.
- **Probe**: the first request of a pane hashes the visible screen
  (`TerminalBuffer.transcriptText`), sends, and checks again 260 ms later. An
  inert mechanism is swapped for the next candidate and the same rows are
  re-sent, so the gesture is never lost; a working one is remembered per app
  (process lifetime). This is how Codex/OMP/OpenCode/Pi/Grok/Claude Code and
  arbitrary TUIs get an answer without a hand-maintained table.
- **Verified on device**: `less` in a mirrored pane — two identical drags move
  exactly 43 rows each (one screen), no double-apply from the probe; panes with
  a local transcript keep native scrolling.
- `unit: rows | pages` on the wire, page size from the host viewport, and
  `scroll{offsetFromBottom,maxOffsetFromBottom,viewportRows}` in the snapshot
  (from 1.4.7) remain the transport underneath the negotiation.

## 1.4.7 — 2026-11-02

- Host scrolling: Herdr exposes `pane.scroll { offset_from_bottom }`, so panes
  that have host scrollback now scroll line-exactly instead of in page jumps.
  The daemon calls it, clamps to `[0, max_offset_from_bottom]`, memoizes the
  live offset and falls back to page keys when a pane has no scrollback.
- The snapshot now carries `scroll` per pane (`offsetFromBottom`,
  `maxOffsetFromBottom`, `viewportRows`), and the app uses `viewportRows` as
  the page unit — Herdr attached at 30 rows while the phone drew 44, so page
  gestures drifted between screens.
- Scroll requests carry an explicit `unit` ("rows" | "pages") so the daemon
  never has to guess what `lines` counts: rows go through `pane.scroll`, pages
  are used verbatim by the PgUp/PgDn fallback. Verified on device: two
  identical drags move the same distance on `less` (one screen each), and the
  local-transcript path is unchanged.
- Daemon tests: 50 (scroll policy, pane.scroll clamping, unit conversion).

## 1.4.6 — 2026-11-02

- Anything with no local transcript now scrolls remotely. Mirrored full-screen
  TUIs (less, vim, top, agent panes) never accumulate local scrollback, so the
  previous "local unless the pane runs an agent" rule made them dead to a
  swipe. Routing is now "can anything actually move?" — alt-screen/mouse
  tracking, or no local rows → remote; otherwise local.
- Page scrolls use the view's real row count (height / renderer pitch). The
  mirror's grid reported 22 rows for a 44-row view mid-resize, so every page
  gesture scrolled twice as far as the gesture suggested.
- Daemon: remote scroll of a pane with host scrollback now moves by the exact
  requested rows through Herdr's absolute `pane.scroll` offset instead of
  whole-screen jumps; falls back to page keys where the app owns the viewport.
- Daemon: the `scroll` frame's `lines` is treated as ROWS on every backend —
  page-key fallbacks convert with the attachment height (min 1 page, max 3).
- `/v1/snapshot` panes carry `scroll` (`offsetFromBottom`, `maxOffsetFromBottom`,
  `viewportRows`) when Herdr reports it.

## 1.4.5 — 2026-11-02

- Clipboard multi-line paste: inside a 2004 bracketed block the payload is
  literal text, so newlines stay LF. They used to be normalized to CR — the
  same byte Enter sends — so a multi-line paste into Pi/codex/omp fired one
  submit per line instead of inserting the text.
- Paste is also no longer re-normalized on the way out: the prepared payload
  went through the text-send path, which stripped the 2004 markers and turned
  LF into CR a second time. Clipboard now has its own verbatim send path.
- Scroll speed is consistent across panes: remote scrolling used
  view.height/rows (off by the renderer's real pitch), the fling added a fixed
  ±12-row jump on top of the drag, and page-key panes moved a whole screen per
  request no matter how short the gesture was. Now one row unit (the renderer's
  pitch), a fling capped at 6 rows, and page scrolls accumulated from the drag
  distance with a single page flushed on release.

## 1.4.4 — 2026-11-02

- Connection badge: a small pill appears over the terminal's top rows when the
  link stays down for more than a second (Connecting / Reconnecting /
  Refreshing / Connection lost) and disappears the moment it is back.
- Reconnect keeps the pane: a daemon restart or tunnel drop used to leave
  "Detached (closed)" behind; the app now re-attaches the same pane with a
  bounded retry and only falls back to Retry when that really fails.
- Scroll routing reads live mouse tracking: SGR wheel bytes are only sent to a
  program that asked for them. Agent panes without mouse tracking page the
  host's scrollback instead of typing inert sequences.
- Architecture pass (no behaviour change intended, all covered by tests):
  `ScrollRoute` (one scroll policy), `ImePolicy` (one keyboard policy),
  `TerminalKeyboardController` (one IME owner), `TermuxInternals` (one
  reflection door), `TargetReconciler` (pure retarget decision),
  `TerminalScreen` split into screen/rails/dialogs/status/input-panel,
  Home screen dialogs moved out, daemon scroll gating shared by both backends.
- Fixed a stale `connection-ssh` test that did not compile with the current
  `HostProfile`.

## 1.4.3 — 2026-11-02

- Shell scroll fixed: Herdr TUI keeps a stale `agent` label on panes the agent
  already exited (a plain `nico@…$` prompt still reported `agent=codex`), so
  those panes were routed to remote scrolling and swallowed SGR wheel bytes —
  "scroll does nothing". Live alt-screen / mouse tracking still routes remote;
  anything else scrolls the local transcript, and a main-screen agent with no
  local history (omp) still uses the host scrollback.
- Keyboard is button-only now. Focus and pane switches never raise the IME,
  and a focused terminal no longer re-shows the keyboard when the app returns
  from the background. Switching the floating input panel keeps an open
  keyboard open (it used to clear focus and dismiss it).
- Closing a workspace no longer strands the screen on "Detached (closed)":
  the app retargets the nearest surviving workspace and says so.
- Floating input panel sat on a doubled inset (nav bar + IME); one union
  inset now, so the card hugs the gesture area with no dead band.

## 1.4.2 — 2026-11-02

- Midnight theme: pure-black AMOLED app theme + matching terminal scheme.
  All M3 surfaces #000 (dynamic color bypassed), terminal background
  #000000. Selectable separately in Settings > Appearance.
- Shell scroll investigated: no app regression found — local Termux scroll
  works in all zones when history exists (verified by zone mapping).
  Reported symptom matches empty-history panes (nothing above to reveal)
  or bottomed-out viewports.

## 1.4.1 — 2026-10-30

- Harden herdr-core input and event routing: bracketed paste keeps ESC markers
  on core DATA bytes while the TUI retains its `input.text` path; PageUp/PgDn
  scrolling requires app/alt-screen evidence; app identity comes from core's
  `status.app`; and event cursors are isolated per subscriber. Connection-target
  changes clear cached snapshots and release old terminal attachments, stale
  refreshes cannot republish prior state, and core-only unavailable errors stay
  typed and visible to the user.
- Fixes Flow-invariant crash on backend/target switch (events flow never swapped).

## 1.4.0 — 2026-10-30

- Backend choice: each connection selects Herdr TUI or Herdr app (core) in
  Settings. Daemon speaks herdr-core JSON-RPC (default socket
  $XDG_RUNTIME_DIR/herdr-core/default.sock) and serves the same /v1 API:
  snapshot/events/terminal/input/resize/interrupt/create/rename/close.
  Move/focus return typed unavailable (no core equivalent); scroll sends
  PgUp/PgDn keys; mouse becomes SGR bytes. TUI path unchanged (default).

## 1.3.29 — 2026-10-29

- AgentMatrix covers all 24 supported `--kind` values with per-entry
  upstream evidence. Qwen's documented 3-line wheel stride is preserved
  explicitly. copilot/hermes stay on conservative page_key (wheel stride
  unestablished); all unverified kinds keep safe OMP defaults.

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
