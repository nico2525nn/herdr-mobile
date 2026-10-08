use std::collections::{BTreeMap, HashMap};
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, RwLock};

use anyhow::Result;
use chrono::Utc;
use tracing::{debug, info, warn};

use crate::events::EventBus;
use crate::herdr::{HerdrClient, HerdrEvent};
use crate::model::{AgentStatus, Focus, HerdrInfo, HerdrPane, HerdrSnapshot, HerdrWorkspace, Pane, SemanticEvent, SessionSnapshot, Tab, Workspace};

#[derive(Clone, Debug)]
pub struct CacheSummary {
    pub revision: i64,
    pub seq: i64,
    pub workspaces: usize,
}

#[derive(Debug, Default)]
struct Inner {
    snapshot: Option<SessionSnapshot>,
    herdr_connected: bool,
    herdr_error: Option<String>,
    herdr_version: Option<String>,
    herdr_protocol: Option<i64>,
}

/// Cached Herdr state. Herdr stays the source of truth: every reconnect and every structural
/// event rebuilds from `session.snapshot`; incremental patches only ever touch agent status
/// and labels.
pub struct SessionCache {
    herdr: HerdrClient,
    inner: RwLock<Inner>,
    revision: AtomicI64,
    seq: AtomicI64,
}

impl SessionCache {
    pub fn new(herdr: HerdrClient) -> SessionCache {
        SessionCache {
            herdr,
            inner: RwLock::new(Inner::default()),
            revision: AtomicI64::new(0),
            seq: AtomicI64::new(0),
        }
    }

    pub fn revision(&self) -> i64 {
        self.revision.load(Ordering::SeqCst)
    }

    pub fn seq(&self) -> i64 {
        self.seq.load(Ordering::SeqCst)
    }

    pub fn herdr_client(&self) -> &HerdrClient {
        &self.herdr
    }

    pub fn snapshot(&self) -> Option<SessionSnapshot> {
        self.inner.read().expect("cache lock").snapshot.clone()
    }

    /// Every pane id in the current cache, for per-pane subscriptions.
    pub fn pane_ids(&self) -> Vec<String> {
        self.inner
            .read()
            .expect("cache lock")
            .snapshot
            .as_ref()
            .map(|s| {
                s.workspaces
                    .iter()
                    .flat_map(|w| w.tabs.iter())
                    .flat_map(|t| t.panes.iter())
                    .map(|p| p.id.clone())
                    .collect()
            })
            .unwrap_or_default()
    }

    pub fn health_parts(&self) -> (bool, Option<String>, Option<String>, Option<i64>) {
        let inner = self.inner.read().expect("cache lock");
        (
            inner.herdr_connected,
            inner.herdr_error.clone(),
            inner.herdr_version.clone(),
            inner.herdr_protocol,
        )
    }

    fn home_dir() -> Option<String> {
        std::env::var("HOME").ok()
    }

    /// Rebuild the whole cache from Herdr. Returns the new summary.
    pub async fn refresh_from_herdr(&self) -> Result<CacheSummary> {
        let raw = self.herdr.snapshot().await?;
        // Best effort: version metadata for health/diagnostics.
        let info = self.herdr.ping().await.ok();
        let snapshot = build_snapshot(
            raw,
            self.revision.load(Ordering::SeqCst) + 1,
            self.seq.load(Ordering::SeqCst),
            Some(self.herdr.herdr_bin.clone()),
            Self::home_dir(),
        )?;
        let workspaces = snapshot.workspaces.len();
        {
            let mut inner = self.inner.write().expect("cache lock");
            inner.snapshot = Some(snapshot);
            inner.herdr_connected = true;
            inner.herdr_error = None;
            if let Some(info) = info {
                inner.herdr_version = info.version;
                inner.herdr_protocol = info.protocol;
            }
        }
        let revision = self.revision.fetch_add(1, Ordering::SeqCst) + 1;
        Ok(CacheSummary {
            revision,
            seq: self.seq.load(Ordering::SeqCst),
            workspaces,
        })
    }

