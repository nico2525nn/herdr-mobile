use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Arc;

use anyhow::{anyhow, bail, Context, Result};
use serde_json::{json, Value};
use tokio::io::{
    AsyncBufRead, AsyncBufReadExt, AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, BufReader,
};
use tokio::net::UnixStream;
use tokio::time::{timeout, Duration};

pub mod terminal;

const JSON_CHANNEL: u32 = 0;
const FRAME_DATA: u8 = 0;
const FRAME_CREDIT: u8 = 1;
const FRAME_FACTS: u8 = 2;
const FRAME_RESIZE: u8 = 3;
const FRAME_FOCUS: u8 = 4;
const FRAME_CLOSE: u8 = 5;
const FRAME_HEADER_LEN: usize = 9;
const MAX_FRAME_LEN: usize = 16 * 1024 * 1024;
const SPACE_ORDER_LABEL: &str = "herdr.space_order";
const TAB_ORDER_LABEL: &str = "herdr.tab_order";

#[derive(Clone, Debug)]
pub struct CoreClient {
    socket_path: PathBuf,
    /// Serializes label read/update/compensation. Cloned clients share it, so
    /// two HTTP reorder requests cannot restore over each other's success.
    reorder_lock: Arc<tokio::sync::Mutex<()>>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CoreFocus {
    pub workspace_id: String,
    pub tab_id: String,
    pub pane_id: String,
}

impl CoreFocus {
    pub fn from_terminal(terminal: &Value) -> Option<Self> {
        Some(Self {
            workspace_id: workspace_id_of(terminal)?,
            tab_id: tab_id_of(terminal)?,
            pane_id: field_string(terminal, "address")?.to_owned(),
        })
    }
}

/// Which completion has been read, not merely which pane.
///
/// Core's `done` is an unseen completion and has no focus/seen RPC, so the
/// daemon presents it as idle after a read-ack. A pane-id set hid the NEXT
/// completion when it arrived while no event subscriber was connected. Store
/// the completion identity available on the terminal and only suppress a
/// `done` whose identity still matches; a new completion (new seq/turn)
/// surfaces immediately, with no dependence on subscriber ordering.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CoreReadMark {
    state_change_seq: Option<i64>,
    completion_seq: Option<i64>,
    turn: Option<i64>,
    /// Core's state-change timestamp. `turn` only increments when leaving
    /// `working`, so an idle -> done completion in the same turn is only
    /// distinguishable by this field.
    status_ms: Option<i64>,
}

impl CoreReadMark {
    fn identity(terminal: &Value) -> Self {
        fn number(value: &Value) -> Option<i64> {
            value
                .as_i64()
                .or_else(|| value.as_str().and_then(|text| text.parse().ok()))
        }
        let seq = |key: &str| {
            terminal
                .get(key)
                .and_then(number)
                .or_else(|| terminal.pointer(&format!("/status/{key}")).and_then(number))
        };
        Self {
            state_change_seq: seq("state_change_seq"),
            completion_seq: seq("completion_seq"),
            turn: seq("turn"),
            status_ms: seq("status_ms"),
        }
    }

    /// A mark is only meaningful for a pane that is currently done; focusing a
    /// working pane must not pre-acknowledge its future completion.
    pub fn for_terminal(terminal: &Value) -> Option<Self> {
        let state = terminal.pointer("/status/state").and_then(Value::as_str)?;
        (state == "done").then(|| Self::identity(terminal))
    }

    pub fn matches(&self, terminal: &Value) -> bool {
        *self == Self::identity(terminal)
    }
}

#[derive(Clone, Debug)]
pub struct CoreSnapshot {
    pub seq: i64,
    pub terminals: Vec<Value>,
    pub version: Option<String>,
}

/// A private cursor for one `events.subscribe` client; never shared globally.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct CoreEventCursor {
    seq: i64,
}

impl CoreEventCursor {
    pub fn new(seq: i64) -> Self {
        Self { seq }
    }

    pub fn seq(self) -> i64 {
        self.seq
    }

    /// Ignore duplicates/late records without moving this connection backwards.
    pub fn observe(&mut self, seq: i64) -> bool {
        if seq <= self.seq {
            return false;
        }
        self.seq = seq;
        true
    }

    /// A `missed` notification is authoritative and resets only this cursor.
    pub fn resync(&mut self, seq: i64) {
        self.seq = seq;
    }
}

const CORE_RPC_TIMEOUT: Duration = Duration::from_secs(15);
// Core emits a heartbeat every 15 s; three missed beats means wedged.
const CORE_STREAM_IDLE_TIMEOUT: Duration = Duration::from_secs(45);

impl CoreClient {
    pub fn new(socket_path: impl Into<PathBuf>) -> Self {
        Self {
            socket_path: socket_path.into(),
            reorder_lock: Arc::new(tokio::sync::Mutex::new(())),
        }
    }

    pub fn socket_path(&self) -> &PathBuf {
        &self.socket_path
    }

    async fn plain_connection(&self) -> Result<BufReader<UnixStream>> {
        let stream = UnixStream::connect(&self.socket_path)
            .await
            .with_context(|| format!("cannot connect to herdr-core socket {}", self.socket_path.display()))?;
        let mut reader = BufReader::new(stream);
        write_json_line(
            reader.get_mut(),
            &json!({"jsonrpc":"2.0","id":0,"method":"hello","params":{"generation":1,"framed":false}}),
        )
        .await?;
        let hello = timeout(CORE_RPC_TIMEOUT, read_json_line(&mut reader))
            .await
            .context("herdr-core hello timed out")??;
        let result = rpc_result(hello, 0)?;
        let generation = result.get("generation").and_then(Value::as_u64).unwrap_or(0);
        if generation != 1 {
            bail!("unsupported herdr-core generation {generation}");
        }
        Ok(reader)
    }

    async fn framed_connection(&self) -> Result<UnixStream> {
        let stream = UnixStream::connect(&self.socket_path)
            .await
            .with_context(|| format!("cannot connect to herdr-core socket {}", self.socket_path.display()))?;
        let mut reader = BufReader::new(stream);
        write_json_line(
            reader.get_mut(),
            &json!({"jsonrpc":"2.0","id":0,"method":"hello","params":{"generation":1,"framed":true}}),
        )
        .await?;
        let hello = timeout(CORE_RPC_TIMEOUT, read_json_line(&mut reader))
            .await
            .context("herdr-core hello timed out")??;
        let result = rpc_result(hello, 0)?;
        let generation = result.get("generation").and_then(Value::as_u64).unwrap_or(0);
        if generation != 1 || result.get("framed").and_then(Value::as_bool) != Some(true) {
            bail!("herdr-core did not negotiate framed generation 1");
        }
        Ok(reader.into_inner())
    }

    pub async fn call(&self, method: &str, params: Value) -> Result<Value> {
        let mut reader = self.plain_connection().await?;
        write_json_line(
            reader.get_mut(),
            &json!({"jsonrpc":"2.0","id":1,"method":method,"params":params}),
        )
        .await?;
        let response = timeout(CORE_RPC_TIMEOUT, read_json_line(&mut reader))
            .await
            .context("herdr-core RPC timed out")??;
        rpc_result(response, 1)
    }

