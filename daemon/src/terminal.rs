use std::collections::HashMap;
use std::sync::Arc;

use anyhow::{Context, Result};
use axum::extract::ws::{Message, WebSocket};
use futures_util::{SinkExt, StreamExt};
use tokio::sync::{broadcast, mpsc, Mutex};
use tracing::{debug, info, warn};

use crate::cache::SessionCache;
use crate::herdr::terminal::{ChildRecord, TerminalChild};
use crate::herdr::HerdrClient;
use crate::model::ScrollInfo;
use crate::scroll_policy::{self, ScrollAction, ScrollBackend, ScrollRequest};

/// Live terminal attachments, keyed by pane id. Attaching twice reuses the session; every
/// attached bridge receives every frame.
pub struct TerminalRegistry {
    herdr: HerdrClient,
    /// Snapshot cache: fallback source for a pane's host-scroll position when
    /// the live `pane.get` fails.
    cache: Arc<SessionCache>,
    sessions: Mutex<HashMap<String, Arc<TerminalSession>>>,
    /// Set under the sessions lock before draining. New attaches reject, and
    /// an attach that already passed the check is included in the drain.
    shutting_down: std::sync::atomic::AtomicBool,
}

pub struct TerminalSession {
    pub pane_id: String,
    input_tx: mpsc::Sender<SessionCommand>,
    frames_tx: broadcast::Sender<SessionFrame>,
    closed_tx: broadcast::Sender<String>,
    /// Last close reason, replayable: run_session can fail between attach()
    /// returning and bridge() subscribing to the no-replay broadcast.
    last_close_reason: std::sync::Mutex<Option<String>>,
    /// Viewport to restore on the Herdr side when the last bridge goes away, so the pane
    /// does not keep the phone's narrow grid after we detach.
    restore_tx: mpsc::Sender<RestoreViewport>,
    /// Bridges currently watching this session, plus whether teardown started.
    /// Only the last bridge leaving flips `closing` and wakes run_session; once
    /// `closing` is set, attach() never reuses the session — a re-attach racing
    /// with teardown gets a fresh session instead of a dying one.
    bridges: std::sync::Mutex<BridgeState>,
    /// Fired by the last bridge leaving. Wakes run_session even when the 64-deep
    /// command queue is full of resizes — teardown correctness must never depend
    /// on queue capacity.
    shutdown: tokio::sync::Notify,
}

/// Watcher count + teardown flag. See `TerminalSession.bridges`.
struct BridgeState {
    count: usize,
    closing: bool,
}

/// Geometry to hand back to Herdr on detach.
#[derive(Clone, Copy, Debug)]
struct RestoreViewport {
    cols: u32,
    rows: u32,
}

#[derive(Clone, Debug)]
enum SessionFrame {
    Bytes(Vec<u8>),
    Ready { cols: u32, rows: u32, resumed: bool },
}

/// Finish an in-flight stdin record before a release, or discard the pipe.
const INPUT_WRITE_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(1);

#[derive(Debug)]
enum SessionCommand {
    /// Raw client bytes → child stdin.
    Input(Vec<u8>),
    /// UTF-8 text → child stdin as `terminal.input` text.
    Text(String),
    Resize(u32, u32),
    /// Remote scroll for an alt-screen TUI, in ROWS. `run_session` prefers
    /// Herdr's absolute `pane.scroll` offset (one row = one row) and only
    /// falls back to the shared policy's `terminal.scroll` page keys / wheel
    /// routing when the pane has no host scrollback. Shells never reach here
    /// (they scroll local prelude history instantly, no RTT).
    Scroll(ScrollAction),
    Mouse(String, String, u32, u32),
    /// Absolute host-scroll offset (scrollbar drag), in rows from live.
    HostScroll(u64),
}

impl TerminalRegistry {
    pub fn new(herdr: HerdrClient, cache: Arc<SessionCache>) -> TerminalRegistry {
        TerminalRegistry {
            herdr,
            cache,
            sessions: Mutex::new(HashMap::new()),
            shutting_down: std::sync::atomic::AtomicBool::new(false),
        }
    }

    pub fn herdr(&self) -> &HerdrClient {
        &self.herdr
    }

    pub async fn shutdown(&self) {
        use std::sync::atomic::Ordering;
        // Keep the map entries until run_session removes them: draining here
        // let a concurrent attach insert a new controller after the drain,
        // which the shutdown then never released. The flag and the drain are
        // under the same lock, so an attach either lands in the drain or sees
        // the flag and is rejected.
        let sessions: Vec<Arc<TerminalSession>> = {
            let guard = self.sessions.lock().await;
            self.shutting_down.store(true, Ordering::SeqCst);
            guard.values().cloned().collect()
        };
        let mut closed = Vec::with_capacity(sessions.len());
        for session in &sessions {
            // Already torn down: its close reason was set before we subscribed,
            // and broadcast has no replay.
            if session
                .last_close_reason
                .lock()
                .expect("close reason poisoned")
                .is_some()
            {
                continue;
            }
            // Notify, not the bounded input queue: a full queue (child write
            // stalled) would block shutdown before its 5s wait even starts.
            let rx = session.closed_tx.subscribe();
            session.shutdown.notify_one();
            closed.push(rx);
        }
        // One shared deadline, not 5s per session: two stuck panes must not
        // turn shutdown into a 10s wait.
        let deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(5);
        for mut rx in closed {
            let now = tokio::time::Instant::now();
            if now >= deadline {
                break;
            }
            let _ = tokio::time::timeout(deadline - now, rx.recv()).await;
        }
    }

