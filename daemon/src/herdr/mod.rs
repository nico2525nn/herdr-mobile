pub mod terminal;

use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};

use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::UnixStream;
use tracing::debug;

use crate::model::{HerdrInfo, HerdrSnapshot};

static REQUEST_COUNTER: AtomicU64 = AtomicU64::new(1);

/// NDJSON client for the Herdr Unix socket: one JSON object per line each way.
#[derive(Clone, Debug)]
pub struct HerdrClient {
    socket_path: PathBuf,
    pub herdr_bin: String,
}

impl HerdrClient {
    pub fn new(socket_path: String, herdr_bin: String) -> HerdrClient {
        HerdrClient {
            socket_path: PathBuf::from(socket_path),
            herdr_bin,
        }
    }

    pub fn socket_path(&self) -> &std::path::Path {
        &self.socket_path
    }

    fn next_id(prefix: &str) -> String {
        let n = REQUEST_COUNTER.fetch_add(1, Ordering::Relaxed);
        format!("{prefix}-{n}")
    }

    async fn open(&self) -> Result<UnixStream> {
        UnixStream::connect(&self.socket_path)
            .await
            .with_context(|| format!("cannot connect to herdr socket {}", self.socket_path.display()))
    }

    /// One request on a fresh connection; reads lines until the matching `id` responds.
    pub async fn request(&self, method: &str, params: Value) -> Result<Value> {
        let id = Self::next_id("hm");
        let line = serde_json::to_string(&json!({"id": id, "method": method, "params": params}))
            .context("cannot encode herdr request")?;
        let stream = self.open().await?;
        let (reader, mut writer) = stream.into_split();
        writer.write_all(line.as_bytes()).await?;
        writer.write_all(b"\n").await?;
        writer.flush().await?;
        let mut lines = BufReader::new(reader).lines();
        loop {
            let line = tokio::time::timeout(
                std::time::Duration::from_secs(15),
                lines.next_line(),
            )
            .await
            .context("herdr request timed out waiting for a matching response")?
            .context("herdr closed the connection mid-request")?
            .context("herdr closed the connection mid-request")?;
            let value: Value = serde_json::from_str(&line)
                .with_context(|| format!("herdr sent invalid JSON: {line:.200}"))?;
            // Herdr echoes the request id on decodable requests, but uses `"id":""` when the
            // request itself is invalid. Either way, an `error` line on our fresh connection
            // is ours: surface it instead of looping until Herdr hangs up.
            if let Some(error) = value.get("error") {
                let line_id = value.get("id").and_then(|v| v.as_str()).unwrap_or("");
                if line_id == id.as_str() || line_id.is_empty() {
                    let code = error.get("code").and_then(|c| c.as_str()).unwrap_or("herdr_error");
                    let message = error
                        .get("message")
                        .and_then(|m| m.as_str())
                        .unwrap_or("herdr request failed");
                    bail!("herdr error {code}: {message}");
                }
                continue;
            }
            let line_id = value.get("id").and_then(|v| v.as_str());
            if line_id != Some(id.as_str()) {
                // A pushed event (or another client's response) arriving on this fresh
                // connection: not ours, ignore it.
                debug!("ignoring unmatched herdr line while awaiting {id}");
                continue;
            }
            return value
                .get("result")
                .cloned()
                .context("herdr response has neither result nor error");
        }
    }

    pub async fn ping(&self) -> Result<HerdrInfo> {
        let result = self.request("ping", json!({})).await?;
        Ok(HerdrInfo {
            version: result.get("version").and_then(|v| v.as_str()).map(str::to_string),
            protocol: result.get("protocol").and_then(|v| v.as_i64()),
            binary: Some(self.herdr_bin.clone()),
        })
    }

    pub async fn snapshot(&self) -> Result<HerdrSnapshot> {
        let result = self.request("session.snapshot", json!({})).await?;
        let snapshot = result
            .get("snapshot")
            .cloned()
            .context("session.snapshot response has no snapshot")?;
        serde_json::from_value(snapshot).context("cannot decode herdr snapshot")
    }

