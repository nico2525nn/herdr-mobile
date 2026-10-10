use std::collections::HashMap;
use std::sync::Arc;

use anyhow::Result;
use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::extract::{Path, Query, State};
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Json, Response};
use axum::routing::{get, post};
use axum::Router;
use futures_util::{SinkExt, StreamExt};
use serde_json::{json, Value};
use tokio::sync::broadcast;
use tracing::{debug, info, warn};

use crate::auth;
use crate::cache::SessionCache;
use crate::model::{AgentStatus, SemanticEvent, PROTOCOL};
use crate::{AppState, EventBus};

pub fn router(state: AppState) -> Router {
    Router::new()
        .route("/v1/health", get(health))
        .route("/v1/snapshot", get(snapshot))
        .route("/v1/workspaces", get(workspaces))
        .route("/v1/panes", get(panes))
        .route("/v1/events", get(events_ws))
        .route("/v1/terminal/{pane_id}", get(terminal_ws))
        .route("/v1/pane/{pane_id}/input", post(pane_input))
        .route("/v1/pane/{pane_id}/interrupt", post(pane_interrupt))
        .route("/v1/pane/{pane_id}/resize", post(pane_resize))
        .route("/v1/agent/report", post(agent_report))
        .route("/v1/tab", post(tab_create))
        .route("/v1/tab/{tab_id}/close", post(tab_close))
        .route("/v1/tab/{tab_id}/focus", post(tab_focus))
        .route("/v1/tab/{tab_id}/rename", post(tab_rename))
        .with_state(state)
}

// ---------------------------------------------------------------------------
// Auth
// ---------------------------------------------------------------------------

fn bearer(headers: &HeaderMap) -> Option<String> {
    // Header only. A query-string fallback would defeat the client's effort to
    // keep tokens out of URLs (proxies/logs/crash reports).
    headers
        .get(axum::http::header::AUTHORIZATION)
        .and_then(|h| h.to_str().ok())
        .and_then(|h| h.strip_prefix("Bearer "))
        .map(str::to_string)
}

fn check_auth(state: &AppState, headers: &HeaderMap) -> Result<(), Response> {
    // No token configured = open loopback daemon. The production path reaches
    // the daemon only through an SSH tunnel (key/password auth at the SSH
    // layer), so a second bearer adds setup friction without stopping any
    // attacker the tunnel doesn't already stop. Set --token only when binding
    // a non-loopback address (enforced at startup).
    let Some(configured) = state.config.token.as_deref() else {
        return Ok(());
    };
    match bearer(headers) {
        Some(presented) if auth::tokens_equal(configured, &presented) => Ok(()),
        _ => Err(api_error(StatusCode::UNAUTHORIZED, "unauthorized", "invalid or missing bearer token")),
    }
}

fn api_error(status: StatusCode, code: &str, message: impl Into<String>) -> Response {
    (
        status,
        Json(json!({"error": {"code": code, "message": message.into()}})),
    )
        .into_response()
}

fn herdr_error(e: anyhow::Error) -> Response {
    let msg = format!("{e:#}");
    if msg.contains("pane_not_found") {
        return api_error(StatusCode::NOT_FOUND, "pane_not_found", msg);
    }
    if msg.contains("tab_not_found") {
        return api_error(StatusCode::NOT_FOUND, "tab_not_found", msg);
    }
    if msg.contains("workspace_not_found") {
        return api_error(StatusCode::NOT_FOUND, "workspace_not_found", msg);
    }
    if msg.contains("cannot connect to herdr socket") {
        return api_error(StatusCode::SERVICE_UNAVAILABLE, "daemon_unavailable", msg);
    }
    api_error(StatusCode::BAD_GATEWAY, "herdr_error", msg)
}

// ---------------------------------------------------------------------------
// REST
// ---------------------------------------------------------------------------

async fn health(State(state): State<AppState>, headers: HeaderMap) -> Response {
    if let Err(e) = check_auth(&state, &headers) {
        return e;
    }
    let (connected, error, version, protocol) = state.cache.health_parts();
    let snapshot = state.cache.snapshot();
    let ok = connected && snapshot.is_some();
    (
        StatusCode::OK,
        Json(json!({
            "ok": ok,
            "revision": state.cache.revision(),
            "seq": state.cache.seq(),
            "uptimeMs": state.started_at.elapsed().as_millis() as i64,
            "protocol": PROTOCOL,
            "herdr": {
                "version": version,
                "protocol": protocol,
                "binary": state.config.herdr_bin,
            },
            "herdrConnected": connected,
            "error": error,
        })),
    )
        .into_response()
}