    /// Attach (or reuse) and bridge the child to `socket`. Returns when either side closes.
    ///
    /// `restore` is the viewport to hand back to Herdr when this bridge goes away, so the
    /// pane does not keep the phone's grid after we detach. `None` means: read the
    /// pane's tab-layout rect now (before the phone's resize lands) and hand that back.
    /// NOTE: per Herdr #4437 this is the tab-layout rect, not any one desktop client's
    /// live PTY grid — with multiple clients there is no single "desktop geometry".
    /// It is still the best available estimate of the pre-attach size, and strictly
    /// better than a hardcoded fallback. An explicit value overrides it;
    /// `Some((0, _))` disables the restore.
    pub async fn bridge(
        self: &Arc<Self>,
        mut socket: WebSocket,
        pane_id: String,
        takeover: bool,
        cols: u32,
        rows: u32,
        restore: Option<(u32, u32)>,
    ) -> Result<()> {
        let session = self.attach(pane_id.clone(), takeover, cols, rows).await?;
        // Subscribe BEFORE the restore RPC below: run_session can fail fast
        // (spawn failure -> attach_failed on closed_tx, a broadcast with no
        // replay). Any await between attach and subscribe — RPC included —
        // lets the failure slip through with zero receivers, hanging the
        // bridge on a blank terminal.
        let frames_rx = session.frames_tx.subscribe();
        let closed_rx = session.closed_tx.subscribe();
        let close_reason = session
            .last_close_reason
            .lock()
            .expect("close reason poisoned")
            .clone();
        if let Some(reason) = close_reason {
            let closed = serde_json::json!({"type": "closed", "reason": reason});
            let _ = socket.send(Message::Text(closed.to_string().into())).await;
            return Ok(());
        }
        let restore: Option<RestoreViewport> = match restore {
            // Explicit client value (or disable) wins.
            Some((0, _)) => None,
            Some((c, r)) => Some(RestoreViewport { cols: c, rows: r }),
            // No client opinion: snapshot the tab-layout rect now (best available
            // pre-attach estimate) and hand exactly that back on detach.
            None => self.herdr.pane_layout_size(&pane_id).await.ok().map(|(c, r)| {
                tracing::debug!("learned tab-layout rect for {pane_id}: {c}x{r}");
                RestoreViewport { cols: c, rows: r }
            }),
        };
        if let Some(req) = restore {
            // try_send, not await: restore_tx drains only at teardown, so an
            // awaiting send would deadlock joiners once the cap fills. On Full
            // the newest geometry is dropped in favor of an equally-fresh older
            // one — teardown takes last-drained-wins either way, and every
            // joiner passes the same attach-time snapshot, so loss is benign.
            let _ = session.restore_tx.try_send(req);
        }
        run_bridge(
            session,
            socket,
            self.clone(),
            pane_id,
            frames_rx,
            closed_rx,
            rows,
        )
        .await
    }

    /// Scroll a Herdr TUI pane by ROWS through the ABSOLUTE `pane.scroll` RPC.
    ///
    /// `terminal.scroll` is relative and its page-key fallback moves a whole
    /// screen per press, so a 3-row swipe used to jump three screens. Herdr's
    /// absolute host offset moves exactly the rows asked for.
    ///
    /// Returns `false` when the caller must fall back to `terminal.scroll`:
    /// the pane has no host scrollback, Herdr reports no scroll state for it,
    /// or the RPC failed.
    async fn host_scroll(
        &self,
        pane_id: &str,
        up: bool,
        rows: u32,
    ) -> bool {
        // Always re-read the live offset before applying a RELATIVE gesture.
        // A cached offset from before a desktop scrollbar/wheel move made the
        // next phone drag jump to the wrong absolute position.
        let Some(base) = self.pane_scroll_state(pane_id).await else {
            return false;
        };
        if !base.has_scrollback() {
            // Main-screen TUIs keep no host scrollback: page keys are the only
            // lever Herdr has for them.
            return false;
        }
        let Some(target) = scroll_policy::host_scroll_target(
            base.offset_from_bottom,
            base.max_offset_from_bottom,
            up,
            rows,
        ) else {
            // Already at the clamp boundary: skip the RPC (never re-assert the
            // same offset). Scrolling up against a max that may have grown
            // since the last report is re-resolved next gesture; down at the
            // live bottom is stable and keeps its state.
            return true;
        };
        match self.herdr.pane_scroll(pane_id, target).await {
            Ok(reported) => {
                let info = match reported {
                    Some(info) => info,
                    None => ScrollInfo { offset_from_bottom: target, ..base },
                };
                let _ = info;
                true
            }
            Err(error) => {
                warn!("pane.scroll failed for {pane_id} (target {target}): {error:#}; using page keys");
                false
            }
        }
    }

    /// Live scroll state for a pane: `pane.get` first (offset_from_bottom is
    /// view state the event stream never carries, so a cached copy can be
    /// badly stale), then the snapshot cache as a fallback.
    async fn pane_scroll_state(&self, pane_id: &str) -> Option<ScrollInfo> {
        match self.herdr.pane_scroll_info(pane_id).await {
            Ok(info) => Some(info),
            Err(error) => {
                debug!("pane.get has no scroll state for {pane_id}: {error:#}");
                self.cache.pane_scroll(pane_id)
            }
        }
    }

    async fn attach(
        self: &Arc<Self>,
        pane_id: String,
        takeover: bool,
        cols: u32,
        rows: u32,
    ) -> Result<Arc<TerminalSession>> {
        // A closing session still owns the Herdr controller (restore resize +
        // beat + child.kill). Keep its map entry until teardown finishes so a
        // second attacher waits on the same session instead of spawning a new
        // controller underneath it. The entry is removed here only after the
        // close reason is set; run_session removes it too (idempotent).
        loop {
            enum Existing {
                Reuse(Arc<TerminalSession>),
                Closing(Arc<TerminalSession>, broadcast::Receiver<String>),
            }
            let existing = {
                let guard = self.sessions.lock().await;
                if self
                    .shutting_down
                    .load(std::sync::atomic::Ordering::SeqCst)
                {
                    anyhow::bail!("daemon is shutting down");
                }
                match guard.get(&pane_id).cloned() {
                    Some(session) => {
                        let closing = {
                            let mut st = session.bridges.lock().expect("bridges poisoned");
                            if st.closing {
                                true
                            } else {
                                st.count += 1;
                                false
                            }
                        };
                        if closing {
                            let rx = session.closed_tx.subscribe();
                            Some(Existing::Closing(session, rx))
                        } else {
                            Some(Existing::Reuse(session))
                        }
                    }
                    None => None,
                }
            };
            match existing {
                Some(Existing::Reuse(session)) => {
                    // Newest geometry wins; the session task coalesces it.
                    let _ = session
                        .input_tx
                        .send(SessionCommand::Resize(cols, rows))
                        .await;
                    return Ok(session);
                }
                Some(Existing::Closing(session, mut closed_rx)) => {
                    if session
                        .last_close_reason
                        .lock()
                        .expect("close reason poisoned")
                        .is_none()
                    {
                        let _ = tokio::time::timeout(
                            std::time::Duration::from_secs(5),
                            closed_rx.recv(),
                        )
                        .await;
                    }
                    if session
                        .last_close_reason
                        .lock()
                        .expect("close reason poisoned")
                        .is_none()
                    {
                        anyhow::bail!("terminal {pane_id} is still closing; retry");
                    }
                    let mut guard = self.sessions.lock().await;
                    if let Some(current) = guard.get(&pane_id) {
                        if Arc::ptr_eq(current, &session) {
                            guard.remove(&pane_id);
                        }
                    }
                    continue;
                }
                None => {}
            }

            let mut guard = self.sessions.lock().await;
            if self
                .shutting_down
                .load(std::sync::atomic::Ordering::SeqCst)
            {
                anyhow::bail!("daemon is shutting down");
            }
            if guard.get(&pane_id).is_some() {
                drop(guard);
                tokio::task::yield_now().await;
                continue;
            }
            let (input_tx, input_rx) = mpsc::channel::<SessionCommand>(64);
            let (frames_tx, _) = broadcast::channel::<SessionFrame>(256);
            let (closed_tx, _) = broadcast::channel::<String>(4);
            let (restore_tx, restore_rx) = mpsc::channel::<RestoreViewport>(4);
            let session = Arc::new(TerminalSession {
                pane_id: pane_id.clone(),
                input_tx,
                frames_tx,
                closed_tx,
                last_close_reason: std::sync::Mutex::new(None),
                restore_tx,
                bridges: std::sync::Mutex::new(BridgeState { count: 1, closing: false }),
                shutdown: tokio::sync::Notify::new(),
            });
            let runner_session = session.clone();
            let herdr = self.herdr.clone();
            let registry = Arc::clone(self);
            tokio::spawn(async move {
                run_session(&registry, runner_session, herdr, takeover, cols, rows, input_rx, restore_rx).await;
            });
            guard.insert(pane_id, session.clone());
            return Ok(session);
        }
    }

