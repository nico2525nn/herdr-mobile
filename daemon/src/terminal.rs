use std::collections::HashMap;
use std::sync::Arc;

use anyhow::{Context, Result};
use axum::extract::ws::{Message, WebSocket};
use futures_util::{SinkExt, StreamExt};
use tokio::sync::{broadcast, mpsc, Mutex};
use tracing::{debug, info, warn};

use crate::herdr::terminal::{ChildRecord, TerminalChild};
use crate::herdr::HerdrClient;

/// Live terminal attachments, keyed by pane id. Attaching twice reuses the session; every
/// attached bridge receives every frame.
pub struct TerminalRegistry {
    herdr: HerdrClient,
    sessions: Mutex<HashMap<String, Arc<TerminalSession>>>,
}

pub struct TerminalSession {
    pub pane_id: String,
    input_tx: mpsc::Sender<SessionCommand>,
    frames_tx: broadcast::Sender<SessionFrame>,
    closed_tx: broadcast::Sender<String>,
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

enum SessionCommand {
    /// Raw client bytes → child stdin.
    Input(Vec<u8>),
    /// UTF-8 text → child stdin as `terminal.input` text.
    Text(String),
    Resize(u32, u32),
    Scroll(String, u32),
    Mouse(String, String, u32, u32),
    Release,
}

impl TerminalRegistry {
    pub fn new(herdr: HerdrClient) -> TerminalRegistry {
        TerminalRegistry {
            herdr,
            sessions: Mutex::new(HashMap::new()),
        }
    }

    pub fn herdr(&self) -> &HerdrClient {
        &self.herdr
    }

    pub async fn shutdown(&self) {
        let sessions: Vec<Arc<TerminalSession>> = {
            let mut guard = self.sessions.lock().await;
            guard.drain().map(|(_, s)| s).collect()
        };
        for session in sessions {
            let _ = session.input_tx.send(SessionCommand::Release).await;
        }
        tokio::time::sleep(std::time::Duration::from_secs(2)).await;
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
        socket: WebSocket,
        pane_id: String,
        takeover: bool,
        cols: u32,
        rows: u32,
        restore: Option<(u32, u32)>,
    ) -> Result<()> {
        let session = self.attach(pane_id.clone(), takeover, cols, rows).await?;
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
        run_bridge(session, socket, self.clone(), pane_id).await
    }