    /// Long-lived subscription; yields pushed `{"event", "data"}` envelopes.
    pub async fn subscribe(&self, subscriptions: Vec<Value>) -> Result<EventSubscription> {
        let id = Self::next_id("sub");
        let line = serde_json::to_string(&json!({
            "id": id,
            "method": "events.subscribe",
            "params": {"subscriptions": subscriptions},
        }))
        .context("cannot encode subscribe request")?;
        let stream = self.open().await?;
        let (reader, mut writer) = stream.into_split();
        writer.write_all(line.as_bytes()).await?;
        writer.write_all(b"\n").await?;
        writer.flush().await?;
        let mut lines = BufReader::new(reader).lines();
        loop {
            let line = tokio::time::timeout(
                std::time::Duration::from_secs(15),
                lines.next_line(),
            )
            .await
            .context("herdr subscribe timed out waiting for acknowledgement")?
            .context("herdr closed the subscription before acknowledging")?
            .context("herdr closed the subscription before acknowledging")?;
            let value: Value = serde_json::from_str(&line)
                .with_context(|| format!("herdr sent invalid JSON: {line:.200}"))?;
            if value.get("id").and_then(|v| v.as_str()) != Some(id.as_str()) {
                continue;
            }
            if let Some(error) = value.get("error") {
                let code = error.get("code").and_then(|c| c.as_str()).unwrap_or("subscribe_failed");
                let message = error
                    .get("message")
                    .and_then(|m| m.as_str())
                    .unwrap_or("events.subscribe failed");
                if code == "events_lost" {
                    bail!("herdr events_lost: {message}");
                }
                bail!("herdr subscribe error {code}: {message}");
            }
            return Ok(EventSubscription { lines, _writer: writer });
        }
    }

    pub async fn report_agent(
        &self,
        pane_id: &str,
        source: &str,
        agent: Option<&str>,
        state: &str,
        message: Option<&str>,
    ) -> Result<()> {
        let mut params = serde_json::Map::new();
        params.insert("pane_id".into(), Value::String(pane_id.to_string()));
        params.insert("source".into(), Value::String(source.to_string()));
        if let Some(agent) = agent {
            params.insert("agent".into(), Value::String(agent.to_string()));
        }
        params.insert("state".into(), Value::String(state.to_string()));
        if let Some(message) = message {
            params.insert("message".into(), Value::String(message.to_string()));
        }
        self.request("pane.report_agent", Value::Object(params)).await?;
        Ok(())
    }

    /// Visible screen as ANSI text, used to pre-populate a terminal view before frames arrive.
    pub async fn pane_read_visible(&self, pane_id: &str) -> Result<String> {
        self.pane_read_source(pane_id, "visible", None).await
    }

    /// Up to `lines` of scrollback history (oldest first) as ANSI text. Herdr
    /// caps at ~1000 lines; fewer when the pane is young. Ends at (and usually
    /// overlaps) the visible screen — callers trim the overlap.
    pub async fn pane_read_recent(&self, pane_id: &str, lines: u32) -> Result<String> {
        self.pane_read_source(pane_id, "recent", Some(lines)).await
    }

    async fn pane_read_source(
        &self,
        pane_id: &str,
        source: &str,
        lines: Option<u32>,
    ) -> Result<String> {
        let mut params = serde_json::Map::from_iter([
            ("pane_id".to_string(), json!(pane_id)),
            ("source".to_string(), json!(source)),
            ("format".to_string(), json!("ansi")),
            ("strip_ansi".to_string(), json!(false)),
        ]);
        if let Some(lines) = lines {
            params.insert("lines".to_string(), json!(lines));
        }
        let result = self.request("pane.read", Value::Object(params)).await?;
        result
            .get("read")
            .and_then(|r| r.get("text"))
            .and_then(|t| t.as_str())
            .map(str::to_string)
            .context("pane.read response has no read.text")
    }

    /// Tab geometry for a pane: `(cols, rows)` of the pane rect as Herdr's layout
    /// engine reports it. Used to restore the TUI's own size on terminal detach
    /// instead of a hardcoded fallback that never matches any real window.
    pub async fn pane_layout_size(&self, pane_id: &str) -> Result<(u32, u32)> {
        let result = self.request("pane.layout", json!({"pane_id": pane_id})).await?;
        let layout = result.get("layout").context("pane.layout response has no layout")?;
        let area = layout.get("area").context("pane.layout response has no area")?;
        let w = area.get("width").and_then(|v| v.as_u64()).context("pane.layout area has no width")?;
        let h = area.get("height").and_then(|v| v.as_u64()).context("pane.layout area has no height")?;
        Ok((w.min(400) as u32, h.min(200) as u32))
    }

    pub async fn send_text(&self, pane_id: &str, text: &str) -> Result<()> {
        self.request("pane.send_text", json!({"pane_id": pane_id, "text": text}))
            .await?;
        Ok(())
    }