    async fn remove(&self, pane_id: &str, session: &Arc<TerminalSession>) {
        let mut guard = self.sessions.lock().await;
        if let Some(current) = guard.get(pane_id) {
            if Arc::ptr_eq(current, session) {
                guard.remove(pane_id);
            }
        }
    }
}

/// Bound every write into the terminal child. A wedged child that stops
/// reading stdin must not pin the shutdown select (or the daemon) forever.
async fn terminal_io<F, T>(future: F) -> anyhow::Result<T>
where
    F: std::future::Future<Output = anyhow::Result<T>>,
{
    tokio::time::timeout(std::time::Duration::from_secs(2), future)
        .await
        .map_err(|_| anyhow::anyhow!("terminal write timed out"))?
}

async fn run_session(
    registry: &TerminalRegistry,
    session: Arc<TerminalSession>,
    herdr: HerdrClient,
    takeover: bool,
    cols: u32,
    rows: u32,
    mut input_rx: mpsc::Receiver<SessionCommand>,
    mut restore_rx: mpsc::Receiver<RestoreViewport>,
) {
    let pane_id = session.pane_id.clone();
    let mut child = match TerminalChild::spawn(&herdr, &pane_id, takeover, cols, rows).await {
        Ok(child) => child,
        Err(e) => {
            warn!("cannot attach to pane {pane_id}: {e:#}");
            // Mark closing BEFORE announcing failure: a concurrent attach() in
            // the insert→remove window must not reuse this doomed session, and
            // a late joiner must not miss the already-sent attach_failed on a
            // broadcast channel that replays nothing.
            session.bridges.lock().expect("bridges poisoned").closing = true;
            *session.last_close_reason.lock().expect("close reason poisoned") =
                Some("attach_failed".to_string());
            let _ = session.closed_tx.send("attach_failed".to_string());
            registry.remove(&pane_id, &session).await;
            return;
        }
    };
    // Android acks DONE on the first Ready frame, so wait for the controller's
    // first record (successful screen bytes) instead of trusting process life
    // alone. A controller that reports busy/failed after a few hundred ms must
    // not look attached. A genuinely quiet pane times out and is accepted.
    let first = tokio::time::timeout(
        // A controller can report busy well after 600 ms (measured 766 ms).
        // Waiting longer is safer than acknowledging a DONE too early; a
        // genuinely quiet pane only pays this once at attach.
        std::time::Duration::from_millis(1500),
        child.next_record(),
    )
    .await;
    let first_frame = match first {
        Ok(Ok(Some(ChildRecord::Frame(bytes)))) => Some(bytes),
        Ok(Ok(Some(ChildRecord::Closed(reason)))) => {
            warn!("terminal controller for {pane_id} closed during attach: {reason}");
            session.bridges.lock().expect("bridges poisoned").closing = true;
            *session.last_close_reason.lock().expect("close reason poisoned") =
                Some("attach_failed".to_string());
            let _ = session.closed_tx.send("attach_failed".to_string());
            registry.remove(&pane_id, &session).await;
            return;
        }
        Ok(Ok(None)) | Ok(Err(_)) => {
            session.bridges.lock().expect("bridges poisoned").closing = true;
            *session.last_close_reason.lock().expect("close reason poisoned") =
                Some("attach_failed".to_string());
            let _ = session.closed_tx.send("attach_failed".to_string());
            registry.remove(&pane_id, &session).await;
            return;
        }
        Err(_) => None,
    };
    if first_frame.is_none() && !child.is_alive() {
        session.bridges.lock().expect("bridges poisoned").closing = true;
        *session.last_close_reason.lock().expect("close reason poisoned") =
            Some("attach_failed".to_string());
        let _ = session.closed_tx.send("attach_failed".to_string());
        registry.remove(&pane_id, &session).await;
        return;
    }
    info!("attached terminal session for pane {pane_id} ({cols}x{rows})");
    let _ = session.frames_tx.send(SessionFrame::Ready { cols, rows, resumed: false });
    if let Some(bytes) = first_frame {
        let _ = session.frames_tx.send(SessionFrame::Bytes(bytes));
    }
    let mut last_resize = tokio::time::Instant::now();
    let mut pending_resize: Option<(u32, u32)> = None;
    // Fixed deadline for the pending flush: a fresh sleep() per select! poll
    // restarts on every arriving command, starving sustained resize streams.
    // The deadline is set once when the first pending resize lands.
    let mut flush_at: Option<tokio::time::Instant> = None;
    // Process kind changes when a shell launches less/vim; a one-second cache
    // avoids an RPC per scroll frame without pinning a stale classification.
    let mut shell_probe: Option<(bool, tokio::time::Instant)> = None;
    let mut end_reason = String::from("detached");
    // A timed-out NDJSON input write may have left a partial line. Never feed
    // another input record into that pipe; teardown kills the child instead.
    let mut input_broken = false;
    // Race every potentially long terminal/Herdr wait against shutdown so a
    // multi-step scroll/foreground/write branch cannot consume the whole 5s
    // shutdown budget before it notices.
    macro_rules! or_shutdown {
        ($future:expr) => {
            tokio::select! {
                _ = session.shutdown.notified() => {
                    end_reason = "released".to_string();
                    break;
                }
                value = $future => value,
            }
        };
    }
    loop {
        tokio::select! {
            _ = session.shutdown.notified() => {
                end_reason = "released".to_string();
                break;
            }
            cmd = input_rx.recv() => {
                let Some(cmd) = cmd else { end_reason = "released".to_string(); break };
                match cmd {
                    SessionCommand::Input(bytes) => {
                        // Input writes are NOT cancelled by shutdown: a
                        // half-written NDJSON line followed by the next
                        // record would corrupt both. Finish within a bound,
                        // or mark the pipe unusable and discard the child.
                        match tokio::time::timeout(
                            INPUT_WRITE_TIMEOUT,
                            terminal_io(child.send_input_bytes(&bytes)),
                        ).await {
                            Ok(Ok(())) => {}
                            Ok(Err(_)) => break,
                            Err(_) => {
                                input_broken = true;
                                end_reason = "input write timed out".to_string();
                                break;
                            }
                        }
                    }
                    SessionCommand::Text(text) => {
                        match tokio::time::timeout(
                            INPUT_WRITE_TIMEOUT,
                            terminal_io(child.send_input_text(&text)),
                        ).await {
                            Ok(Ok(())) => {}
                            Ok(Err(_)) => break,
                            Err(_) => {
                                input_broken = true;
                                end_reason = "input write timed out".to_string();
                                break;
                            }
                        }
                    }
                    SessionCommand::Resize(c, r) => {
                        // Coalesce resizes to at most one per 120 ms.
                        if last_resize.elapsed() >= std::time::Duration::from_millis(120) {
                            // A newer immediate resize supersedes any pending
                            // deferred one; leaving it queued would replay the
                            // older geometry after this newer one.
                            pending_resize = None;
                            flush_at = None;
                            if or_shutdown!(terminal_io(child.send_resize(c, r))).is_err() { break; }
                            last_resize = tokio::time::Instant::now();
                            let _ = session.frames_tx.send(SessionFrame::Ready { cols: c, rows: r, resumed: false });
                        } else {
                            pending_resize = Some((c, r));
                            if flush_at.is_none() {
                                flush_at = Some(last_resize + std::time::Duration::from_millis(120));
                            }
                        }
                    }
                    SessionCommand::Scroll(action) => {
                        let host_handled = match action {
                            ScrollAction::PageKey { up, rows } => {
                                // Bound the RPC so a wedged Herdr cannot pin
                                // shutdown or starve the child's output.
                                or_shutdown!(tokio::time::timeout(
                                    std::time::Duration::from_secs(2),
                                    registry.host_scroll(&pane_id, up, rows),
                                ))
                                .unwrap_or(false)
                            }
                            // Wheel routing is the app's own viewport, never
                            // the host scrollback.
                            _ => false,
                        };
                        let mut block_page_keys = false;
                        if !host_handled {
                            if matches!(action, ScrollAction::PageKey { .. }) {
                                let fresh = shell_probe
                                    .filter(|(_, at)| at.elapsed() < std::time::Duration::from_secs(1));
                                let is_shell = match fresh {
                                    Some((is_shell, _)) => is_shell,
                                    None => {
                                        // Unknown (RPC error): stay safe and do
                                        // not type PgUp/PgDn into a shell.
                                        let is_shell = or_shutdown!(tokio::time::timeout(
                                            std::time::Duration::from_secs(2),
                                            herdr.pane_foreground_is_shell(&pane_id),
                                        ))
                                        .ok()
                                        .and_then(Result::ok)
                                        .unwrap_or(true);
                                        shell_probe = Some((is_shell, tokio::time::Instant::now()));
                                        is_shell
                                    }
                                };
                                block_page_keys = is_shell;
                            }
                        }
                        if !host_handled && !block_page_keys
                            && or_shutdown!(terminal_io(child.send_scroll(action))).is_err()
                        {
                            break;
                        }
                    }
                    SessionCommand::HostScroll(offset) => {
                        match or_shutdown!(tokio::time::timeout(
                            std::time::Duration::from_secs(2),
                            herdr.pane_scroll(&pane_id, offset),
                        )) {
                            Ok(Ok(Some(_))) | Ok(Ok(None)) => {}
                            Ok(Err(error)) => {
                                warn!("pane.scroll absolute {offset} failed for {pane_id}: {error:#}");
                            }
                            Err(_) => {
                                warn!("pane.scroll absolute {offset} timed out for {pane_id}");
                            }
                        }
                    }
                    SessionCommand::Mouse(action, button, column, row) => {
                        if or_shutdown!(terminal_io(child.send_mouse(&action, &button, column, row))).is_err() { break; }
                    }
                }
            }
            record = child.next_record() => {
                match record {
                    Ok(Some(ChildRecord::Frame(bytes))) => {
                        if session.frames_tx.receiver_count() == 0 {
                            // Nobody is watching; keep the child alive but skip the copy.
                            continue;
                        }
                        if session.frames_tx.send(SessionFrame::Bytes(bytes)).is_err() {
                            break;
                        }
                    }
                    Ok(Some(ChildRecord::Closed(reason))) => { end_reason = reason; break; }
                    Ok(None) => { end_reason = "child exited".to_string(); break; }
                    Err(e) => {
                        warn!("terminal child for {pane_id} errored: {e:#}");
                        end_reason = "child error".to_string();
                        break;
                    }
                }
            }
            _ = async {
                match flush_at {
                    Some(t) => tokio::time::sleep_until(t).await,
                    None => std::future::pending::<()>().await,
                }
            }, if pending_resize.is_some() => {
                flush_at = None;
                if let Some((c, r)) = pending_resize.take() {
                    if or_shutdown!(terminal_io(child.send_resize(c, r))).is_err() { break; }
                    last_resize = tokio::time::Instant::now();
                    let _ = session.frames_tx.send(SessionFrame::Ready { cols: c, rows: r, resumed: false });
                }
            }
        }
    }
    // The client sends `release` on the same socket AFTER its input frames, so
    // every prior keystroke is already in this queue. A shutdown can break the
    // loop while a scroll RPC is in flight; forward the accepted input before
    // any pending resize, restore, or kill, or the last keystrokes vanish.
    // If the in-flight write timed out, the pipe has a partial line: sending
    // the remaining input would concatenate two NDJSON records. Kill it.
    if !input_broken {
        // A timeout here leaves the same partial NDJSON record as the
        // in-flight write path; report it so teardown discards the pipe.
        input_broken = !flush_pending_input(&mut child, &mut input_rx).await;
    }
    if !input_broken {
        if let Some((c, r)) = pending_resize {
            let _ = terminal_io(child.send_resize(c, r)).await;
        }
    }
    // Teardown starts here on EVERY exit path (shutdown, child death, send
    // failure). Flip closing first so a racing attach() never reuses us while
    // the restore/kill below is still in flight.
    session.bridges.lock().expect("bridges poisoned").closing = true;
    // Hand the viewport back before we go: the last restore request wins, so the pane does
    // not keep the phone's narrow grid after we detach. This runs on every exit path —
    // including when Herdr itself ended the controller (child death) — because the pane
    // keeps the last grid either way.
    restore_rx.close();
    let mut restore: Option<RestoreViewport> = None;
    while let Some(req) = restore_rx.recv().await {
        restore = Some(req);
    }
    if input_broken {
        // The old pipe may end in a partial NDJSON record. Never append a
        // resize or release to it: kill the controller, then perform the
        // grid restore through a fresh, valid control connection.
        child.kill_now().await;
        if let Some(req) = restore {
            // Same short-lived controller contract as /v1/pane/{id}/resize:
            // attach at the target grid, wait for the first frame, release.
            match TerminalChild::spawn(&herdr, &pane_id, false, req.cols, req.rows).await {
                Ok(mut fresh) => {
                    let landed = tokio::time::timeout(
                        std::time::Duration::from_secs(5),
                        fresh.next_record(),
                    )
                    .await;
                    let _ = fresh.send_release().await;
                    fresh.kill().await;
                    match landed {
                        Ok(Ok(Some(crate::herdr::terminal::ChildRecord::Frame(_)))) => {}
                        Ok(Ok(Some(crate::herdr::terminal::ChildRecord::Closed(reason)))) => {
                            warn!("viewport restore attach closed for {pane_id}: {reason}");
                        }
                        Ok(Ok(None)) => warn!("viewport restore attach ended without a frame for {pane_id}"),
                        Ok(Err(e)) => warn!("viewport restore attach failed for {pane_id}: {e:#}"),
                        Err(_) => warn!("viewport restore for {pane_id} timed out"),
                    }
                }
                Err(e) => warn!(
                    "cannot restore viewport for {pane_id} to {}x{} through a fresh controller: {e:#}",
                    req.cols, req.rows
                ),
            }
        }
    } else {
        if let Some(req) = restore {
            if child.is_alive()
                && terminal_io(child.send_resize(req.cols, req.rows)).await.is_err()
            {
                warn!("cannot restore viewport for {pane_id} to {}x{}", req.cols, req.rows);
            } else {
                // Give Herdr a beat to apply the geometry before release closes the controller.
                tokio::time::sleep(std::time::Duration::from_millis(250)).await;
            }
        }
        child.kill().await;
    }
    *session.last_close_reason.lock().expect("close reason poisoned") = Some(end_reason.clone());
    let _ = session.closed_tx.send(end_reason.clone());
    registry.remove(&pane_id, &session).await;
    info!("terminal session for pane {pane_id} ended ({end_reason})");
}