async fn snapshot(State(state): State<AppState>, headers: HeaderMap) -> Response {
    if let Err(e) = check_auth(&state, &headers) {
        return e;
    }
    match state.cache.snapshot() {
        Some(snapshot) => (StatusCode::OK, Json(snapshot)).into_response(),
        None => api_error(
            StatusCode::SERVICE_UNAVAILABLE,
            "daemon_unavailable",
            "no snapshot yet; herdr is unreachable",
        ),
    }
}

async fn workspaces(State(state): State<AppState>, headers: HeaderMap) -> Response {
    if let Err(e) = check_auth(&state, &headers) {
        return e;
    }
    match state.cache.snapshot() {
        Some(snapshot) => (
            StatusCode::OK,
            Json(json!({
                "revision": snapshot.revision,
                "seq": snapshot.seq,
                "workspaces": snapshot.workspaces,
            })),
        )
            .into_response(),
        None => api_error(
            StatusCode::SERVICE_UNAVAILABLE,
            "daemon_unavailable",
            "no snapshot yet; herdr is unreachable",
        ),
    }
}

async fn panes(State(state): State<AppState>, headers: HeaderMap) -> Response {
    if let Err(e) = check_auth(&state, &headers) {
        return e;
    }
    match state.cache.snapshot() {
        Some(snapshot) => {
            let panes: Vec<_> = snapshot
                .workspaces
                .iter()
                .flat_map(|w| w.tabs.iter())
                .flat_map(|t| t.panes.iter().cloned())
                .collect();
            (
                StatusCode::OK,
                Json(json!({
                    "revision": snapshot.revision,
                    "seq": snapshot.seq,
                    "panes": panes,
                })),
            )
                .into_response()
        }
        None => api_error(
            StatusCode::SERVICE_UNAVAILABLE,
            "daemon_unavailable",
            "no snapshot yet; herdr is unreachable",
        ),
    }
}

async fn pane_input(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(pane_id): Path<String>,
    Json(body): Json<Value>,
) -> Response {
    if let Err(e) = check_auth(&state, &headers) {
        return e;
    }
    let encoding = body.get("encoding").and_then(|e| e.as_str()).unwrap_or("utf-8");
    let text = body.get("text").and_then(|t| t.as_str()).unwrap_or("");
    let herdr = state.cache.herdr_client();
    let result = match encoding {
        "utf-8" => herdr.send_text(&pane_id, text).await,
        "base64" => match base64_decode(text) {
            Ok(bytes) => match String::from_utf8(bytes) {
                Ok(decoded) => herdr.send_text(&pane_id, &decoded).await,
                Err(_) => {
                    return api_error(StatusCode::BAD_REQUEST, "bad_request", "base64 payload is not valid UTF-8")
                }
            },
            Err(_) => {
                return api_error(StatusCode::BAD_REQUEST, "bad_request", "text is not valid base64")
            }
        },
        other => {
            return api_error(
                StatusCode::BAD_REQUEST,
                "bad_request",
                format!("unsupported encoding {other:?}; want utf-8 or base64"),
            )
        }
    };
    match result {
        Ok(()) => (StatusCode::OK, Json(json!({"ok": true}))).into_response(),
        Err(e) => herdr_error(e),
    }
}

fn base64_decode(text: &str) -> Result<Vec<u8>> {
    use base64::Engine;
    base64::engine::general_purpose::STANDARD
        .decode(text)
        .map_err(|e| anyhow::anyhow!("bad base64: {e}"))
}

async fn pane_interrupt(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(pane_id): Path<String>,
) -> Response {
    if let Err(e) = check_auth(&state, &headers) {
        return e;
    }
    match state.cache.herdr_client().send_keys(&pane_id, &["ctrl+c".to_string()]).await {
        Ok(()) => (StatusCode::OK, Json(json!({"ok": true}))).into_response(),
        Err(e) => herdr_error(e),
    }
}