    pub async fn snapshot(&self) -> Result<CoreSnapshot> {
        let subscription = self.subscribe().await?;
        Ok(subscription.initial.clone())
    }

    pub async fn subscribe(&self) -> Result<CoreSubscription> {
        let mut reader = self.plain_connection().await?;
        write_json_line(
            reader.get_mut(),
            &json!({"jsonrpc":"2.0","id":1,"method":"events.subscribe","params":{}}),
        )
        .await?;
        let response = timeout(CORE_RPC_TIMEOUT, read_json_line(&mut reader))
            .await
            .context("herdr-core events.subscribe timed out")??;
        let initial = rpc_result(response, 1)?;
        let seq = initial.get("seq").and_then(Value::as_i64).unwrap_or(0);
        let terminals = initial
            .get("terminals")
            .and_then(Value::as_array)
            .cloned()
            .unwrap_or_default();
        // read_json_line may have read past the response line. Move any
        // already-buffered bytes into our cancellation-safe line buffer.
        let buffered = reader.buffer().to_vec();
        reader.consume(buffered.len());
        Ok(CoreSubscription {
            reader,
            read_buf: buffered,
            scan_from: 0,
            initial: CoreSnapshot { seq, terminals, version: None },
        })
    }

    pub async fn list(&self, all: bool) -> Result<Vec<Value>> {
        let result = self.call("terminal.list", json!({"all":all})).await?;
        Ok(result.get("terminals").and_then(Value::as_array).cloned().unwrap_or_default())
    }

    pub async fn terminal(&self, address: &str) -> Result<Value> {
        self.call("terminal.get", json!({"terminal":address})).await
    }

    pub async fn attach(&self, address: &str, cols: u32, rows: u32, history: u32, observe: bool) -> Result<CoreAttachment> {
        let mut stream = self.framed_connection().await?;
        write_frame(
            &mut stream,
            JSON_CHANNEL,
            FRAME_DATA,
            &serde_json::to_vec(&json!({
                "jsonrpc":"2.0",
                "id":1,
                "method":"terminal.attach",
                "params":{
                    "terminal":address,
                    "cols":cols,
                    "rows":rows,
                    "history":history,
                    "observe":observe,
                },
            }))?,
        )
        .await?;
        let response = loop {
            let frame = timeout(CORE_RPC_TIMEOUT, read_frame(&mut stream))
                .await
                .context("herdr-core attach timed out")??;
            if frame.channel == JSON_CHANNEL && frame.kind == FRAME_DATA {
                let message: Value = serde_json::from_slice(&frame.payload).context("invalid herdr-core attach response")?;
                if message.get("id").and_then(Value::as_i64) == Some(1) {
                    break rpc_result(message, 1)?;
                }
            }
        };
        let channel = response
            .get("channel")
            .and_then(Value::as_u64)
            .and_then(|channel| u32::try_from(channel).ok())
            .ok_or_else(|| anyhow!("herdr-core attach response has no channel"))?;
        let terminal = response.get("terminal").cloned().unwrap_or(Value::Null);
        let history_rows = response
            .get("historyRows")
            .or_else(|| response.get("history"))
            .and_then(Value::as_u64)
            .unwrap_or(0) as u32;
        Ok(CoreAttachment {
            stream,
            read_buf: Vec::new(),
            channel,
            terminal,
            history_rows,
        })
    }

    pub async fn send_input(&self, address: &str, bytes: &[u8]) -> Result<()> {
        let terminal = self.terminal(address).await?;
        let cols = terminal.get("cols").and_then(Value::as_u64).unwrap_or(80) as u32;
        let rows = terminal.get("rows").and_then(Value::as_u64).unwrap_or(24) as u32;
        let mut attachment = self.attach(address, cols.max(1), rows.max(1), 0, false).await?;
        attachment.send_data(bytes).await?;
        attachment.close().await
    }

    pub async fn resize(&self, address: &str, cols: u16, rows: u16) -> Result<()> {
        let terminal = self.terminal(address).await?;
        let current_cols = terminal.get("cols").and_then(Value::as_u64).unwrap_or(80).min(u16::MAX as u64) as u16;
        let current_rows = terminal.get("rows").and_then(Value::as_u64).unwrap_or(24).min(u16::MAX as u64) as u16;
        let mut attachment = self
            .attach(
                address,
                current_cols.max(1) as u32,
                current_rows.max(1) as u32,
                0,
                false,
            )
            .await?;
        attachment.resize(cols, rows).await?;
        attachment.close().await
    }

    pub async fn key(&self, address: &str, keys: &[&str]) -> Result<Value> {
        self.call("terminal.keys", json!({"terminal":address,"keys":keys})).await
    }

    pub async fn label(&self, address: &str, labels: Value) -> Result<Value> {
        self.call("terminal.label", json!({"terminal":address,"labels":labels})).await
    }

    pub async fn run_shell(&self, cwd: Option<&str>, labels: Value) -> Result<Value> {
        let mut params = json!({"argv":[],"no_parent":true,"no_notify":true,"outlive_parent":true,"labels":labels});
        if let Some(cwd) = cwd {
            params["cwd"] = Value::String(cwd.to_owned());
        }
        self.call("terminal.run", params).await
    }

    pub async fn create_workspace(&self, label: Option<&str>, cwd: Option<&str>) -> Result<String> {
        let space_id = format!("core-space-{:016x}", rand::random::<u64>());
        let tab_id = format!("core-tab-{:016x}", rand::random::<u64>());
        let label = label.filter(|value| !value.trim().is_empty()).unwrap_or("Workspace");
        let labels = json!({
            "herdr.space":space_id,
            "herdr.space_label":label,
            "herdr.tab":tab_id,
            "herdr.tab_label":"1",
            "where":format!("{label} / 1"),
        });
        self.run_shell(cwd, labels).await?;
        Ok(space_id)
    }

    pub async fn create_tab(&self, workspace_id: &str, label: Option<&str>) -> Result<String> {
        let core = self.snapshot().await?;
        let snapshot = translate_snapshot(
            core.seq, &core.terminals, core.version, None,
            &std::collections::HashMap::new(),
        );
        let workspace = snapshot.workspaces.iter().find(|workspace| workspace.id == workspace_id)
            .ok_or_else(|| anyhow!("not_found: workspace {workspace_id}"))?;
        let workspace_label = workspace.label.clone();
        let cwd = workspace.path.clone();
        let tab_id = format!("core-tab-{:016x}", rand::random::<u64>());
        let tab_label = label.filter(|value| !value.trim().is_empty())
            .map(str::to_owned)
            .unwrap_or_else(|| (workspace.tabs.iter().map(|tab| tab.number).max().unwrap_or(0) + 1)
                .to_string());
        let labels = json!({
            "herdr.space":workspace_id,
            "herdr.space_label":workspace_label,
            "herdr.tab":tab_id,
            "herdr.tab_label":tab_label,
            "where":format!("{} / {}", workspace.label, tab_label),
        });
        self.run_shell(cwd.as_deref(), labels).await?;
        Ok(tab_id)
    }