/// RAII guard: every run_bridge exit path (handshake failure, client close,
/// explicit release, frame-pump death) funnels through its Drop. The guard
/// decrements the bridge count exactly once, and only the last bridge wakes the
/// session for teardown — so a shared session survives one watcher going away,
/// and a dead handshake can never leave a ghost controller holding the resize
/// lock. Teardown goes through Notify, NOT the 64-deep command queue: a full
/// queue of coalesced resizes must never block the release.
struct BridgeGuard {
    session: Arc<TerminalSession>,
}
impl BridgeGuard {
    fn new(session: &Arc<TerminalSession>) -> Self {
        BridgeGuard { session: session.clone() }
    }
}
impl Drop for BridgeGuard {
    fn drop(&mut self) {
        // Never expect() in Drop: panicking during unwind aborts the process.
        // A poisoned mutex still yields its inner value — recover and proceed.
        let last = {
            let mut st = self
                .session
                .bridges
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            st.count = st.count.saturating_sub(1);
            if st.count == 0 {
                // Mark teardown before waking: a re-attach racing with the
                // pending shutdown must not reuse this dying session.
                st.closing = true;
                true
            } else {
                false
            }
        };
        if last {
            self.session.shutdown.notify_one();
        }
    }
}

async fn run_bridge(
    session: Arc<TerminalSession>,
    socket: WebSocket,
    registry: Arc<TerminalRegistry>,
    pane_id: String,
    frames_rx: broadcast::Receiver<SessionFrame>,
    closed_rx: broadcast::Receiver<String>,
    rows: u32,
) -> Result<()> {
    let _guard = BridgeGuard::new(&session);
    run_bridge_inner(session, socket, registry, pane_id, frames_rx, closed_rx, rows).await
}