    fn mark_herdr_down(&self, error: String) {
        let mut inner = self.inner.write().expect("cache lock");
        inner.herdr_connected = false;
        inner.herdr_error = Some(error);
    }

    /// Fold one Herdr event. Returns semantic events to broadcast (usually 0–3).
    pub async fn apply_herdr_event(&self, event: &HerdrEvent) -> Result<Vec<SemanticEvent>> {
        let name = event.name.as_str();
        match name {
            "pane_agent_status_changed" => Ok(self.apply_status_change(event)),
            "tab_renamed" => Ok(self.apply_tab_rename(event)),
            "workspace_renamed" => Ok(self.apply_workspace_rename(event)),
            "pane_created" | "pane_closed" | "tab_created" | "tab_closed" | "workspace_created"
            | "workspace_closed" => self.apply_structural(event).await,
            _ => {
                // Focus, layout, metadata, output-changed and other passive events invalidate
                // nothing we render; the next structural event or resync heals any drift.
                debug!("ignoring passive herdr event {name}");
                Ok(vec![])
            }
        }
    }

    fn next_seq(&self) -> i64 {
        self.seq.fetch_add(1, Ordering::SeqCst) + 1
    }

    /// Reserve sequence numbers consumed outside `emit` (the `snapshot.required` path).
    /// Loop on CAS failure: a single compare_exchange lets two racers mint the
    /// same seq (client drops the duplicate as history and loses a change).
    pub fn next_seq_for_broadcast(&self, at_least: i64) {
        loop {
            let current = self.seq.load(Ordering::SeqCst);
            if at_least <= current {
                return;
            }
            if self
                .seq
                .compare_exchange(current, at_least, Ordering::SeqCst, Ordering::SeqCst)
                .is_ok()
            {
                return;
            }
        }
    }

    fn mutate(&self, f: impl FnOnce(&mut SessionSnapshot)) -> Option<(i64, SessionSnapshot)> {
        let mut inner = self.inner.write().expect("cache lock");
        let snapshot = inner.snapshot.as_mut()?;
        f(snapshot);
        let revision = self.revision.fetch_add(1, Ordering::SeqCst) + 1;
        snapshot.revision = revision;
        Some((revision, snapshot.clone()))
    }

    /// Bring the stored snapshot's seq up to the global cursor AFTER emitting
    /// the events for a mutation. mutate() runs before emit() mints seqs, so
    /// reading the cursor there always lags; call this once per mutation batch.
    fn sync_snapshot_seq(&self) {
        let mut inner = self.inner.write().expect("cache lock");
        if let Some(snapshot) = inner.snapshot.as_mut() {
            snapshot.seq = self.seq.load(Ordering::SeqCst);
        }
    }

