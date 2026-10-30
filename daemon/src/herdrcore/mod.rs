use std::collections::HashMap;
use std::path::PathBuf;

use anyhow::{anyhow, bail, Context, Result};
use serde_json::{json, Value};
use tokio::io::{AsyncBufReadExt, AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, BufReader};
use tokio::net::UnixStream;

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

#[derive(Clone, Debug)]
pub struct CoreClient {
    socket_path: PathBuf,
}

#[derive(Clone, Debug)]
pub struct CoreSnapshot {
    pub seq: i64,
    pub terminals: Vec<Value>,
    pub version: Option<String>,
}

impl CoreClient {
    pub fn new(socket_path: impl Into<PathBuf>) -> Self {
        Self { socket_path: socket_path.into() }
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
        let hello = read_json_line(&mut reader).await?;
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
        let hello = read_json_line(&mut reader).await?;
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
        let response = read_json_line(&mut reader).await?;
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
        let response = read_json_line(&mut reader).await?;
        let initial = rpc_result(response, 1)?;
        let seq = initial.get("seq").and_then(Value::as_i64).unwrap_or(0);
        let terminals = initial
            .get("terminals")
            .and_then(Value::as_array)
            .cloned()
            .unwrap_or_default();
        Ok(CoreSubscription {
            reader,
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
            let frame = read_frame(&mut stream).await?;
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
        Ok(CoreAttachment { stream, channel, terminal })
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
        let snapshot = translate_snapshot(core.seq, &core.terminals, core.version);
        let workspace = snapshot.workspaces.iter().find(|workspace| workspace.id == workspace_id)
            .ok_or_else(|| anyhow!("not_found: workspace {workspace_id}"))?;
        let workspace_label = workspace.label.clone();
        let cwd = workspace.path.clone();
        let tab_id = format!("core-tab-{:016x}", rand::random::<u64>());
        let tab_label = label.filter(|value| !value.trim().is_empty())
            .map(str::to_owned)
            .unwrap_or_else(|| (workspace.tabs.len() + 1).to_string());
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

    pub async fn close_tab(&self, tab_id: &str) -> Result<()> {
        let terminals = self.list(true).await?;
        self.close_selected(terminals.into_iter().filter(|terminal| {
            field_string(terminal.get("labels").unwrap_or(&Value::Null), "herdr.tab")
                .unwrap_or_else(|| field_string(terminal, "address").unwrap_or_default()) == tab_id
        })).await
    }

    pub async fn close_workspace(&self, workspace_id: &str) -> Result<()> {
        let terminals = self.list(true).await?;
        let locations = locations(&terminals);
        let selected = terminals.iter().filter(|terminal| {
            field_string(terminal, "address")
                .and_then(|address| locations.get(address))
                .is_some_and(|(space, _)| space == workspace_id)
        }).cloned().collect::<Vec<_>>();
        if selected.is_empty() { bail!("not_found: workspace {workspace_id}"); }
        self.close_selected(selected.into_iter()).await
    }

    async fn close_selected(&self, terminals: impl Iterator<Item = Value>) -> Result<()> {
        for terminal in terminals {
            let Some(address) = field_string(&terminal, "address") else { continue };
            let mut finished = field_string(&terminal, "state") == Some("finished");
            if field_string(&terminal, "state") == Some("live") {
                let result = self.call("terminal.kill", json!({"terminal":address})).await?;
                finished = field_string(&result, "state") == Some("finished");
            }
            if finished {
                match self.call("terminal.rm", json!({"terminal":address})).await {
                    Ok(_) => {}
                    Err(error) if format!("{error:#}").contains("not_found") => {}
                    Err(error) if format!("{error:#}").contains("live") => {}
                    Err(error) => return Err(error),
                }
            }
        }
        Ok(())
    }

    pub async fn rename_tab(&self, tab_id: &str, label: &str) -> Result<()> {
        let terminals = self.list(false).await?;
        let matched: Vec<_> = terminals.into_iter().filter(|terminal| {
            field_string(terminal.get("labels").unwrap_or(&Value::Null), "herdr.tab")
                .unwrap_or_else(|| field_string(terminal, "address").unwrap_or_default()) == tab_id
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
        let locations = locations(&terminals);
        let matched: Vec<_> = terminals.into_iter().filter(|terminal| {
            field_string(terminal, "address")
                .and_then(|address| locations.get(address))
                .is_some_and(|(space, _)| space == workspace_id)
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
    pub initial: CoreSnapshot,
}

impl CoreSubscription {
    pub async fn next(&mut self) -> Result<Option<Value>> {
        let mut line = String::new();
        if self.reader.read_line(&mut line).await? == 0 {
            return Ok(None);
        }
        let message: Value = serde_json::from_str(&line).context("invalid herdr-core event notification")?;
        Ok(Some(message))
    }
}

pub struct CoreAttachment {
    stream: UnixStream,
    pub channel: u32,
    pub terminal: Value,
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
        read_frame(&mut self.stream).await
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

async fn read_json_line<R: AsyncBufReadExt + Unpin>(reader: &mut R) -> Result<Value> {
    let mut line = String::new();
    if reader.read_line(&mut line).await? == 0 {
        bail!("herdr-core closed the socket before replying");
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

fn split_where(value: Option<&str>) -> (Option<String>, Option<String>) {
    let Some(where_label) = value else { return (None, None) };
    match where_label.split_once(" / ") {
        Some((space, tab)) => (Some(space.to_owned()), Some(tab.to_owned())),
        None => (Some(where_label.to_owned()), None),
    }
}

pub fn translate_snapshot(seq: i64, terminals: &[Value], version: Option<String>) -> crate::model::SessionSnapshot {
    use crate::model::{AgentStatus, Focus, HerdrInfo, HerdrUpdate, Pane, SessionSnapshot, Tab, Workspace};
    use std::collections::BTreeMap;

    let mut workspaces = Vec::<Workspace>::new();
    let mut workspace_indices = HashMap::<String, usize>::new();
    let mut tab_indices = HashMap::<(String, String), usize>::new();

    for terminal in terminals {
        let Some(address) = field_string(terminal, "address") else { continue };
        let labels = terminal.get("labels").unwrap_or(&Value::Null);
        let (where_space, where_tab) = split_where(field_string(labels, "where"));
        let workspace_id = field_string(labels, "herdr.space")
            .map(str::to_owned)
            .unwrap_or_else(|| format!("core:{address}"));
        let workspace_label = field_string(labels, "herdr.space_label")
            .map(str::to_owned)
            .or(where_space)
            .unwrap_or_else(|| terminal.get("name").and_then(Value::as_str).unwrap_or(address).to_owned());
        let tab_id = field_string(labels, "herdr.tab").unwrap_or(address).to_owned();
        let tab_label = field_string(labels, "herdr.tab_label")
            .map(str::to_owned)
            .or(where_tab)
            .unwrap_or_else(|| terminal.get("name").and_then(Value::as_str).unwrap_or(address).to_owned());
        let cwd = field_string(terminal, "cwd").map(str::to_owned);
        let status_value = terminal.pointer("/status/state").map(status).unwrap_or(AgentStatus::Unknown);
        let agent = terminal.pointer("/status/app").and_then(Value::as_str)
            .or_else(|| terminal.pointer("/foreground/name").and_then(Value::as_str))
            .map(str::to_owned);
        let title = field_string(terminal, "title").map(str::to_owned);
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

    SessionSnapshot {
        revision: seq,
        seq,
        captured_at: Some(crate::cache::now_rfc3339()),
        herdr: HerdrInfo { version, protocol: Some(1), binary: Some("herdr-core".to_owned()) },
        update: HerdrUpdate::default(),
        focus: Focus::default(),
        workspaces,
    }
}

pub fn locations(terminals: &[Value]) -> HashMap<String, (String, String)> {
    terminals.iter().filter_map(|terminal| {
        let address = field_string(terminal, "address")?;
        let labels = terminal.get("labels").unwrap_or(&Value::Null);
        let workspace = field_string(labels, "herdr.space").map(str::to_owned).unwrap_or_else(|| format!("core:{address}"));
        let tab = field_string(labels, "herdr.tab").unwrap_or(address).to_owned();
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
        })], Some("0.1.14".to_owned()));
        assert_eq!(snapshot.seq, 17);
        assert_eq!(snapshot.workspaces[0].id, "space-1");
        assert_eq!(snapshot.workspaces[0].label, "Project");
        assert_eq!(snapshot.workspaces[0].tabs[0].id, "tab-1");
        assert_eq!(snapshot.workspaces[0].tabs[0].label, "2");
        assert_eq!(snapshot.workspaces[0].tabs[0].panes[0].terminal_id.as_deref(), Some("t7"));
        assert_eq!(snapshot.workspaces[0].tabs[0].panes[0].status, crate::model::AgentStatus::Working);
    }

    #[test]
    fn event_translation_maps_status_and_structure() {
        let location = HashMap::from([("t7".to_owned(), ("space-1".to_owned(), "tab-1".to_owned()))]);
        let status = translate_event(&json!({"method":"event","params":{"seq":18,"terminal":"t7","type":"status","data":{"status":{"state":"blocked"}}}}), &location).unwrap();
        assert_eq!(status.kind, "pane.status_changed");
        assert_eq!(status.workspace_id.as_deref(), Some("space-1"));
        assert_eq!(status.status, Some(crate::model::AgentStatus::Blocked));
        let created = translate_event(&json!({"params":{"seq":19,"terminal":"t8","type":"created","data":{}}}), &location).unwrap();
        assert_eq!(created.kind, "pane.created");
    }

    #[test]
    fn core_attach_frame_encoding_matches_measured_wire_header() {
        assert_eq!(
            encode_frame(1, FRAME_RESIZE, &encode_resize(101, 31)),
            [1, 0, 0, 0, 3, 4, 0, 0, 0, 101, 0, 31, 0],
        );
        assert_eq!(FRAME_FACTS, 2);
        assert_eq!(FRAME_FOCUS, 4);
        assert_eq!(FRAME_CLOSE, 5);
    }
}