async fn run_bridge_inner(
    session: Arc<TerminalSession>,
    socket: WebSocket,
    registry: Arc<TerminalRegistry>,
    pane_id: String,
    mut frames_rx: broadcast::Receiver<SessionFrame>,
    mut closed_rx: broadcast::Receiver<String>,
    rows: u32,
) -> Result<()> {
    // Receivers arrive pre-subscribed from bridge(): any await between attach
    // and subscribe lets attach_failed slip through with zero receivers.
    // Pre-populate the view before the first frame with ONE coherent
    // pane.read recent capture: up to 1000 lines of real Herdr transcript
    // ending at the visible screen. The client feeds it verbatim, so history
    // and screen can never come from two different instants.
    let prelude = build_attach_prelude(&registry.herdr, &pane_id, rows).await;

    let (mut sink, mut stream) = socket.split();

    // Replay the latest known geometry first so the client never sizes blind.
    // history_* report the prelude outcome (audit P1: silent history loss).
    let ready = serde_json::json!({
        "type": "ready",
        "paneId": pane_id,
        "cols": 0,
        "rows": 0,
        "encoding": "ansi",
        "resumed": false,
        "historyRows": prelude.history_rows,
        "historyTruncated": prelude.history_truncated,
        "historyError": prelude.history_error,
        "visibleOk": prelude.visible_ok,
    });
    sink.send(Message::Text(ready.to_string().into()))
        .await
        .context("terminal socket broke before ready")?;
    if !prelude.bytes.is_empty() {
        if sink.send(Message::Binary(prelude.bytes.into())).await.is_err() {
            return Ok(());
        }
    }

    let input_tx = session.input_tx.clone();
    // Client → child traffic. Returns a close reason when the bridge should stop pulling
    // frames, or None when the socket itself ended.
    let pump_client = async {
        while let Some(msg) = stream.next().await {
            let msg = msg.context("terminal socket read failed")?;
            match msg {
                Message::Binary(bytes) => {
                    if input_tx.send(SessionCommand::Input(bytes.to_vec())).await.is_err() {
                        return Ok::<Option<String>, anyhow::Error>(Some("session ended".to_string()));
                    }
                }
                Message::Text(text) => {
                    if !handle_client_text(&text, &input_tx, rows).await {
                        // Client asked for `release`: stop pulling; the single
                        // Release for this bridge goes out from the RAII guard at
                        // the bottom (only if we are the last bridge watching).
                        return Ok(None);
                    }
                }
                // Socket closed without an explicit release (app killed, network
                // drop, release frame lost in a race): same path as `release` —
                // stop pulling, let the guard decide about the session.
                Message::Close(_) => {
                    return Ok(None);
                }
                _ => {}
            }
        }
        Ok(None)
    };

    // Child → client traffic.
    let pump_frames = async {
        loop {
            tokio::select! {
                frame = frames_rx.recv() => {
                    match frame {
                        Ok(SessionFrame::Bytes(bytes)) => {
                            if sink.send(Message::Binary(bytes.into())).await.is_err() {
                                break;
                            }
                        }
                        Ok(SessionFrame::Ready { cols, rows, resumed }) => {
                            let ready = serde_json::json!({
                                "type": "ready", "paneId": pane_id,
                                "cols": cols, "rows": rows,
                                "encoding": "ansi", "resumed": resumed,
                            });
                            if sink.send(Message::Text(ready.to_string().into())).await.is_err() {
                                break;
                            }
                        }
                        Err(broadcast::error::RecvError::Lagged(n)) => {
                            // Dropped PTY bytes desync the emulator with no recovery:
                            // send a resync marker so the client re-attaches clean
                            // instead of rendering corrupt frames forever.
                            warn!("terminal bridge for {pane_id} lagged {n} frames; forcing resync");
                            let resync = serde_json::json!({
                                "type": "closed", "reason": "lagged_resync",
                            });
                            let _ = sink.send(Message::Text(resync.to_string().into())).await;
                            break;
                        }
                        Err(broadcast::error::RecvError::Closed) => break,
                    }
                }
                closed = closed_rx.recv() => {
                    // Owned String arrives: no guard crosses the awaits below.
                    let reason = closed.unwrap_or_else(|_| "detached".to_string());
                    let _ = sink.send(Message::Text(
                        serde_json::json!({"type": "closed", "reason": reason}).to_string().into(),
                    )).await;
                    let _ = sink.close().await;
                    break;
                }
            }
        }
    };

    tokio::select! {
        result = pump_client => {
            match result {
                Ok(Some(reason)) => {
                    let _ = sink.send(Message::Text(
                        serde_json::json!({"type": "closed", "reason": reason}).to_string().into(),
                    )).await;
                }
                Ok(None) => {
                    // Client went away (socket closed or `release`): tell it the stream is
                    // over so it never hangs waiting for bytes that will never come.
                    let _ = sink.send(Message::Text(
                        serde_json::json!({"type": "closed", "reason": "detached"}).to_string().into(),
                    )).await;
                }
                Err(e) => debug!("terminal bridge client pump ended: {e:#}"),
            }
        }
        _ = pump_frames => {}
    }
    // The BridgeGuard (run_bridge) owns session teardown: when this returns, the
    // guard decrements exactly once and only the last bridge sends Release.
    let _ = sink.close().await;
    Ok(())
}