async fn pane_resize(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(pane_id): Path<String>,
    Json(body): Json<Value>,
) -> Response {
    if let Err(e) = check_auth(&state, &headers) {
        return e;
    }
    let cols = body.get("cols").and_then(|c| c.as_u64()).unwrap_or(0) as u32;
    let rows = body.get("rows").and_then(|r| r.as_u64()).unwrap_or(0) as u32;
    if cols == 0 || rows == 0 {
        return api_error(StatusCode::BAD_REQUEST, "bad_request", "cols and rows must be greater than 0");
    }
    // A short-lived control child applies the geometry, then releases.
    let herdr = state.cache.herdr_client().clone();
    let pane = pane_id.clone();
    let task = tokio::spawn(async move {
        let mut child =
            crate::herdr::terminal::TerminalChild::spawn(&herdr, &pane, false, cols, rows).await?;
        // Wait for the child's first frame so the resize lands before we release.
        let landed = tokio::time::timeout(std::time::Duration::from_secs(5), child.next_record()).await;
        child.send_release().await?;
        child.kill().await;
        landed
            .map_err(|_| anyhow::anyhow!("timed out waiting for the resized frame"))?
            .map_err(|e| anyhow::anyhow!("resize attach failed: {e:#}"))?;
        Ok::<(), anyhow::Error>(())
    });
    match task.await {
        Ok(Ok(())) => (StatusCode::OK, Json(json!({"ok": true}))).into_response(),
        Ok(Err(e)) => herdr_error(e),
        Err(e) => api_error(StatusCode::INTERNAL_SERVER_ERROR, "resize_failed", format!("{e}")),
    }
}

async fn agent_report(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(body): Json<Value>,
) -> Response {
    if let Err(e) = check_auth(&state, &headers) {
        return e;
    }
    let Some(pane_id) = body.get("paneId").and_then(|v| v.as_str()) else {
        return api_error(StatusCode::BAD_REQUEST, "bad_request", "missing paneId");
    };
    let status = body
        .get("status")
        .and_then(|v| v.as_str());
    let status = AgentStatus::from_wire(status);
    let Some(agent) = body.get("agent").and_then(|v| v.as_str()) else {
        return api_error(StatusCode::BAD_REQUEST, "bad_request", "missing agent");
    };
    let message = body.get("message").and_then(|v| v.as_str());
    let source = body.get("source").and_then(|v| v.as_str()).unwrap_or("custom:herdr-mobile");
    match state
        .cache
        .herdr_client()
        .report_agent(pane_id, source, Some(agent), status.as_wire(), message)
        .await
    {
        Ok(()) => (StatusCode::OK, Json(json!({"ok": true}))).into_response(),
        Err(e) => {
            let msg = format!("{e:#}");
            if msg.contains("pane_not_found") || msg.contains("not found") {
                (
                    StatusCode::NOT_FOUND,
                    Json(json!({"ok": false, "error": {"code": "pane_not_found", "message": msg}})),
                )
                    .into_response()
            } else {
                herdr_error(e)
            }
        }
    }
}

async fn tab_create(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(body): Json<Value>,
) -> Response {
    if let Err(e) = check_auth(&state, &headers) {
        return e;
    }
    let Some(workspace_id) = body.get("workspaceId").and_then(|v| v.as_str()) else {
        return api_error(StatusCode::BAD_REQUEST, "bad_request", "missing workspaceId");
    };
    let label = body.get("label").and_then(|v| v.as_str());
    match state.cache.herdr_client().tab_create(workspace_id, label).await {
        Ok(tab_id) => (StatusCode::OK, Json(json!({"ok": true, "tabId": tab_id}))).into_response(),
        Err(e) => herdr_error(e),
    }
}

async fn tab_close(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(tab_id): Path<String>,
) -> Response {
    if let Err(e) = check_auth(&state, &headers) {
        return e;
    }
    match state.cache.herdr_client().tab_close(&tab_id).await {
        Ok(()) => (StatusCode::OK, Json(json!({"ok": true}))).into_response(),
        Err(e) => herdr_error(e),
    }
}

async fn tab_focus(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(tab_id): Path<String>,
) -> Response {
    if let Err(e) = check_auth(&state, &headers) {
        return e;
    }
    match state.cache.herdr_client().tab_focus(&tab_id).await {
        Ok(()) => (StatusCode::OK, Json(json!({"ok": true}))).into_response(),
        Err(e) => herdr_error(e),
    }
}

async fn tab_rename(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(tab_id): Path<String>,
    Json(body): Json<Value>,
) -> Response {
    if let Err(e) = check_auth(&state, &headers) {
        return e;
    }
    let Some(label) = body.get("label").and_then(|v| v.as_str()).map(str::trim) else {
        return api_error(StatusCode::BAD_REQUEST, "bad_request", "missing label");
    };
    if label.is_empty() {
        return api_error(StatusCode::BAD_REQUEST, "bad_request", "label must not be blank");
    }
    match state.cache.herdr_client().tab_rename(&tab_id, label).await {
        Ok(()) => (StatusCode::OK, Json(json!({"ok": true}))).into_response(),
        Err(e) => herdr_error(e),
    }
}

// ---------------------------------------------------------------------------
// Events WebSocket
// ---------------------------------------------------------------------------