    fn emit(
        &self,
        kind: &str,
        workspace_id: Option<String>,
        tab_id: Option<String>,
        pane_id: Option<String>,
        status: Option<AgentStatus>,
        label: Option<String>,
        revision: i64,
        detail: Option<serde_json::Value>,
        message: Option<String>,
    ) -> SemanticEvent {
        SemanticEvent {
            seq: self.next_seq(),
            kind: kind.to_string(),
            at: Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, false),
            workspace_id,
            tab_id,
            pane_id,
            status,
            label,
            message,
            revision: Some(revision),
            detail,
        }
    }

    fn apply_status_change(&self, event: &HerdrEvent) -> Vec<SemanticEvent> {
        let pane_id = event.data.get("pane_id").and_then(|v| v.as_str()).unwrap_or("");
        let workspace_id = event
            .data
            .get("workspace_id")
            .and_then(|v| v.as_str())
            .unwrap_or("");
        let status = AgentStatus::from_wire(event.data.get("agent_status").and_then(|v| v.as_str()));
        // Failure detail: Herdr reports failures as blocked-typed status changes
        // with a reason; the client classifies notifyFailed off message text.
        // Without this, message stays None and the failed branch is dead.
        let message = event
            .data
            .get("message")
            .or_else(|| event.data.get("reason"))
            .or_else(|| event.data.get("detail"))
            .and_then(|v| v.as_str())
            .map(str::to_string);
        let mut out = vec![];
        let Some((revision, snapshot)) = self.mutate(|snapshot| {
            for workspace in &mut snapshot.workspaces {
                if workspace.id != workspace_id {
                    continue;
                }
                for tab in &mut workspace.tabs {
                    let mut touched = false;
                    for pane in &mut tab.panes {
                        if pane.id == pane_id {
                            pane.status = status;
                            touched = true;
                        }
                    }
                    if touched {
                        tab.status = AgentStatus::roll_up(tab.panes.iter().map(|p| p.status))
                            .unwrap_or(AgentStatus::Unknown);
                    }
                }
                workspace.status = AgentStatus::roll_up(workspace.tabs.iter().map(|t| t.status))
                    .unwrap_or(AgentStatus::Unknown);
            }
        }) else {
            return out;
        };
        // Re-read the aggregates to decide which level events to emit.
        let (tab_id, tab_status, workspace_status) = snapshot
            .workspaces
            .iter()
            .find(|w| w.id == workspace_id)
            .and_then(|w| {
                w.tabs.iter().find(|t| t.panes.iter().any(|p| p.id == pane_id)).map(|t| {
                    (
                        t.id.clone(),
                        t.status,
                        w.status,
                    )
                })
            })
            .unwrap_or_default();
        out.push(self.emit(
            "pane.status_changed",
            Some(workspace_id.to_string()),
            Some(tab_id.clone()),
            Some(pane_id.to_string()),
            Some(status),
            None,
            revision,
            None,
            message.clone(),
        ));
        out.push(self.emit(
            "tab.status_changed",
            Some(workspace_id.to_string()),
            Some(tab_id),
            None,
            Some(tab_status),
            None,
            revision,
            None,
            message.clone(),
        ));
        out.push(self.emit(
            "workspace.status_changed",
            Some(workspace_id.to_string()),
            None,
            None,
            Some(workspace_status),
            None,
            revision,
            None,
            message,
        ));
        self.sync_snapshot_seq();
        out
    }

    fn apply_tab_rename(&self, event: &HerdrEvent) -> Vec<SemanticEvent> {
        let tab_id = event.data.get("tab_id").and_then(|v| v.as_str()).unwrap_or("");
        let workspace_id = event.data.get("workspace_id").and_then(|v| v.as_str()).unwrap_or("");
        let label = event.data.get("label").and_then(|v| v.as_str()).unwrap_or("").to_string();
        let Some((revision, _)) = self.mutate(|snapshot| {
            for workspace in &mut snapshot.workspaces {
                if workspace.id != workspace_id {
                    continue;
                }
                for tab in &mut workspace.tabs {
                    if tab.id == tab_id {
                        tab.label = label.clone();
                    }
                }
            }
        }) else {
            return vec![];
        };
        let ev = self.emit(
            "tab.renamed",
            Some(workspace_id.to_string()),
            Some(tab_id.to_string()),
            None,
            None,
            Some(label),
            revision,
            None,
            None,
        );
        self.sync_snapshot_seq();
        vec![ev]
    }

    fn apply_workspace_rename(&self, event: &HerdrEvent) -> Vec<SemanticEvent> {
        let workspace_id = event.data.get("workspace_id").and_then(|v| v.as_str()).unwrap_or("");
        let label = event.data.get("label").and_then(|v| v.as_str()).unwrap_or("").to_string();
        let Some((revision, _)) = self.mutate(|snapshot| {
            for workspace in &mut snapshot.workspaces {
                if workspace.id == workspace_id {
                    workspace.label = label.clone();
                }
            }
        }) else {
            return vec![];
        };
        let ev = self.emit(
            "workspace.renamed",
            Some(workspace_id.to_string()),
            None,
            None,
            None,
            Some(label),
            revision,
            None,
            None,
        );
        self.sync_snapshot_seq();
        vec![ev]
    }

    async fn apply_structural(&self, event: &HerdrEvent) -> Result<Vec<SemanticEvent>> {
        // Never patch structure by hand: rebuild authoritatively, then describe the change.
        let summary = self.refresh_from_herdr().await?;
        let kind = match event.name.as_str() {
            "pane_created" => "pane.created",
            "pane_closed" => "pane.closed",
            "tab_created" => "tab.created",
            "tab_closed" => "tab.closed",
            "workspace_created" => "workspace.created",
            _ => "workspace.closed",
        };
        let workspace_id = event.data.get("workspace_id").and_then(|v| v.as_str()).map(str::to_string);
        let tab_id = event.data.get("tab_id").and_then(|v| v.as_str()).map(str::to_string);
        let pane_id = event.data.get("pane_id").and_then(|v| v.as_str()).map(str::to_string);
        let ev = self.emit(
            kind,
            workspace_id,
            tab_id,
            pane_id,
            None,
            None,
            summary.revision,
            Some(event.data.clone()),
            None,
        );
        self.sync_snapshot_seq();
        Ok(vec![ev])
    }
}