fn base64_decode(text: &str) -> anyhow::Result<Vec<u8>> {
    use base64::Engine;
    base64::engine::general_purpose::STANDARD
        .decode(text)
        .map_err(|e| anyhow::anyhow!("bad base64: {e}"))
}

/// Reset used only by the visible-only fallback. The normal prelude is a
/// single `pane.read recent` capture and is fed verbatim.
const PRELUDE_CLEAR: &str = "\x1b[2J\x1b[H";

/// Outcome of the attach capture. `history_*` describe the transcript tail;
/// `visible_ok` says the prelude actually carries the live screen.
struct PreludeOutcome {
    bytes: Vec<u8>,
    history_rows: usize,
    history_truncated: bool,
    history_error: Option<String>,
    visible_ok: bool,
}

/// One pane.read(recent) is the whole prelude.
///
/// Herdr's `recent` source is a single coherent capture of scrollback PLUS the
/// visible screen (it ends at, and usually overlaps, the visible screen; less
/// and other alternate-screen apps return their screen as the tail). Feeding it
/// as one stream cannot splice two different points in time, needs no
/// content-based overlap guess, and keeps the real transcript in the engine's
/// local scrollback for panes without host scroll.
async fn build_attach_prelude(
    herdr: &crate::herdr::HerdrClient,
    pane_id: &str,
    rows: u32,
) -> PreludeOutcome {
    match herdr.pane_read_recent(pane_id, 1000).await {
        Ok(recent) if !recent.is_empty() => return prelude_from_recent(&recent, rows),
        Ok(_) => {}
        Err(e) => {
            let error = format!("{e:#}");
            tracing::warn!("attach transcript read failed for {pane_id} ({error}); trying visible");
            return prelude_from_visible(herdr, pane_id, Some(error)).await;
        }
    }

    // A genuinely blank screen (or a very young pane) can return an empty
    // transcript. Keep the visible-only fallback instead of attaching blank.
    prelude_from_visible(herdr, pane_id, None).await
}

async fn prelude_from_visible(
    herdr: &crate::herdr::HerdrClient,
    pane_id: &str,
    recent_error: Option<String>,
) -> PreludeOutcome {
    match herdr.pane_read_visible(pane_id).await {
        Ok(visible) => visible_prelude(&visible, recent_error),
        Err(e) => {
            tracing::warn!("attach visible read failed for {pane_id} ({e:#})");
            let error = match recent_error {
                Some(recent) => format!("recent fetch failed: {recent}; visible fetch failed: {e:#}"),
                None => format!("visible fetch failed: {e:#}"),
            };
            PreludeOutcome {
                bytes: vec![],
                history_rows: 0,
                history_truncated: false,
                history_error: Some(error),
                visible_ok: false,
            }
        }
    }
}

/// Fallback for a blank/failed transcript: paint only the live screen. This is
/// still one capture, never a splice of two reads.
fn visible_prelude(visible: &str, recent_error: Option<String>) -> PreludeOutcome {
    PreludeOutcome {
        bytes: format!("{PRELUDE_CLEAR}{visible}").into_bytes(),
        history_rows: 0,
        history_truncated: false,
        history_error: recent_error.map(|e| format!("recent fetch failed: {e}")),
        visible_ok: true,
    }
}

/// Pure part of the single-capture prelude: the transcript bytes are sent
/// verbatim. `rows` only estimates how many rows will scroll off the client's
/// viewport for diagnostics.
fn prelude_from_recent(recent: &str, rows: u32) -> PreludeOutcome {
    let lines = recent.lines().count();
    PreludeOutcome {
        bytes: recent.as_bytes().to_vec(),
        history_rows: lines.saturating_sub(rows.max(1) as usize),
        // Herdr caps `recent` at ~1000 lines; a full read may have been clipped.
        history_truncated: lines >= 1000,
        history_error: None,
        visible_ok: true,
    }
}