    /// Write one order label per terminal, restoring already-applied labels
    /// if a later RPC fails. Core has no batch label RPC, so a mid-sequence
    /// failure used to leave a half-reordered session even though the caller
    /// received an error.
    async fn apply_order_labels(
        &self,
        key: &str,
        updates: &[(String, Option<i64>, i64)],
    ) -> Result<()> {
        for update in updates {
            let mut labels = serde_json::Map::new();
            labels.insert(key.to_owned(), Value::String(update.2.to_string()));
            if let Err(error) = self.label(&update.0, Value::Object(labels)).await {
                // A failed response does not prove the write did not land
                // (timeout/lost reply). Restore EVERY target, not only RPCs
                // that answered Ok, so an uncertain last write is covered too.
                for done in updates.iter().rev() {
                    let mut labels = serde_json::Map::new();
                    labels.insert(
                        key.to_owned(),
                        Value::String(done.1.unwrap_or(i64::MAX).to_string()),
                    );
                    if let Err(restore_error) =
                        self.label(&done.0, Value::Object(labels)).await
                    {
                        tracing::warn!(
                            "cannot restore {key} label on {}: {restore_error:#}",
                            done.0
                        );
                    }
                }
                return Err(error);
            }
        }
        Ok(())
    }

    /// Reorder spaces by writing an order label on every terminal in each
    /// space. Core has no native ordering method; labels are the durable,
    /// restart-safe source `translate_snapshot` sorts by.
    pub async fn move_workspace(&self, workspace_id: &str, insert_index: i64) -> Result<()> {
        let _guard = self.reorder_lock.lock().await;
        let terminals = self.list(false).await?;
        let mut order = ordered_workspace_ids(&terminals);
        let Some(from) = order.iter().position(|id| id == workspace_id) else {
            bail!("not_found: workspace {workspace_id}");
        };
        let item = order.remove(from);
        // The wire index counts the list WITH the item still in it (the
        // Kotlin Reorder.insertIndex contract). After remove() the target is
        // one slot lower for a downward move.
        let to = adjusted_insert_index(from, insert_index)
            .clamp(0, order.len() as i64) as usize;
        order.insert(to, item);
        let mut updates = Vec::new();
        for (index, id) in order.iter().enumerate() {
            for terminal in terminals.iter().filter(|t| workspace_id_of(t).as_deref() == Some(id)) {
                let Some(address) = field_string(terminal, "address") else { continue };
                let labels = terminal.get("labels").unwrap_or(&Value::Null);
                let old = labels.get(SPACE_ORDER_LABEL).and_then(|value| {
                    value.as_i64().or_else(|| value.as_str()?.parse().ok())
                });
                updates.push((address.to_owned(), old, index as i64));
            }
        }
        self.apply_order_labels(SPACE_ORDER_LABEL, &updates).await
    }

    /// Reorder tabs inside one workspace using the same label mechanism.
    pub async fn move_tab(&self, tab_id: &str, insert_index: i64) -> Result<()> {
        let _guard = self.reorder_lock.lock().await;
        let terminals = self.list(false).await?;
        let workspace = terminals
            .iter()
            .find(|t| tab_id_of(t).as_deref() == Some(tab_id))
            .map(|t| workspace_id_of(t))
            .flatten()
            .context("tab has no workspace label")?;
        let scoped: Vec<Value> = terminals
            .iter()
            .filter(|t| workspace_id_of(t).as_deref() == Some(workspace.as_str()))
            .cloned()
            .collect();
        let mut order = ordered_tab_ids(&scoped);
        let Some(from) = order.iter().position(|id| id == tab_id) else {
            bail!("not_found: tab {tab_id}");
        };
        let item = order.remove(from);
        let to = adjusted_insert_index(from, insert_index)
            .clamp(0, order.len() as i64) as usize;
        order.insert(to, item);
        let mut updates = Vec::new();
        for (index, id) in order.iter().enumerate() {
            for terminal in scoped.iter().filter(|t| tab_id_of(t).as_deref() == Some(id)) {
                let Some(address) = field_string(terminal, "address") else { continue };
                let labels = terminal.get("labels").unwrap_or(&Value::Null);
                let old = labels.get(TAB_ORDER_LABEL).and_then(|value| {
                    value.as_i64().or_else(|| value.as_str()?.parse().ok())
                });
                updates.push((address.to_owned(), old, index as i64));
            }
        }
        self.apply_order_labels(TAB_ORDER_LABEL, &updates).await
    }

    pub async fn close_tab(&self, tab_id: &str) -> Result<()> {
        let terminals = self.list(true).await?;
        let selected = terminals.into_iter().filter(|terminal| {
            tab_id_of(terminal).as_deref() == Some(tab_id)
        }).collect::<Vec<_>>();
        // Idempotent: a second tap after a concurrent tab.closed is a success,
        // not a 404 the app surfaces as a failure.
        self.close_selected(selected.into_iter()).await
    }

    pub async fn close_workspace(&self, workspace_id: &str) -> Result<()> {
        let terminals = self.list(true).await?;
        let selected = terminals.iter().filter(|terminal| {
            workspace_id_of(terminal).as_deref() == Some(workspace_id)
        }).cloned().collect::<Vec<_>>();
        // Idempotent like close_tab: a concurrent/duplicate close is success.
        self.close_selected(selected.into_iter()).await
    }

    async fn close_selected(&self, terminals: impl Iterator<Item = Value>) -> Result<()> {
        let mut closed = 0usize;
        let mut first_error: Option<anyhow::Error> = None;
        for terminal in terminals {
            let Some(address) = field_string(&terminal, "address") else { continue };
            let state = field_string(&terminal, "state").unwrap_or("unknown");
            let mut finished = state == "finished";
            if state == "live" {
                let result = self.call("terminal.kill", json!({"terminal":address})).await?;
                finished = field_string(&result, "state") == Some("finished");
            }
            if finished {
                match self.call("terminal.rm", json!({"terminal":address})).await {
                    Ok(_) => closed += 1,
                    Err(error) if format!("{error:#}").contains("not_found") => closed += 1,
                    Err(error) if format!("{error:#}").contains("live") => {
                        if first_error.is_none() {
                            first_error = Some(anyhow!("failed: terminal {address} is live"));
                        }
                    }
                    Err(error) => {
                        if first_error.is_none() { first_error = Some(error); }
                    }
                }
            } else if first_error.is_none() {
                first_error = Some(anyhow!("failed: terminal {address} is {state}"));
            }
        }
        // A multi-pane tab may close most of its panes; report success then.
        if closed > 0 || first_error.is_none() {
            Ok(())
        } else {
            Err(first_error.unwrap())
        }
    }

    pub async fn rename_tab(&self, tab_id: &str, label: &str) -> Result<()> {
        let terminals = self.list(false).await?;
        let matched: Vec<_> = terminals.into_iter().filter(|terminal| {
            tab_id_of(terminal).as_deref() == Some(tab_id)
        }).collect();
        if matched.is_empty() { bail!("not_found: tab {tab_id}"); }
        for terminal in matched {
            let address = field_string(&terminal, "address").unwrap_or_default();
            let labels = terminal.get("labels").unwrap_or(&Value::Null);
            let (old_space, _old_tab) = split_where(field_string(labels, "where"));
            let space = field_string(labels, "herdr.space_label").map(str::to_owned).or(old_space).unwrap_or_else(|| "Herdr core".to_owned());
            let mut updates = json!({"herdr.tab_label":label,"where":format!("{space} / {label}")});
            self.label(address, updates.take()).await?;
        }
        Ok(())
    }