/// Subscription loop: keeps the cache current and broadcasts semantic events.
/// On `events_lost` or any socket failure it refetches, tells every Android subscriber the
/// cache is untrustworthy (`snapshot.required`), and resubscribes with backoff.
pub async fn run_resync_loop(cache: Arc<SessionCache>, bus: Arc<EventBus>) {
    let mut backoff = std::time::Duration::from_millis(500);
    const MAX_BACKOFF: std::time::Duration = std::time::Duration::from_secs(15);
    loop {
        // Refresh first so the subscription below covers exactly the panes we know about.
        match cache.refresh_from_herdr().await {
            Ok(summary) => info!(revision = summary.revision, "resynced session cache from herdr"),
            Err(e) => {
                cache.mark_herdr_down(format!("{e:#}"));
                warn!("resync failed ({e:#}); retrying in {}ms", backoff.as_millis());
                tokio::time::sleep(backoff).await;
                backoff = (backoff * 2).min(MAX_BACKOFF);
                continue;
            }
        }
        let subscriptions = all_subscriptions(&cache.pane_ids());
        let mut stale_noops: u32 = 0;
        let mut sub = match cache.herdr_client().subscribe(subscriptions).await {
            Ok(sub) => {
                backoff = std::time::Duration::from_millis(500);
                sub
            }
            Err(e) => {
                cache.mark_herdr_down(format!("{e:#}"));
                warn!("herdr subscribe failed ({e:#}); retrying in {}ms", backoff.as_millis());
                tokio::time::sleep(backoff).await;
                backoff = (backoff * 2).min(MAX_BACKOFF);
                continue;
            }
        };
        loop {
            match sub.next_event().await {
                Ok(Some(event)) => {
                    if is_passive(&event.name) {
                        stale_noops += 1;
                        if stale_noops >= 30 {
                            debug!("30 passive events accumulated; refetching snapshot");
                            match cache.refresh_from_herdr().await {
                                Ok(_) => stale_noops = 0,
                                Err(e) => {
                                    warn!("passive refetch failed ({e:#}); breaking to resubscribe");
                                    break;
                                }
                            }
                        }
                        continue;
                    }
                    stale_noops = 0;
                    let is_structural = matches!(
                        event.name.as_str(),
                        "pane_created" | "pane_closed" | "tab_created" | "tab_closed"
                            | "workspace_created" | "workspace_closed"
                    );
                    match cache.apply_herdr_event(&event).await {
                        Ok(events) => {
                            for e in events {
                                bus.broadcast(e);
                            }
                            if is_structural {
                                // pane.agent_status_changed needs concrete pane ids:
                                // break to resubscribe with the fresh pane set, or
                                // panes born after subscribe never report status.
                                debug!("structural event; resubscribing with fresh pane set");
                                break;
                            }
                        }
                        Err(e) => {
                            warn!("failed to apply herdr event ({e:#}); breaking to resubscribe");
                            break;
                        }
                    }
                }
                Ok(None) => {
                    warn!("herdr closed the event stream; resubscribing");
                    break;
                }
                Err(e) => {
                    let msg = format!("{e:#}");
                    if msg.contains("events_lost") {
                        warn!("herdr reported events_lost; invalidating every subscriber");
                        bus.broadcast_snapshot_required(&cache);
                    } else {
                        warn!("herdr event stream broke ({e:#}); resubscribing");
                    }
                    break;
                }
            }
        }
        // Tell subscribers the cache may have holes before we rebuild it.
        bus.broadcast_snapshot_required(&cache);
        cache.mark_herdr_down("event stream disconnected".to_string());
        tokio::time::sleep(backoff).await;
        backoff = (backoff * 2).min(MAX_BACKOFF);
    }
}