/// Returns false when the bridge should stop (client sent `release`).
async fn handle_client_text(
    text: &str,
    input_tx: &mpsc::Sender<SessionCommand>,
    viewport_rows: u32,
) -> bool {
    let value: serde_json::Value = match serde_json::from_str(text) {
        Ok(value) => value,
        Err(_) => return true, // malformed JSON is ignored, not fatal
    };
    let kind = value.get("type").and_then(|t| t.as_str()).unwrap_or("");
    let send = async |cmd: SessionCommand| input_tx.send(cmd).await.is_ok();
    match kind {
        "input.text" => {
            let text = value.get("text").and_then(|t| t.as_str()).unwrap_or("");
            if text.is_empty() {
                return true;
            }
            send(SessionCommand::Text(text.to_string())).await;
            true
        }
        "input.bytes" => {
            let encoded = value.get("bytes").and_then(|b| b.as_str()).unwrap_or("");
            let bytes = match base64_decode(encoded) {
                Ok(bytes) => bytes,
                Err(_) => return true, // malformed payload is ignored, not fatal
            };
            send(SessionCommand::Input(bytes)).await;
            true
        }
        "resize" => {
            let cols = value.get("cols").and_then(|c| c.as_u64()).unwrap_or(0) as u32;
            let rows = value.get("rows").and_then(|r| r.as_u64()).unwrap_or(0) as u32;
            if cols == 0 || rows == 0 {
                return true;
            }
            send(SessionCommand::Resize(cols, rows)).await;
            true
        }
        "scroll" => {
            let direction = value.get("direction").and_then(|d| d.as_str()).unwrap_or("down");
            let lines = (value.get("lines").and_then(|l| l.as_u64()).unwrap_or(5) as u32).max(1);
            // `unit` tells us what `lines` counts. The view only has whole
            // screens when the host has no scrollback, so it sends pages; the
            // rest of the pipeline works in rows, and one page is one viewport.
            let pages = value.get("unit").and_then(|u| u.as_str()) == Some("pages");
            let rows = if pages {
                lines.saturating_mul(viewport_rows.max(1))
            } else {
                lines
            };
            let up = direction != "down";
            let action = scroll_policy::scroll_action(ScrollRequest {
                backend: ScrollBackend::Herdr,
                app_identity: None,
                alt_screen: false,
                mouse_tracking: false,
                // Kept for the shared contract; the Herdr transport routes
                // page keys itself and never types into a shell.
                allow_page_key: value
                    .get("allowPageKey")
                    .and_then(|a| a.as_bool())
                    .unwrap_or(false),
                lines: if up { -(rows as i64) } else { rows as i64 },
            });
            if action != ScrollAction::Ignore {
                send(SessionCommand::Scroll(action)).await;
            }
            true
        }
        "host_scroll" => {
            let offset = value.get("offsetFromBottom").and_then(|o| o.as_u64()).unwrap_or(0);
            send(SessionCommand::HostScroll(offset)).await;
            true
        }
        "mouse" => {
            let action = value.get("action").and_then(|a| a.as_str()).unwrap_or("down").to_string();
            let button = value.get("button").and_then(|b| b.as_str()).unwrap_or("left").to_string();
            let column = value.get("column").and_then(|c| c.as_u64()).unwrap_or(0) as u32;
            let row = value.get("row").and_then(|r| r.as_u64()).unwrap_or(0) as u32;
            send(SessionCommand::Mouse(action, button, column, row)).await;
            true
        }
        "release" => false,
        _ => true, // unknown frames from a newer client: ignore
    }
}

/// Take the accepted user input out of the command queue, preserving order.
/// Viewport commands (resize/scroll/mouse) are not input and are dropped on
/// teardown; their only consumer is the session that is already leaving.
fn take_pending_input(rx: &mut mpsc::Receiver<SessionCommand>) -> Vec<SessionCommand> {
    let mut input = Vec::new();
    while let Ok(cmd) = rx.try_recv() {
        match cmd {
            SessionCommand::Input(_) | SessionCommand::Text(_) => input.push(cmd),
            _ => {}
        }
    }
    input
}

/// Forward input accepted before a release, bounded so a wedged child cannot
/// pin teardown. The client's release path waits seconds for this; the pipe
/// write normally completes immediately.
async fn flush_pending_input(child: &mut TerminalChild, rx: &mut mpsc::Receiver<SessionCommand>) -> bool {
    let deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(1);
    for cmd in take_pending_input(rx) {
        let sent = match cmd {
            SessionCommand::Input(bytes) => {
                tokio::time::timeout_at(deadline, child.send_input_bytes(&bytes)).await
            }
            SessionCommand::Text(text) => {
                tokio::time::timeout_at(deadline, child.send_input_text(&text)).await
            }
            _ => Ok(Ok(())),
        };
        if !matches!(sent, Ok(Ok(()))) {
            // The pipe may end in a partial line. The caller must kill it and
            // restore the grid through a fresh controller.
            return false;
        }
    }
    true
}

#[cfg(test)]
mod tests {
    use super::*;
    use futures_util::FutureExt;

    #[test]
    fn recent_capture_is_sent_verbatim() {
        // One capture: ANSI and CRLF stay byte-identical; no CLEAR and no
        // separate visible block that could come from another instant.
        let recent = "\x1b[32mh1\x1b[0m\r\nh2\nv1\nv2\n$ ";
        let prelude = prelude_from_recent(recent, 3);
        assert_eq!(prelude.bytes, recent.as_bytes());
        assert!(!prelude.bytes.starts_with(PRELUDE_CLEAR.as_bytes()));
        assert_eq!(prelude.history_rows, 2); // 5 lines - 3 viewport rows
        assert!(!prelude.history_truncated);
        assert!(prelude.visible_ok);
        assert_eq!(prelude.history_error, None);
    }

    #[test]
    fn recent_capture_reports_the_read_cap() {
        let recent = (1..=1000)
            .map(|n| format!("line-{n}"))
            .collect::<Vec<_>>()
            .join("\n");
        let prelude = prelude_from_recent(&recent, 40);
        assert!(prelude.history_truncated);
        assert_eq!(prelude.history_rows, 960);
    }