async fn events_ws(
    State(state): State<AppState>,
    headers: HeaderMap,
    ws: WebSocketUpgrade,
) -> Response {
    if let Err(e) = check_auth(&state, &headers) {
        return e;
    }
    ws.on_upgrade(move |socket| serve_events(socket, state.bus.clone(), state.cache.clone()))
}

async fn serve_events(socket: WebSocket, bus: Arc<EventBus>, cache: Arc<SessionCache>) {
    let (mut sink, mut stream) = socket.split();
    let ready = SemanticEvent {
        seq: bus.cursor().max(cache.seq()),
        kind: "stream.ready".to_string(),
        at: crate::cache::now_rfc3339(),
        workspace_id: None,
        tab_id: None,
        pane_id: None,
        status: None,
        label: None,
        message: None,
        revision: Some(cache.revision()),
        detail: None,
    };
    if sink.send(Message::Text(serde_json::to_string(&ready).unwrap().into())).await.is_err() {
        return;
    }
    let mut rx: broadcast::Receiver<SemanticEvent> = bus.subscribe();
    info!("events subscriber connected");
    let mut last_seq: i64 = ready.seq;
    loop {
        tokio::select! {
            event = rx.recv() => {
                match event {
                    Ok(event) => {
                        last_seq = last_seq.max(event.seq);
                        let text = match serde_json::to_string(&event) {
                            Ok(text) => text,
                            Err(e) => {
                                warn!("cannot encode semantic event: {e}");
                                continue;
                            }
                        };
                        if sink.send(Message::Text(text.into())).await.is_err() {
                            break;
                        }
                    }
                    Err(broadcast::error::RecvError::Lagged(n)) => {
                        // We dropped events this subscriber never saw: force a refetch.
                        // Per-connection seq (last seen + 1), NOT the global cursor:
                        // minting globally makes healthy subscribers see a skip and
                        // refetch too — one slow phone storms everyone.
                        warn!("events subscriber lagged {n}; forcing snapshot refetch");
                        let required = SemanticEvent {
                            seq: last_seq + 1,
                            kind: "snapshot.required".to_string(),
                            at: crate::cache::now_rfc3339(),
                            workspace_id: None, tab_id: None, pane_id: None,
                            status: None, label: None, message: None,
                            revision: Some(cache.revision()),
                            detail: None,
                        };
                        if sink.send(Message::Text(serde_json::to_string(&required).unwrap().into())).await.is_err() {
                            break;
                        }
                    }
                    Err(broadcast::error::RecvError::Closed) => break,
                }
            }
            msg = stream.next() => {
                match msg {
                    Some(Ok(Message::Close(_))) | None => break,
                    Some(Ok(_)) => {
                        // The events socket is server-to-client; ignore client frames.
                        debug!("ignoring client frame on events socket");
                    }
                    Some(Err(_)) => break,
                }
            }
        }
    }
    info!("events subscriber disconnected");
}

// ---------------------------------------------------------------------------
// Terminal WebSocket
// ---------------------------------------------------------------------------

async fn terminal_ws(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<HashMap<String, String>>,
    Path(pane_id): Path<String>,
    ws: WebSocketUpgrade,
) -> Response {
    if let Err(e) = check_auth(&state, &headers) {
        return e;
    }
    let cols = query.get("cols").and_then(|c| c.parse::<u32>().ok()).unwrap_or(90).clamp(20, 400);
    let rows = query.get("rows").and_then(|r| r.parse::<u32>().ok()).unwrap_or(30).clamp(5, 200);
    let takeover = query.get("takeover").map(|t| t == "true").unwrap_or(false);
    // Viewport to hand back to Herdr on detach, so the pane does not keep the phone's
    // narrow grid after we go away. No hardcoded fallback here: when the client sends
    // nothing, the bridge learns the pane's own layout from Herdr at attach time.
    // `restore_cols=0` disables the restore.
    let restore = match (
        query.get("restore_cols").and_then(|c| c.parse::<u32>().ok()),
        query.get("restore_rows").and_then(|r| r.parse::<u32>().ok()),
    ) {
        (Some(0), _) => None,
        (Some(c), Some(r)) if c > 0 && r > 0 => Some((c.min(400), r.min(200))),
        _ => None,
    };
    let terminals = state.terminals.clone();
    ws.on_upgrade(move |socket| async move {
        if let Err(e) = terminals.bridge(socket, pane_id.clone(), takeover, cols, rows, restore).await {
            warn!("terminal bridge for {pane_id} ended: {e:#}");
        }
    })
}