fn is_passive(name: &str) -> bool {
    matches!(
        name,
        "pane_updated"
            | "pane_focused"
            | "pane_moved"
            | "pane_output_changed"
            | "pane_exited"
            | "pane_agent_detected"
            | "tab_focused"
            | "tab_moved"
            | "workspace_updated"
            | "workspace_metadata_updated"
            | "workspace_moved"
            | "workspace_reordered"
            | "workspace_focused"
            | "layout_updated"
    )
}

fn all_subscriptions(pane_ids: &[String]) -> Vec<serde_json::Value> {
    // Deliberately narrow: high-frequency passive events (`pane.updated`, focus, output,
    // scroll) would flood the retained history and get this slow reader disconnected.
    // Status and structure are all the Android client renders; anything else heals on the
    // next structural refetch.
    let mut subs: Vec<serde_json::Value> = [
        "workspace.created",
        "workspace.renamed",
        "workspace.closed",
        "tab.created",
        "tab.renamed",
        "tab.closed",
        "pane.created",
        "pane.closed",
        "pane.exited",
    ]
    .iter()
    .map(|t| serde_json::json!({"type": t}))
    .collect();
    // `pane.agent_status_changed` requires a concrete pane id: subscribe to every pane we
    // know about. Structural events carry no pane list, so the loop refetches the snapshot
    // and resubscribes with the fresh pane set whenever structure changes.
    for pane_id in pane_ids {
        subs.push(serde_json::json!({"type": "pane.agent_status_changed", "pane_id": pane_id}));
    }
    subs
}

// ---------------------------------------------------------------------------
// Snapshot construction
// ---------------------------------------------------------------------------