    #[test]
    fn visible_fallback_is_one_capture_after_a_clear() {
        let prelude = visible_prelude("v1\n$ ", Some("recent timeout".to_string()));
        assert!(prelude.bytes.starts_with(PRELUDE_CLEAR.as_bytes()));
        assert!(prelude.bytes.ends_with(b"v1\n$ "));
        assert_eq!(prelude.history_rows, 0);
        assert!(prelude.visible_ok);
        assert_eq!(
            prelude.history_error.as_deref(),
            Some("recent fetch failed: recent timeout")
        );
    }

    #[tokio::test]
    async fn release_flush_keeps_input_order_and_drops_viewport_ops() {
        let (tx, mut rx) = mpsc::channel(8);
        tx.send(SessionCommand::Text("A".to_string())).await.unwrap();
        tx.send(SessionCommand::Resize(80, 24)).await.unwrap();
        tx.send(SessionCommand::Input(b"B".to_vec())).await.unwrap();
        let pending = take_pending_input(&mut rx);
        assert_eq!(pending.len(), 2);
        match &pending[0] {
            SessionCommand::Text(text) => assert_eq!(text, "A"),
            other => panic!("unexpected first command: {other:?}"),
        }
        match &pending[1] {
            SessionCommand::Input(bytes) => assert_eq!(bytes, b"B"),
            other => panic!("unexpected second command: {other:?}"),
        }
    }

    #[tokio::test]
    async fn client_control_contract() {
        let (tx, mut rx) = mpsc::channel(8);
        assert!(handle_client_text(r#"{"type":"input.text","text":"echo hi\r"}"#, &tx, 24).await);
        assert!(handle_client_text(r#"{"type":"input.text","text":""}"#, &tx, 24).await);
        assert!(handle_client_text(r#"{"type":"input.bytes","bytes":"AQI="}"#, &tx, 24).await);
        assert!(handle_client_text(r#"{"type":"input.bytes","bytes":"!!!"}"#, &tx, 24).await);
        assert!(handle_client_text(r#"{"type":"resize","cols":100,"rows":30}"#, &tx, 24).await);
        assert!(handle_client_text(r#"{"type":"scroll","direction":"up","lines":5}"#, &tx, 24).await);
        assert!(handle_client_text(
            r#"{"type":"mouse","action":"down","button":"left","column":3,"row":4}"#,
            &tx,
            24,
        )
        .await);
        // unit=pages is converted to rows before the action leaves the parser.
        assert!(handle_client_text(
            r#"{"type":"scroll","direction":"up","lines":2,"unit":"pages"}"#,
            &tx,
            24
        )
        .await);
        assert!(handle_client_text("not json at all", &tx, 24).await);
        assert!(handle_client_text(r#"{"type":"something-new","x":1}"#, &tx, 24).await);
        assert!(!handle_client_text(r#"{"type":"release"}"#, &tx, 24).await);
        assert!(handle_client_text(r#"{"type":"resize","cols":0,"rows":0}"#, &tx, 24).await);

        let mut got = vec![];
        while let Ok(cmd) = rx.try_recv() {
            got.push(cmd);
        }
        assert_eq!(got.len(), 6);
        assert!(matches!(&got[0], SessionCommand::Text(t) if t == "echo hi\r"));
        assert!(matches!(&got[1], SessionCommand::Input(b) if b == &[1, 2]));
        assert!(matches!(got[2], SessionCommand::Resize(100, 30)));
        assert!(matches!(
            &got[3],
            SessionCommand::Scroll(a) if *a == ScrollAction::PageKey { up: true, rows: 5 }
        ));
        assert!(matches!(&got[4], SessionCommand::Mouse(a, b, 3, 4) if a == "down" && b == "left"));
        assert!(matches!(
            &got[5],
            SessionCommand::Scroll(a) if *a == ScrollAction::PageKey { up: true, rows: 48 }
        ));
    }

    /// The production BridgeGuard owns exactly-once teardown: dropping it
    /// decrements once, and only the last bridge over a session wakes run_session
    /// and marks it closing. This test exercises the REAL guard, not a copy —
    /// if the production Drop breaks, this goes red.
    #[test]
    fn bridge_guard_last_only_releases() {
        let session = test_session(2);

        // First bridge leaving: count drops to 1, no shutdown notification,
        // session still reusable.
        // NOTE: the count assert must run AFTER the guard drops (outside the
        // block) — inside, the guard is still alive and the count is still 2.
        {
            let _g = BridgeGuard::new(&session);
        }
        {
            let st = session.bridges.lock().expect("poisoned");
            assert_eq!(st.count, 1);
            assert!(!st.closing, "open session must stay reusable");
        }
        assert!(
            session.shutdown.notified().now_or_never().is_none(),
            "first bridge must not wake the session"
        );

        // Last bridge leaving: count hits 0, closing flips, shutdown fires once.
        {
            let _g = BridgeGuard::new(&session);
        }
        {
            let st = session.bridges.lock().expect("poisoned");
            assert_eq!(st.count, 0);
            assert!(st.closing, "drained session must refuse reuse");
        }
        assert!(
            session.shutdown.notified().now_or_never().is_some(),
            "last bridge must wake the session exactly once"
        );
        // Notify is a oneshot permit: a second wait finds nothing new.
        assert!(
            session.shutdown.notified().now_or_never().is_none(),
            "no duplicate wakeup"
        );
    }

    #[tokio::test]
    async fn attach_is_rejected_after_shutdown_started() {
        let registry = Arc::new(TerminalRegistry::new(
            HerdrClient::new("/nonexistent.sock".into(), "herdr".into()),
            Arc::new(SessionCache::new(HerdrClient::new(
                "/nonexistent.sock".into(),
                "herdr".into(),
            ))),
        ));
        registry.shutdown().await;
        let error = match registry.attach("w1:pX".to_string(), false, 80, 24).await {
            Ok(_) => panic!("a new controller must not start during shutdown"),
            Err(error) => error,
        };
        assert!(error.to_string().contains("shutting down"));
    }

    /// Test-only session with `n` bridges watching. Production attach() builds
    /// the real ones; this mirrors its fields without spawning run_session.
    fn test_session(n: usize) -> Arc<TerminalSession> {
        let (input_tx, _input_rx) = mpsc::channel::<SessionCommand>(8);
        let (frames_tx, _) = broadcast::channel::<SessionFrame>(8);
        let (closed_tx, _) = broadcast::channel::<String>(2);
        let (restore_tx, _restore_rx) = mpsc::channel::<RestoreViewport>(2);
        Arc::new(TerminalSession {
            pane_id: "w1:pX".to_string(),
            input_tx,
            frames_tx,
            closed_tx,
            last_close_reason: std::sync::Mutex::new(None),
            restore_tx,
            bridges: std::sync::Mutex::new(BridgeState { count: n, closing: false }),
            shutdown: tokio::sync::Notify::new(),
        })
    }
}