    pub async fn send_keys(&self, pane_id: &str, keys: &[String]) -> Result<()> {
        self.request("pane.send_keys", json!({"pane_id": pane_id, "keys": keys}))
            .await?;
        Ok(())
    }

    pub async fn tab_create(&self, workspace_id: &str, label: Option<&str>) -> Result<String> {
        let mut params = serde_json::Map::new();
        params.insert("workspace_id".into(), Value::String(workspace_id.to_string()));
        if let Some(label) = label {
            params.insert("label".into(), Value::String(label.to_string()));
        }
        params.insert("focus".into(), Value::Bool(true));
        let result = self.request("tab.create", Value::Object(params)).await?;
        result
            .get("tab")
            .and_then(|t| t.get("tab_id"))
            .and_then(|i| i.as_str())
            .map(str::to_string)
            .context("tab.create response has no tab.tab_id")
    }

    pub async fn tab_close(&self, tab_id: &str) -> Result<()> {
        self.request("tab.close", json!({"tab_id": tab_id})).await?;
        Ok(())
    }

    /// Focus a tab. Herdr treats focus as "read": a done agent transitions to
    /// idle (verified live: focus w1:tX moved w1:pZ done->idle). The mobile
    /// client calls this when the user OPENS a done pane, replacing the old
    /// client-side seen-mute with real server state every client agrees on.
    pub async fn tab_focus(&self, tab_id: &str) -> Result<()> {
        self.request("tab.focus", json!({"tab_id": tab_id})).await?;
        Ok(())
    }

    pub async fn tab_rename(&self, tab_id: &str, label: &str) -> Result<()> {
        self.request("tab.rename", json!({"tab_id": tab_id, "label": label})).await?;
        Ok(())
    }

    /// Best effort: notification delivery must never fail a request.
    pub async fn notification_show(&self, pane_id: &str) {
        if let Err(e) = self.request("notification.show", json!({"pane_id": pane_id})).await {
            debug!("notification.show failed (best effort): {e:#}");
        }
    }
}

/// An acknowledged, still-open event stream.
///
/// The write half is deliberately kept alive: dropping it would half-close the socket and
/// Herdr would treat the subscription as gone.
pub struct EventSubscription {
    lines: tokio::io::Lines<BufReader<tokio::net::unix::OwnedReadHalf>>,
    _writer: tokio::net::unix::OwnedWriteHalf,
}

impl EventSubscription {
    /// Next pushed envelope, or `None` when the socket closed.
    pub async fn next_event(&mut self) -> Result<Option<HerdrEvent>> {
        loop {
            let line = match self.lines.next_line().await {
                Ok(Some(line)) => line,
                Ok(None) => return Ok(None),
                Err(e) => return Err(e).context("herdr event stream broke"),
            };
            if line.trim().is_empty() {
                continue;
            }
            tracing::debug!("herdr event line: {}", line.chars().take(160).collect::<String>());
            let value: Value = match serde_json::from_str(&line) {
                Ok(value) => value,
                Err(e) => {
                    debug!("skipping invalid herdr event line: {e}");
                    continue;
                }
            };
            if value.get("event").and_then(|e| e.as_str()).is_none() {
                // Late ack or unmatched response line on a shared connection; ignore.
                continue;
            }
            return Ok(Some(HerdrEvent::parse(value)?));
        }
    }
}

#[derive(Clone, Debug)]
pub struct HerdrEvent {
    /// e.g. `pane_updated`, `workspace_renamed`. Always underscore form: Herdr
    /// sends structural events (`tab_created`) and status events
    /// (`pane.agent_status_changed`) in inconsistent forms, so dots are
    /// normalized here — every match arm below uses underscores.
    pub name: String,
    pub data: Value,
}

impl HerdrEvent {
    fn parse(value: Value) -> Result<HerdrEvent> {
        let name = value
            .get("event")
            .and_then(|e| e.as_str())
            .context("herdr event has no event name")?
            .replace('.', "_");
        if let Some(error) = value.get("error") {
            let code = error.get("code").and_then(|c| c.as_str()).unwrap_or("event_error");
            let message = error.get("message").and_then(|m| m.as_str()).unwrap_or("event error");
            if code == "events_lost" {
                bail!("herdr events_lost: {message}");
            }
            bail!("herdr event error {code}: {message}");
        }
        let data = value.get("data").cloned().unwrap_or(Value::Null);
        Ok(HerdrEvent { name, data })
    }
}