pub fn build_snapshot(
    raw: HerdrSnapshot,
    revision: i64,
    seq: i64,
    binary: Option<String>,
    home: Option<String>,
) -> Result<SessionSnapshot> {
    let panes_by_tab: HashMap<&str, Vec<&HerdrPane>> = {
        let mut map: HashMap<&str, Vec<&HerdrPane>> = HashMap::new();
        for pane in &raw.panes {
            map.entry(pane.tab_id.as_str()).or_default().push(pane);
        }
        map
    };

    let mut workspaces: Vec<Workspace> = raw
        .workspaces
        .iter()
        .map(|w| {
            let tabs: Vec<Tab> = raw
                .tabs
                .iter()
                .filter(|t| t.workspace_id == w.workspace_id)
                .map(|t| {
                    let panes: Vec<Pane> = panes_by_tab
                        .get(t.tab_id.as_str())
                        .map(|panes| panes.iter().map(|p| to_pane(p)).collect())
                        .unwrap_or_default();
                    let status =
                        AgentStatus::roll_up(panes.iter().map(|p| p.status)).unwrap_or(AgentStatus::Unknown);
                    Tab {
                        id: t.tab_id.clone(),
                        workspace_id: t.workspace_id.clone(),
                        label: t.label.clone().unwrap_or_default(),
                        number: t.number,
                        status,
                        focused: t.focused,
                        panes,
                    }
                })
                .collect();
            let status =
                AgentStatus::roll_up(tabs.iter().map(|t| t.status)).unwrap_or(AgentStatus::Unknown);
            let path = workspace_path(w, &tabs, &raw.panes);
            Workspace {
                id: w.workspace_id.clone(),
                label: w.label.clone(),
                path,
                home: home.clone(),
                number: w.number,
                status,
                focused: w.focused,
                active_tab_id: w.active_tab_id.clone(),
                tabs,
                tokens: w.tokens.clone(),
            }
        })
        .collect();
    workspaces.sort_by_key(|w| w.number);

    Ok(SessionSnapshot {
        revision,
        seq,
        captured_at: Some(Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, false)),
        herdr: HerdrInfo {
            version: raw.version,
            protocol: raw.protocol,
            binary,
        },
        focus: Focus {
            workspace_id: raw.focused_workspace_id,
            tab_id: raw.focused_tab_id,
            pane_id: raw.focused_pane_id,
        },
        workspaces,
    })
}

fn to_pane(p: &HerdrPane) -> Pane {
    let title = p
        .terminal_title_stripped
        .clone()
        .filter(|t| !t.trim().is_empty())
        .or_else(|| p.terminal_title.clone().filter(|t| !t.trim().is_empty()))
        .or_else(|| p.label.clone());
    Pane {
        id: p.pane_id.clone(),
        tab_id: p.tab_id.clone(),
        workspace_id: p.workspace_id.clone(),
        label: p.label.clone(),
        title,
        agent: p.agent.clone(),
        status: AgentStatus::from_wire(p.agent_status.as_deref()),
        cwd: p.cwd.clone().or_else(|| p.foreground_cwd.clone()),
        terminal_id: p.terminal_id.clone(),
        focused: p.focused,
        revision: p.revision,
        tokens: p.tokens.clone(),
    }
}

/// Herdr has no workspace-level cwd: take the most common pane cwd in the active tab,
/// else any pane cwd in the workspace, else the label.
fn workspace_path(
    workspace: &HerdrWorkspace,
    tabs: &[Tab],
    _panes: &[HerdrPane],
) -> Option<String> {
    let active = workspace
        .active_tab_id
        .as_deref()
        .and_then(|id| tabs.iter().find(|t| t.id == id));
    let mut counts: BTreeMap<&str, usize> = BTreeMap::new();
    if let Some(tab) = active {
        for pane in &tab.panes {
            if let Some(cwd) = pane.cwd.as_deref() {
                *counts.entry(cwd).or_default() += 1;
            }
        }
    }
    if counts.is_empty() {
        for tab in tabs {
            for pane in &tab.panes {
                if let Some(cwd) = pane.cwd.as_deref() {
                    *counts.entry(cwd).or_default() += 1;
                }
            }
        }
    }
    counts
        .into_iter()
        .max_by_key(|(_, n)| *n)
        .map(|(cwd, _)| cwd.to_string())
}