    async fn attach(
        self: &Arc<Self>,
        pane_id: String,
        takeover: bool,
        cols: u32,
        rows: u32,
    ) -> Result<Arc<TerminalSession>> {
        // A closing session may still own the Herdr controller (restore resize +
        // up to 250ms beat + child.kill still in flight). Wait for its teardown
        // BEFORE spawning a fresh controller, or old and new overlap and the new
        // attach can fail under takeover=false. Registry lock is released while
        // waiting so run_session's remove() can proceed.
        //
        // The whole body loops: two attachers can both pass the empty-map check
        // concurrently (lock released during the wait), and the loser must fall
        // back to reusing the winner — never insert a second session.
        loop {
            let old = {
                let mut guard = self.sessions.lock().await;
                // Re-check under the CURRENT lock hold: a concurrent attacher may
                // have inserted while we waited/yielded.
                match guard.get(&pane_id) {
                    Some(session) => {
                        let reusable = {
                            let mut st = session.bridges.lock().expect("bridges poisoned");
                            if st.closing {
                                false
                            } else {
                                st.count += 1;
                                true
                            }
                        };
                        if reusable {
                            let session = session.clone();
                            drop(guard);
                            // Newest geometry wins; the session task coalesces it.
                            let _ = session
                                .input_tx
                                .send(SessionCommand::Resize(cols, rows))
                                .await;
                            return Ok(session);
                        }
                        // Closing: evict from the map now so no other attacher
                        // queues behind it, then wait for ITS teardown outside
                        // the registry lock.
                        guard.remove(&pane_id)
                    }
                    None => None,
                }
            };
            match old {
                None => {
                    // Fresh insert — but re-check under the lock first (TOCTOU
                    // against a concurrent inserter).
                    let mut guard = self.sessions.lock().await;
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
                Some(old) => {
                    // Bounded wait for the evicted session's teardown, then loop
                    // back and re-enter the normal path.
                    let _ = tokio::time::timeout(
                        std::time::Duration::from_secs(5),
                        old.closed_tx.subscribe().recv(),
                    )
                    .await;
                }
            }
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
            let _ = session.closed_tx.send("attach_failed".to_string());
            registry.remove(&pane_id, &session).await;
            return;
        }
    };
    info!("attached terminal session for pane {pane_id} ({cols}x{rows})");
    let _ = session.frames_tx.send(SessionFrame::Ready { cols, rows, resumed: false });
    let mut last_resize = tokio::time::Instant::now();
    let mut pending_resize: Option<(u32, u32)> = None;
    let mut end_reason = String::from("detached");
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
                        if child.send_input_bytes(&bytes).await.is_err() { break; }
                    }
                    SessionCommand::Text(text) => {
                        if child.send_input_text(&text).await.is_err() { break; }
                    }
                    SessionCommand::Resize(c, r) => {
                        // Coalesce resizes to at most one per 120 ms.
                        if last_resize.elapsed() >= std::time::Duration::from_millis(120) {
                            if child.send_resize(c, r).await.is_err() { break; }
                            last_resize = tokio::time::Instant::now();
                            let _ = session.frames_tx.send(SessionFrame::Ready { cols: c, rows: r, resumed: false });
                        } else {
                            pending_resize = Some((c, r));
                        }
                    }
                    SessionCommand::Scroll(direction, lines) => {
                        if child.send_scroll(&direction, lines).await.is_err() { break; }
                    }
                    SessionCommand::Mouse(action, button, column, row) => {
                        if child.send_mouse(&action, &button, column, row).await.is_err() { break; }
                    }
                    SessionCommand::Release => { end_reason = "released".to_string(); break; }
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
            _ = tokio::time::sleep(std::time::Duration::from_millis(120)), if pending_resize.is_some() => {
                if let Some((c, r)) = pending_resize.take() {
                    if child.send_resize(c, r).await.is_err() { break; }
                    last_resize = tokio::time::Instant::now();
                    let _ = session.frames_tx.send(SessionFrame::Ready { cols: c, rows: r, resumed: false });
                }
            }
        }
    }
    if let Some((c, r)) = pending_resize {
        let _ = child.send_resize(c, r).await;
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
    if let Some(req) = restore {
        if child.is_alive() && child.send_resize(req.cols, req.rows).await.is_err() {
            warn!("cannot restore viewport for {pane_id} to {}x{}", req.cols, req.rows);
        } else {
            // Give Herdr a beat to apply the geometry before release closes the controller.
            tokio::time::sleep(std::time::Duration::from_millis(250)).await;
        }
    }
    child.kill().await;
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
        let last = {
            let mut st = self.session.bridges.lock().expect("bridges poisoned");
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
) -> Result<()> {
    let _guard = BridgeGuard::new(&session);
    run_bridge_inner(session, socket, registry, pane_id).await
}

async fn run_bridge_inner(
    session: Arc<TerminalSession>,
    socket: WebSocket,
    registry: Arc<TerminalRegistry>,
    pane_id: String,
) -> Result<()> {
    // Pre-populate the view before the first frame: visible screen wrapped in home+clear.
    let prelude = match registry.herdr.pane_read_visible(&pane_id).await {
        Ok(text) => format!("\x1b[2J\x1b[H{text}").into_bytes(),
        Err(e) => {
            debug!("pane.read prelude failed for {pane_id} ({e:#}); starting from frames only");
            vec![]
        }
    };

    let (mut sink, mut stream) = socket.split();
    let mut frames_rx = session.frames_tx.subscribe();
    let mut closed_rx = session.closed_tx.subscribe();

    // Replay the latest known geometry first so the client never sizes blind.
    let ready = serde_json::json!({
        "type": "ready",
        "paneId": pane_id,
        "cols": 0,
        "rows": 0,
        "encoding": "ansi",
        "resumed": false,
    });
    sink.send(Message::Text(ready.to_string().into()))
        .await
        .context("terminal socket broke before ready")?;
    if !prelude.is_empty() {
        if sink.send(Message::Binary(prelude.into())).await.is_err() {
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
                    if !handle_client_text(&text, &input_tx).await {
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
                            debug!("terminal bridge for {pane_id} lagged {n} frames; continuing with live bytes");
                            continue;
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

/// Returns false when the bridge should stop (client sent `release`).
async fn handle_client_text(text: &str, input_tx: &mpsc::Sender<SessionCommand>) -> bool {
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
            let direction = value
                .get("direction")
                .and_then(|d| d.as_str())
                .unwrap_or("down")
                .to_string();
            let lines = value.get("lines").and_then(|l| l.as_u64()).unwrap_or(5) as u32;
            send(SessionCommand::Scroll(direction, lines.max(1))).await;
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

#[cfg(test)]
mod tests {
    use super::*;
    use futures_util::FutureExt;

    #[tokio::test]
    async fn client_control_contract() {
        let (tx, mut rx) = mpsc::channel(8);
        assert!(handle_client_text(r#"{"type":"input.text","text":"echo hi\r"}"#, &tx).await);
        assert!(handle_client_text(r#"{"type":"input.text","text":""}"#, &tx).await);
        assert!(handle_client_text(r#"{"type":"input.bytes","bytes":"AQI="}"#, &tx).await);
        assert!(handle_client_text(r#"{"type":"input.bytes","bytes":"!!!"}"#, &tx).await);
        assert!(handle_client_text(r#"{"type":"resize","cols":100,"rows":30}"#, &tx).await);
        assert!(handle_client_text(r#"{"type":"scroll","direction":"up","lines":5}"#, &tx).await);
        assert!(handle_client_text(
            r#"{"type":"mouse","action":"down","button":"left","column":3,"row":4}"#,
            &tx
        )
        .await);
        assert!(handle_client_text("not json at all", &tx).await);
        assert!(handle_client_text(r#"{"type":"something-new","x":1}"#, &tx).await);
        assert!(!handle_client_text(r#"{"type":"release"}"#, &tx).await);
        assert!(handle_client_text(r#"{"type":"resize","cols":0,"rows":0}"#, &tx).await);

        let mut got = vec![];
        while let Ok(cmd) = rx.try_recv() {
            got.push(cmd);
        }
        assert_eq!(got.len(), 5);
        assert!(matches!(&got[0], SessionCommand::Text(t) if t == "echo hi\r"));
        assert!(matches!(&got[1], SessionCommand::Input(b) if b == &[1, 2]));
        assert!(matches!(got[2], SessionCommand::Resize(100, 30)));
        assert!(matches!(&got[3], SessionCommand::Scroll(d, 5) if d == "up"));
        assert!(matches!(&got[4], SessionCommand::Mouse(a, b, 3, 4) if a == "down" && b == "left"));
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
            restore_tx,
            bridges: std::sync::Mutex::new(BridgeState { count: n, closing: false }),
            shutdown: tokio::sync::Notify::new(),
        })
    }
}