    pub async fn rename_workspace(&self, workspace_id: &str, label: &str) -> Result<()> {
        let terminals = self.list(false).await?;
        let matched: Vec<_> = terminals.into_iter().filter(|terminal| {
            workspace_id_of(terminal).as_deref() == Some(workspace_id)
        }).collect();
        if matched.is_empty() { bail!("not_found: workspace {workspace_id}"); }
        for terminal in matched {
            let address = field_string(&terminal, "address").unwrap_or_default();
            let labels = terminal.get("labels").unwrap_or(&Value::Null);
            let (_, old_tab) = split_where(field_string(labels, "where"));
            let tab = field_string(labels, "herdr.tab_label").map(str::to_owned).or(old_tab).unwrap_or_else(|| "1".to_owned());
            self.label(address, json!({"herdr.space_label":label,"where":format!("{label} / {tab}")})).await?;
        }
        Ok(())
    }
}

pub struct CoreSubscription {
    reader: BufReader<UnixStream>,
    /// Bytes already read from the socket. `read_line` is not cancellation
    /// safe: the events WebSocket selects on this future and a client frame
    /// can drop it mid-line. Reading raw bytes with a cancel-safe `read` into
    /// this buffer preserves a partial JSON line across select rounds.
    read_buf: Vec<u8>,
    /// Where the next newline search starts. A line-less stream must not make
    /// every 8 KiB read rescan the whole buffer from byte zero.
    scan_from: usize,
    pub initial: CoreSnapshot,
}

/// Upper bound for one JSON event line. Core sends compact status records;
/// 16 MiB is far above any real event and still bounds a wedged/broken writer.
const CORE_MAX_EVENT_LINE: usize = 16 * 1024 * 1024;

impl CoreSubscription {
    pub async fn next(&mut self) -> Result<Option<Value>> {
        loop {
            let newline = self.read_buf[self.scan_from..]
                .iter()
                .position(|byte| *byte == b'\n')
                .map(|offset| self.scan_from + offset);
            if let Some(newline) = newline {
                if newline + 1 > CORE_MAX_EVENT_LINE {
                    bail!(
                        "herdr-core event line exceeds {} bytes",
                        CORE_MAX_EVENT_LINE
                    );
                }
                let line: Vec<u8> = self.read_buf.drain(..=newline).collect();
                self.scan_from = 0;
                let text = std::str::from_utf8(&line).context("herdr-core event is not UTF-8")?;
                let message: Value = serde_json::from_str(text)
                    .context("invalid herdr-core event notification")?;
                return Ok(Some(message));
            }
            if self.read_buf.len() > CORE_MAX_EVENT_LINE {
                bail!(
                    "herdr-core event line exceeds {} bytes without a newline",
                    CORE_MAX_EVENT_LINE
                );
            }
            let mut chunk = [0u8; 8192];
            // Bypass BufReader so a cancelled read cannot strand bytes in its
            // internal buffer. `read` itself is cancel-safe.
            let read = timeout(
                CORE_STREAM_IDLE_TIMEOUT,
                self.reader.get_mut().read(&mut chunk),
            )
            .await
            .context("herdr-core event stream went idle")??;
            if read == 0 {
                return Ok(None);
            }
            self.scan_from = self.read_buf.len();
            self.read_buf.extend_from_slice(&chunk[..read]);
        }
    }
}

pub struct CoreAttachment {
    stream: UnixStream,
    /// Partial frame bytes preserved across cancellation of `next_frame`.
    read_buf: Vec<u8>,
    pub channel: u32,
    pub terminal: Value,
    /// History rows the server says it painted before live data.
    pub history_rows: u32,
}

impl CoreAttachment {
    pub async fn grant_credit(&mut self, bytes: u32) -> Result<()> {
        write_frame(&mut self.stream, self.channel, FRAME_CREDIT, &bytes.to_le_bytes()).await
    }

    pub async fn send_data(&mut self, bytes: &[u8]) -> Result<()> {
        if !bytes.is_empty() {
            write_frame(&mut self.stream, self.channel, FRAME_DATA, bytes).await?;
        }
        Ok(())
    }

    pub async fn resize(&mut self, cols: u16, rows: u16) -> Result<()> {
        let mut payload = [0u8; 4];
        payload[..2].copy_from_slice(&cols.to_le_bytes());
        payload[2..].copy_from_slice(&rows.to_le_bytes());
        write_frame(&mut self.stream, self.channel, FRAME_RESIZE, &payload).await
    }

    pub async fn focus(&mut self) -> Result<()> {
        write_frame(&mut self.stream, self.channel, FRAME_FOCUS, &[]).await
    }

    pub async fn close(&mut self) -> Result<()> {
        write_frame(&mut self.stream, self.channel, FRAME_CLOSE, &[]).await
    }