pub fn now_rfc3339() -> String {
    Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, false)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture() -> HerdrSnapshot {
        serde_json::from_value(serde_json::json!({
            "version": "0.9.1", "protocol": 22,
            "focused_workspace_id": "w1", "focused_tab_id": "w1:tR", "focused_pane_id": "w1:pT",
            "mystery_future_field": {"anything": [1, 2, {"deep": true}]},
            "workspaces": [
                {"workspace_id": "w2", "number": 2, "label": "second", "focused": false,
                 "active_tab_id": "w2:t1", "agent_status": "idle", "tokens": {}},
                {"workspace_id": "w1", "number": 1, "label": "cpp-fabricmc", "focused": true,
                 "active_tab_id": "w1:tR", "agent_status": "working", "tokens": {"k": "v"}}
            ],
            "tabs": [
                {"tab_id": "w2:t1", "workspace_id": "w2", "number": 1, "label": "main",
                 "focused": false, "agent_status": "idle"},
                {"tab_id": "w1:tR", "workspace_id": "w1", "number": 24, "label": "agents",
                 "focused": true, "agent_status": "working"}
            ],
            "panes": [
                {"pane_id": "w2:p1", "tab_id": "w2:t1", "workspace_id": "w2", "focused": false,
                 "agent": null, "agent_status": "idle", "cwd": "/home/nico/proj", "terminal_id": "term_a",
                 "revision": 1},
                {"pane_id": "w1:pT", "tab_id": "w1:tR", "workspace_id": "w1", "focused": true,
                 "agent": "codex", "agent_status": "working",
                 "cwd": "/home/nico/proj", "foreground_cwd": "/home/nico/proj",
                 "terminal_id": "term_x", "terminal_title": "cpp-fabricmc 7th | cpp-fabricmc",
                 "terminal_title_stripped": "cpp-fabricmc 7th | cpp-fabricmc", "revision": 26,
                 "tokens": {}, "scroll": {"offset_from_bottom": 0, "max_offset_from_bottom": 0, "viewport_rows": 68}}
            ]
        }))
        .unwrap()
    }

    #[test]
    fn maps_snapshot_with_nulls_and_unknown_fields() {
        let snap = build_snapshot(fixture(), 7, 42, Some("/bin/herdr".into()), Some("/home/nico".into())).unwrap();
        assert_eq!(snap.revision, 7);
        assert_eq!(snap.seq, 42);
        assert_eq!(snap.workspaces.len(), 2);
        // Ordered by number even though Herdr sent w2 first.
        assert_eq!(snap.workspaces[0].id, "w1");
        assert_eq!(snap.workspaces[0].status, AgentStatus::Working);
        assert_eq!(snap.workspaces[0].path.as_deref(), Some("/home/nico/proj"));
        assert_eq!(snap.workspaces[0].home.as_deref(), Some("/home/nico"));
        let tab = &snap.workspaces[0].tabs[0];
        assert_eq!(tab.status, AgentStatus::Working);
        let pane = &tab.panes[0];
        assert_eq!(pane.agent.as_deref(), Some("codex"));
        assert_eq!(pane.title.as_deref(), Some("cpp-fabricmc 7th | cpp-fabricmc"));
        let idle = &snap.workspaces[1];
        assert_eq!(idle.status, AgentStatus::Idle);
        assert_eq!(snap.focus.workspace_id.as_deref(), Some("w1"));
    }

    #[test]
    fn unknown_status_maps_to_unknown() {
        let raw: HerdrSnapshot = serde_json::from_value(serde_json::json!({
            "workspaces": [{"workspace_id": "w9", "number": 1, "label": "x"}],
            "tabs": [{"tab_id": "w9:t1", "workspace_id": "w9", "number": 1}],
            "panes": [{"pane_id": "w9:p1", "tab_id": "w9:t1", "workspace_id": "w9",
                       "agent_status": "transmogrifying"}]
        }))
        .unwrap();
        let snap = build_snapshot(raw, 1, 0, None, None).unwrap();
        assert_eq!(snap.workspaces[0].status, AgentStatus::Unknown);
    }

    #[test]
    fn revision_and_seq_rules() {
        let cache = SessionCache::new(HerdrClient::new("/nonexistent.sock".into(), "herdr".into()));
        assert_eq!(cache.revision(), 0);
        assert_eq!(cache.seq(), 0);
        // next_seq is private; the broadcast reservation path exercises the same counter.
        cache.next_seq_for_broadcast(2);
        assert_eq!(cache.seq(), 2);
        cache.next_seq_for_broadcast(1);
        assert_eq!(cache.seq(), 2);
    }
}