    pub async fn next_frame(&mut self) -> Result<CoreFrame> {
        loop {
            if let Some(frame) = take_frame(&mut self.read_buf)? {
                return Ok(frame);
            }
            let mut chunk = [0u8; 8192];
            // `read_exact` is not cancellation safe. The terminal bridge
            // selects this future against client input, so read raw bytes into
            // our own buffer and only consume complete frames.
            let read = timeout(CORE_STREAM_IDLE_TIMEOUT, self.stream.read(&mut chunk))
                .await
                .context("herdr-core terminal stream went idle")??;
            if read == 0 {
                bail!("herdr-core closed the terminal stream");
            }
            self.read_buf.extend_from_slice(&chunk[..read]);
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CoreFrame {
    pub channel: u32,
    pub kind: u8,
    pub payload: Vec<u8>,
}

async fn write_json_line<W: AsyncWrite + Unpin>(writer: &mut W, message: &Value) -> Result<()> {
    let bytes = serde_json::to_vec(message)?;
    writer.write_all(&bytes).await?;
    writer.write_all(b"\n").await?;
    writer.flush().await?;
    Ok(())
}

async fn read_json_line<R: AsyncBufRead + Unpin>(reader: &mut R) -> Result<Value> {
    let mut line = String::new();
    let mut limited = reader.take(CORE_MAX_EVENT_LINE as u64 + 1);
    let read = limited.read_line(&mut line).await?;
    if read == 0 {
        bail!("herdr-core closed the socket before replying");
    }
    if read > CORE_MAX_EVENT_LINE {
        bail!(
            "herdr-core reply exceeds {} bytes",
            CORE_MAX_EVENT_LINE
        );
    }
    serde_json::from_str(&line).context("invalid herdr-core JSON-RPC response")
}

fn rpc_result(message: Value, id: i64) -> Result<Value> {
    if message.get("id").and_then(Value::as_i64) != Some(id) {
        bail!("herdr-core response id does not match request {id}");
    }
    if let Some(error) = message.get("error") {
        let kind = error.pointer("/data/kind").and_then(Value::as_str).unwrap_or("core_error");
        let detail = error.get("message").and_then(Value::as_str).unwrap_or("herdr-core request failed");
        bail!("{kind}: {detail}");
    }
    message.get("result").cloned().ok_or_else(|| anyhow!("herdr-core response has no result"))
}

async fn write_frame<W: AsyncWrite + Unpin>(writer: &mut W, channel: u32, kind: u8, payload: &[u8]) -> Result<()> {
    let length = u32::try_from(payload.len()).context("herdr-core frame is too large")?;
    let mut header = [0u8; FRAME_HEADER_LEN];
    header[..4].copy_from_slice(&channel.to_le_bytes());
    header[4] = kind;
    header[5..].copy_from_slice(&length.to_le_bytes());
    writer.write_all(&header).await?;
    writer.write_all(payload).await?;
    writer.flush().await?;
    Ok(())
}

fn take_frame(buffer: &mut Vec<u8>) -> Result<Option<CoreFrame>> {
    if buffer.len() < FRAME_HEADER_LEN {
        return Ok(None);
    }
    let channel = u32::from_le_bytes(buffer[..4].try_into().expect("4-byte channel"));
    let kind = buffer[4];
    let length = u32::from_le_bytes(buffer[5..FRAME_HEADER_LEN].try_into().expect("4-byte frame length")) as usize;
    if length > MAX_FRAME_LEN {
        return Err(anyhow!("herdr-core frame exceeds {} bytes", MAX_FRAME_LEN));
    }
    let total = FRAME_HEADER_LEN + length;
    if buffer.len() < total {
        return Ok(None);
    }
    let payload = buffer[FRAME_HEADER_LEN..total].to_vec();
    buffer.drain(..total);
    Ok(Some(CoreFrame { channel, kind, payload }))
}

async fn read_frame<R: AsyncRead + Unpin>(reader: &mut R) -> Result<CoreFrame> {
    let mut header = [0u8; FRAME_HEADER_LEN];
    reader.read_exact(&mut header).await?;
    let channel = u32::from_le_bytes(header[..4].try_into().expect("4-byte channel"));
    let kind = header[4];
    let length = u32::from_le_bytes(header[5..].try_into().expect("4-byte frame length")) as usize;
    if length > MAX_FRAME_LEN {
        return Err(anyhow!("herdr-core frame exceeds {} bytes", MAX_FRAME_LEN));
    }
    let mut payload = vec![0; length];
    reader.read_exact(&mut payload).await?;
    Ok(CoreFrame { channel, kind, payload })
}

fn status(value: &Value) -> crate::model::AgentStatus {
    use crate::model::AgentStatus;
    match value.as_str() {
        Some("blocked") => AgentStatus::Blocked,
        Some("working") => AgentStatus::Working,
        Some("done") => AgentStatus::Done,
        Some("idle") => AgentStatus::Idle,
        Some("error") => AgentStatus::Failed,
        _ => AgentStatus::Unknown,
    }
}

fn field_string<'a>(value: &'a Value, field: &str) -> Option<&'a str> {
    value.get(field).and_then(Value::as_str).filter(|s| !s.is_empty())
}

fn adjusted_insert_index(from: usize, insert_index: i64) -> i64 {
    if (from as i64) < insert_index {
        insert_index - 1
    } else {
        insert_index
    }
}

fn split_where(value: Option<&str>) -> (Option<String>, Option<String>) {
    let Some(where_label) = value else { return (None, None) };
    match where_label.split_once(" / ") {
        Some((space, tab)) => (Some(space.to_owned()), Some(tab.to_owned())),
        None => (Some(where_label.to_owned()), None),
    }
}

fn where_space(terminal: &Value) -> Option<String> {
    let labels = terminal.get("labels").unwrap_or(&Value::Null);
    split_where(field_string(labels, "where")).0
}

fn workspace_id_of(terminal: &Value) -> Option<String> {
    let labels = terminal.get("labels").unwrap_or(&Value::Null);
    field_string(labels, "herdr.space")
        .map(str::to_owned)
        .or_else(|| where_space(terminal).map(|space| format!("where:{space}")))
        .or_else(|| field_string(terminal, "address").map(|address| format!("core:{address}")))
}

pub(crate) fn tab_id_of(terminal: &Value) -> Option<String> {
    let labels = terminal.get("labels").unwrap_or(&Value::Null);
    field_string(labels, "herdr.tab")
        .map(str::to_owned)
        .or_else(|| {
            field_string(labels, "where")
                .map(|value| format!("where:{value}"))
        })
        .or_else(|| field_string(terminal, "address").map(str::to_owned))
}

fn order_label(labels: &Value, key: &str) -> i64 {
    labels.get(key)
        .and_then(|value| value.as_i64().or_else(|| value.as_str()?.parse().ok()))
        .unwrap_or(i64::MAX)
}

fn aggregate_order(
    terminals: &[Value],
    id_of: impl Fn(&Value) -> Option<String>,
    order_key: &str,
) -> HashMap<String, i64> {
    let mut orders: HashMap<String, i64> = HashMap::new();
    for terminal in terminals {
        let Some(id) = id_of(terminal) else { continue };
        let labels = terminal.get("labels").unwrap_or(&Value::Null);
        let order = order_label(labels, order_key);
        orders
            .entry(id)
            .and_modify(|current| *current = (*current).min(order))
            .or_insert(order);
    }
    orders
}

fn ordered_tab_ids(terminals: &[Value]) -> Vec<String> {
    let tab_orders = aggregate_order(terminals, tab_id_of, TAB_ORDER_LABEL);
    let mut seen: HashMap<String, (i64, usize)> = HashMap::new();
    for (index, terminal) in terminals.iter().enumerate() {
        let Some(id) = tab_id_of(terminal) else { continue };
        let order = tab_orders.get(&id).copied().unwrap_or(i64::MAX);
        let key = (order, index);
        seen.entry(id)
            .and_modify(|current| {
                if key < *current {
                    *current = key;
                }
            })
            .or_insert(key);
    }
    let mut ids: Vec<(String, (i64, usize))> = seen.into_iter().collect();
    ids.sort_by_key(|(_, key)| *key);
    ids.into_iter().map(|(id, _)| id).collect()
}

/// Workspaces sort by `(space_order, min tab_order, first terminal index)` in
/// `translate_snapshot`. Reordering must use the exact same key: sorting only
/// by `(space_order, index)` desynchronized the saved order from the rendered
/// order whenever two spaces had equal space labels.
fn ordered_workspace_ids(terminals: &[Value]) -> Vec<String> {
    // translate_snapshot aggregates space_order per workspace and tab_order
    // per tab, then sorts terminals. Reusing raw per-terminal labels here
    // picked a different terminal when a group had mixed order labels.
    let spaces = aggregate_order(terminals, workspace_id_of, SPACE_ORDER_LABEL);
    let tabs = aggregate_order(terminals, tab_id_of, TAB_ORDER_LABEL);
    let mut seen: HashMap<String, (i64, i64, usize)> = HashMap::new();
    for (index, terminal) in terminals.iter().enumerate() {
        let Some(id) = workspace_id_of(terminal) else { continue };
        let space = spaces.get(&id).copied().unwrap_or(i64::MAX);
        let tab = tab_id_of(terminal)
            .and_then(|tab| tabs.get(&tab).copied())
            .unwrap_or(i64::MAX);
        let key = (space, tab, index);
        seen.entry(id)
            .and_modify(|current| {
                if key < *current {
                    *current = key;
                }
            })
            .or_insert(key);
    }
    let mut ids: Vec<(String, (i64, i64, usize))> = seen.into_iter().collect();
    ids.sort_by_key(|(_, key)| *key);
    ids.into_iter().map(|(id, _)| id).collect()
}

pub fn translate_snapshot(
    seq: i64,
    terminals: &[Value],
    version: Option<String>,
    focus: Option<&CoreFocus>,
    read_panes: &HashMap<String, CoreReadMark>,
) -> crate::model::SessionSnapshot {
    use crate::model::{AgentStatus, Focus, HerdrInfo, HerdrUpdate, Pane, SessionSnapshot, Tab, Workspace};
    use std::collections::BTreeMap;

    let mut workspaces = Vec::<Workspace>::new();
    let mut workspace_indices = HashMap::<String, usize>::new();
    let mut tab_indices = HashMap::<(String, String), usize>::new();

    // Order labels live on terminals, but a space/tab may gain a terminal
    // after the move (no label on the newcomer). Aggregate at the group level
    // so the whole space/tab keeps its position.
    let mut space_orders: HashMap<String, i64> = HashMap::new();
    let mut tab_orders: HashMap<String, i64> = HashMap::new();
    for terminal in terminals {
        let labels = terminal.get("labels").unwrap_or(&Value::Null);
        if let Some(id) = workspace_id_of(terminal) {
            let order = order_label(labels, SPACE_ORDER_LABEL);
            space_orders.entry(id).and_modify(|value| *value = (*value).min(order)).or_insert(order);
        }
        if let Some(id) = tab_id_of(terminal) {
            let order = order_label(labels, TAB_ORDER_LABEL);
            tab_orders.entry(id).and_modify(|value| *value = (*value).min(order)).or_insert(order);
        }
    }
    let mut ordered: Vec<(usize, &Value)> = terminals.iter().enumerate().collect();
    ordered.sort_by_key(|(index, terminal)| {
        let space = workspace_id_of(terminal)
            .and_then(|id| space_orders.get(&id).copied())
            .unwrap_or(i64::MAX);
        let tab = tab_id_of(terminal)
            .and_then(|id| tab_orders.get(&id).copied())
            .unwrap_or(i64::MAX);
        (space, tab, *index)
    });
    for (_, terminal) in ordered {
        let Some(address) = field_string(terminal, "address") else { continue };
        let labels = terminal.get("labels").unwrap_or(&Value::Null);
        let (where_space, where_tab) = split_where(field_string(labels, "where"));
        let workspace_id = workspace_id_of(terminal).unwrap_or_else(|| format!("core:{address}"));
        let workspace_label = field_string(labels, "herdr.space_label")
            .map(str::to_owned)
            .or(where_space)
            .unwrap_or_else(|| terminal.get("name").and_then(Value::as_str).unwrap_or(address).to_owned());
        let tab_id = tab_id_of(terminal).unwrap_or_else(|| address.to_owned());
        let tab_label = field_string(labels, "herdr.tab_label")
            .map(str::to_owned)
            .or(where_tab)
            .unwrap_or_else(|| terminal.get("name").and_then(Value::as_str).unwrap_or(address).to_owned());
        let cwd = field_string(terminal, "cwd").map(str::to_owned);
        let mut status_value = terminal.pointer("/status/state").map(status).unwrap_or(AgentStatus::Unknown);
        // Core's `done` is an unseen completion (it has no focus/seen RPC).
        // Present only the completion this daemon actually read as idle; a
        // later completion has a new identity and stays done.
        if status_value == AgentStatus::Done
            && read_panes.get(address).is_some_and(|mark| mark.matches(terminal))
        {
            status_value = AgentStatus::Idle;
        }
        // Core `Status.app` is the OSC 7501 app identity consumed by AgentMatrix.
        // `foreground.name` is any process (e.g. `sleep`), not an agent kind.
        let agent = terminal.pointer("/status/app").and_then(Value::as_str)
            .map(str::trim)
            .filter(|app| !app.is_empty())
            .map(str::to_owned);
        let title = field_string(terminal, "title").map(str::to_owned);
        let wheel_rows_per_event = crate::agent_config::wheel_rows_per_event(agent.as_deref());
        let pane = Pane {
            id: address.to_owned(),
            tab_id: tab_id.clone(),
            workspace_id: workspace_id.clone(),
            label: terminal.get("name").and_then(Value::as_str).map(str::to_owned),
            title,
            agent,
            status: status_value,
            cwd: cwd.clone(),
            terminal_id: Some(address.to_owned()),
            focused: false,
            revision: seq,
            tokens: BTreeMap::new(),
            // The core protocol exposes PTY data only: there is no host
            // scrollback offset to report.
            scroll: None,
            wheel_rows_per_event,
        };

        let workspace_index = *workspace_indices.entry(workspace_id.clone()).or_insert_with(|| {
            let index = workspaces.len();
            workspaces.push(Workspace {
                id: workspace_id.clone(),
                label: workspace_label,
                path: cwd.clone(),
                home: None,
                number: index as i64 + 1,
                status: AgentStatus::Unknown,
                focused: false,
                active_tab_id: None,
                tabs: Vec::new(),
                tokens: BTreeMap::new(),
            });
            index
        });
        let workspace = &mut workspaces[workspace_index];
        let tab_key = (workspace_id.clone(), tab_id.clone());
        let tab_index = *tab_indices.entry(tab_key).or_insert_with(|| {
            let index = workspace.tabs.len();
            let number = field_string(labels, "where")
                .and_then(|value| value.rsplit_once(" / ").map(|(_, suffix)| suffix))
                .and_then(|value| value.parse::<i64>().ok())
                .unwrap_or(index as i64 + 1);
            workspace.tabs.push(Tab {
                id: tab_id.clone(),
                workspace_id: workspace_id.clone(),
                label: tab_label,
                number,
                status: AgentStatus::Unknown,
                focused: false,
                panes: Vec::new(),
            });
            index
        });
        let tab = &mut workspace.tabs[tab_index];
        tab.status = AgentStatus::roll_up([tab.status, status_value]).unwrap_or(status_value);
        tab.panes.push(pane);
        workspace.status = AgentStatus::roll_up([workspace.status, status_value]).unwrap_or(status_value);
    }

    // A closed pane/tab must not leave a dangling focus in the snapshot.
    let focus = focus.filter(|focus| {
        terminals.iter().any(|terminal| {
            terminal.get("address").and_then(Value::as_str) == Some(focus.pane_id.as_str())
        })
    });
    if let Some(focus) = focus {
        for workspace in &mut workspaces {
            workspace.focused = workspace.id == focus.workspace_id;
            if workspace.id == focus.workspace_id {
                workspace.active_tab_id = Some(focus.tab_id.clone());
            }
            for tab in &mut workspace.tabs {
                tab.focused = tab.id == focus.tab_id;
                for pane in &mut tab.panes {
                    pane.focused = pane.id == focus.pane_id;
                }
            }
        }
    }
    SessionSnapshot {
        revision: seq,
        seq,
        captured_at: Some(crate::cache::now_rfc3339()),
        herdr: HerdrInfo { version, protocol: Some(1), binary: Some("herdr-core".to_owned()) },
        update: HerdrUpdate::default(),
        focus: Focus {
            workspace_id: focus.map(|f| f.workspace_id.clone()),
            tab_id: focus.map(|f| f.tab_id.clone()),
            pane_id: focus.map(|f| f.pane_id.clone()),
        },
        workspaces,
    }
}

pub fn locations(terminals: &[Value]) -> HashMap<String, (String, String)> {
    terminals.iter().filter_map(|terminal| {
        let address = field_string(terminal, "address")?;
        let workspace = workspace_id_of(terminal).unwrap_or_else(|| format!("core:{address}"));
        let tab = tab_id_of(terminal).unwrap_or_else(|| address.to_owned());
        Some((address.to_owned(), (workspace, tab)))
    }).collect()
}

pub fn translate_event(message: &Value, locations: &HashMap<String, (String, String)>) -> Option<crate::model::SemanticEvent> {
    use crate::model::SemanticEvent;
    let params = message.get("params").unwrap_or(message);
    let event_type = field_string(params, "type")?;
    let seq = params.get("seq").and_then(Value::as_i64).unwrap_or(0);
    let terminal = field_string(params, "terminal");
    let data = params.get("data").unwrap_or(&Value::Null);
    let (workspace_id, tab_id, pane_id) = terminal.map(|id| {
        let location = locations.get(id);
        (location.map(|v| v.0.clone()), location.map(|v| v.1.clone()), Some(id.to_owned()))
    }).unwrap_or((None, None, None));
    let (kind, status, label) = match event_type {
        "created" => ("pane.created", None, None),
        "removed" | "finished" => ("pane.closed", None, None),
        "lost" | "restarted" | "labels" => ("snapshot.required", None, None),
        "status" => {
            let state = data.pointer("/status/state")
                .or_else(|| data.get("state"))
                .or_else(|| data.get("status"));
            ("pane.status_changed", state.map(status).filter(|value| *value != crate::model::AgentStatus::Unknown), None)
        }
        "title" => ("pane.title_changed", None, data.get("title").and_then(Value::as_str).map(str::to_owned)),
        _ => ("core.event", None, None),
    };
    Some(SemanticEvent {
        seq,
        kind: kind.to_owned(),
        at: crate::cache::now_rfc3339(),
        workspace_id,
        tab_id,
        pane_id,
        status,
        label,
        message: None,
        revision: None,
        detail: Some(data.clone()),
    })
}

#[cfg(test)]
pub fn encode_frame(channel: u32, kind: u8, payload: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(FRAME_HEADER_LEN + payload.len());
    out.extend_from_slice(&channel.to_le_bytes());
    out.push(kind);
    out.extend_from_slice(&(payload.len() as u32).to_le_bytes());
    out.extend_from_slice(payload);
    out
}

#[cfg(test)]
pub fn encode_resize(cols: u16, rows: u16) -> [u8; 4] {
    let mut payload = [0u8; 4];
    payload[..2].copy_from_slice(&cols.to_le_bytes());
    payload[2..].copy_from_slice(&rows.to_le_bytes());
    payload
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn snapshot_translates_core_terminals_to_spaces_tabs_and_panes() {
        let snapshot = translate_snapshot(17, &[json!({
            "address":"t7", "cwd":"/work", "title":"Codex", "state":"live", "turn":3,
            "status":{"state":"working","app":"codex"},
            "labels":{"herdr.space":"space-1","herdr.tab":"tab-1","where":"Project / 2"}
        })], Some("0.1.14".to_owned()), None, &std::collections::HashMap::new());
        assert_eq!(snapshot.seq, 17);
        assert_eq!(snapshot.workspaces[0].id, "space-1");
        assert_eq!(snapshot.workspaces[0].label, "Project");
        assert_eq!(snapshot.workspaces[0].tabs[0].id, "tab-1");
        assert_eq!(snapshot.workspaces[0].tabs[0].label, "2");
        assert_eq!(snapshot.workspaces[0].tabs[0].panes[0].terminal_id.as_deref(), Some("t7"));
        assert_eq!(snapshot.workspaces[0].tabs[0].panes[0].status, crate::model::AgentStatus::Working);
        assert_eq!(snapshot.workspaces[0].tabs[0].panes[0].agent.as_deref(), Some("codex"));
    }

    #[test]
    fn workspace_reorder_uses_the_snapshot_tie_breaker() {
        // Equal space_order is resolved by the minimum tab_order in both the
        // rendered snapshot and the move source list. Raw enumeration order
        // (A before B) must not win here.
        let terminals = vec![
            json!({"address":"a1","labels":{"herdr.space":"A","herdr.space_order":0,"herdr.tab":"a1","herdr.tab_order":5}}),
            json!({"address":"b1","labels":{"herdr.space":"B","herdr.space_order":0,"herdr.tab":"b1","herdr.tab_order":0}}),
            // A's winning tab_order is on a later terminal; the snapshot
            // tie-breaks by that terminal's index, not A's first index.
            json!({"address":"a2","labels":{"herdr.space":"A","herdr.space_order":0,"herdr.tab":"a2","herdr.tab_order":0}}),
        ];
        let snapshot = translate_snapshot(1, &terminals, None, None, &std::collections::HashMap::new());
        let rendered: Vec<_> = snapshot.workspaces.iter().map(|w| w.id.as_str()).collect();
        assert_eq!(rendered, ["B", "A"]);
        assert_eq!(ordered_workspace_ids(&terminals), ["B", "A"]);
    }

    #[test]
    fn reorder_uses_snapshot_aggregated_group_keys() {
        // A's winning space_order and tab_order live on different terminals.
        // The snapshot aggregates each key per group; a per-terminal minimum
        // tuple picks A's (0,5,2) instead of the rendered (0,0,0).
        let terminals = vec![
            json!({"address":"a1","labels":{"herdr.space":"A","herdr.space_order":5,"herdr.tab":"a1","herdr.tab_order":0}}),
            json!({"address":"b1","labels":{"herdr.space":"B","herdr.space_order":0,"herdr.tab":"b1","herdr.tab_order":0}}),
            json!({"address":"a2","labels":{"herdr.space":"A","herdr.space_order":0,"herdr.tab":"a2","herdr.tab_order":5}}),
        ];
        let snapshot = translate_snapshot(1, &terminals, None, None, &std::collections::HashMap::new());
        let rendered: Vec<_> = snapshot.workspaces.iter().map(|w| w.id.as_str()).collect();
        assert_eq!(rendered, ["A", "B"]);
        assert_eq!(ordered_workspace_ids(&terminals), ["A", "B"]);
    }

    #[test]
    fn reorder_adjusts_for_the_removed_item() {
        assert_eq!(adjusted_insert_index(0, 2), 1); // A to middle -> [B,A,C]
        assert_eq!(adjusted_insert_index(0, 1), 0); // already there
        assert_eq!(adjusted_insert_index(2, 0), 0); // upward
        assert_eq!(adjusted_insert_index(0, 3), 2); // to end
    }

    #[test]
    fn core_foreground_process_is_not_mistaken_for_agent() {
        // Regression for bug class #2: `foreground.name` may be `sleep`, a shell,
        // or any other command; only the Status.app field selects AgentMatrix routing.
        let snapshot = translate_snapshot(18, &[json!({
            "address":"t8", "cwd":"/work", "state":"live",
            "foreground":{"name":"sleep"}, "status":null
        })], None, None, &std::collections::HashMap::new());
        assert_eq!(snapshot.workspaces[0].tabs[0].panes[0].agent, None);
    }

    #[test]
    fn event_translation_maps_status_and_structure() {
        let location = HashMap::from([("t7".to_owned(), ("space-1".to_owned(), "tab-1".to_owned()))]);
        let status = translate_event(&json!({"method":"event","params":{"seq":18,"terminal":"t7","type":"status","data":{"status":{"state":"blocked","app":"pi"}}}}), &location).unwrap();
        assert_eq!(status.kind, "pane.status_changed");
        assert_eq!(status.workspace_id.as_deref(), Some("space-1"));
        assert_eq!(status.status, Some(crate::model::AgentStatus::Blocked));
        assert_eq!(status.detail.as_ref().and_then(|detail| detail.pointer("/status/app")).and_then(Value::as_str), Some("pi"));
        let created = translate_event(&json!({"params":{"seq":19,"terminal":"t8","type":"created","data":{}}}), &location).unwrap();
        assert_eq!(created.kind, "pane.created");
    }

    #[test]
    fn core_attach_frame_encoding_matches_measured_wire_header() {
        // Regression for bug class #6: resize is encoded as RESIZE, never FOCUS;
        // Android IME handling remains outside this protocol bridge.
        assert_eq!(
            encode_frame(1, FRAME_RESIZE, &encode_resize(101, 31)),
            [1, 0, 0, 0, 3, 4, 0, 0, 0, 101, 0, 31, 0],
        );
        assert_eq!(FRAME_FACTS, 2);
        assert_eq!(FRAME_FOCUS, 4);
        assert_eq!(FRAME_CLOSE, 5);
    }

    #[test]
    fn snapshot_sorts_spaces_and_tabs_by_order_labels() {
        let terminals = vec![
            json!({"address":"t1","labels":{"herdr.space":"a","herdr.space_label":"A","herdr.tab":"a1","herdr.tab_label":"1","herdr.space_order":2,"herdr.tab_order":2}}),
            json!({"address":"t2","labels":{"herdr.space":"b","herdr.space_label":"B","herdr.tab":"b1","herdr.tab_label":"1","herdr.space_order":0,"herdr.tab_order":0}}),
            json!({"address":"t3","labels":{"herdr.space":"b","herdr.space_label":"B","herdr.tab":"b2","herdr.tab_label":"2","herdr.space_order":0,"herdr.tab_order":1}}),
            json!({"address":"t4","labels":{"herdr.space":"a","herdr.space_label":"A","herdr.tab":"a2","herdr.tab_label":"2","herdr.space_order":2,"herdr.tab_order":0}}),
        ];
        let snapshot = translate_snapshot(1, &terminals, None, None, &std::collections::HashMap::new());
        let spaces: Vec<_> = snapshot.workspaces.iter().map(|w| w.label.as_str()).collect();
        assert_eq!(spaces, ["B", "A"]);
        let a_tabs: Vec<_> = snapshot.workspaces[1].tabs.iter().map(|t| t.id.as_str()).collect();
        assert_eq!(a_tabs, ["a2", "a1"]);
        let b_tabs: Vec<_> = snapshot.workspaces[0].tabs.iter().map(|t| t.id.as_str()).collect();
        assert_eq!(b_tabs, ["b1", "b2"]);
    }

    #[test]
    fn where_only_terminals_group_into_one_workspace() {
        let terminals = vec![
            json!({"address":"t1","labels":{"where":"Project / 1"}}),
            json!({"address":"t2","labels":{"where":"Project / 1"}}),
            json!({"address":"t3","labels":{"where":"Project / 2"}}),
        ];
        let snapshot = translate_snapshot(1, &terminals, None, None, &std::collections::HashMap::new());
        assert_eq!(snapshot.workspaces.len(), 1);
        assert_eq!(snapshot.workspaces[0].id, "where:Project");
        assert_eq!(snapshot.workspaces[0].tabs.len(), 2);
        assert_eq!(snapshot.workspaces[0].tabs[0].panes.len(), 2);
        assert_eq!(snapshot.workspaces[0].tabs[1].panes.len(), 1);
    }

    #[test]
    fn focused_done_pane_is_presented_as_read() {
        let terminals = vec![json!({
            "address":"t1", "turn":1, "status_ms":100,
            "status":{"state":"done","app":"omp"},
            "labels":{"herdr.space":"s1","herdr.tab":"tab1"}
        })];
        let read = std::collections::HashMap::from([(
            "t1".to_owned(),
            CoreReadMark::for_terminal(&terminals[0]).expect("done terminal has a mark"),
        )]);
        let snapshot = translate_snapshot(1, &terminals, None, None, &read);
        assert_eq!(snapshot.workspaces[0].tabs[0].panes[0].status, crate::model::AgentStatus::Idle);
        let unread = translate_snapshot(1, &terminals, None, None, &std::collections::HashMap::new());
        assert_eq!(unread.workspaces[0].tabs[0].panes[0].status, crate::model::AgentStatus::Done);

        // A new completion on the same pane id must not inherit the old read
        // mark: this is the subscriber-independent fix.
        let next = vec![json!({
            // Same turn as the read completion; only status_ms changed.
            "address":"t1", "turn":1, "status_ms":200,
            "status":{"state":"done","app":"omp"},
            "labels":{"herdr.space":"s1","herdr.tab":"tab1"}
        })];
        let next_snapshot = translate_snapshot(2, &next, None, None, &read);
        assert_eq!(
            next_snapshot.workspaces[0].tabs[0].panes[0].status,
            crate::model::AgentStatus::Done,
        );
    }

    #[test]
    fn snapshot_marks_core_focus_on_workspace_tab_and_pane() {
        let terminals = vec![json!({
            "address":"t1", "labels":{"herdr.space":"s1","herdr.tab":"tab1","herdr.space_label":"S","herdr.tab_label":"1"}
        })];
        let focus = CoreFocus {
            workspace_id: "s1".into(),
            tab_id: "tab1".into(),
            pane_id: "t1".into(),
        };
        let snapshot = translate_snapshot(1, &terminals, None, Some(&focus), &std::collections::HashMap::new());
        assert!(snapshot.workspaces[0].focused);
        assert_eq!(snapshot.workspaces[0].active_tab_id.as_deref(), Some("tab1"));
        assert!(snapshot.workspaces[0].tabs[0].focused);
        assert!(snapshot.workspaces[0].tabs[0].panes[0].focused);
        assert_eq!(snapshot.focus.workspace_id.as_deref(), Some("s1"));
        assert_eq!(snapshot.focus.tab_id.as_deref(), Some("tab1"));
        assert_eq!(snapshot.focus.pane_id.as_deref(), Some("t1"));
    }

    #[test]
    fn core_event_cursors_are_monotonic_and_per_connection() {
        // Regression for bug class #5: reconnects and missed-event recovery must
        // not share a global cursor or make one subscriber drive another's seq.
        let mut first = CoreEventCursor::new(10);
        let second = CoreEventCursor::new(10);
        assert!(first.observe(12));
        assert!(!first.observe(11));
        assert_eq!(first.seq(), 12);
        assert_eq!(second.seq(), 10);
        first.resync(15);
        assert_eq!(first.seq(), 15);
        assert!(!first.observe(14));
        assert!(first.observe(16));
        assert_eq!(second.seq(), 10);
    }
}
