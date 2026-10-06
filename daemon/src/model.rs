use serde::{Deserialize, Serialize};

/// Wire protocol generation implemented by this daemon. The Android client compares it
/// against its own and reports `protocol_mismatch` instead of misbehaving.
pub const PROTOCOL: i64 = 1;

// ---------------------------------------------------------------------------
// Android-facing model (camelCase). Mirrors
// core-model/.../SessionSnapshot.kt field for field.
// ---------------------------------------------------------------------------

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum AgentStatus {
    Blocked,
    Working,
    Done,
    Idle,
    Unknown,
}

impl AgentStatus {
    pub fn priority(self) -> u32 {
        match self {
            AgentStatus::Blocked => 500,
            AgentStatus::Working => 400,
            AgentStatus::Done => 300,
            AgentStatus::Idle => 200,
            AgentStatus::Unknown => 100,
        }
    }

    pub fn from_wire(value: Option<&str>) -> AgentStatus {
        match value {
            Some("blocked") => AgentStatus::Blocked,
            Some("working") => AgentStatus::Working,
            Some("done") => AgentStatus::Done,
            Some("idle") => AgentStatus::Idle,
            _ => AgentStatus::Unknown,
        }
    }

    pub fn as_wire(self) -> &'static str {
        match self {
            AgentStatus::Blocked => "blocked",
            AgentStatus::Working => "working",
            AgentStatus::Done => "done",
            AgentStatus::Idle => "idle",
            AgentStatus::Unknown => "unknown",
        }
    }

    pub fn roll_up(statuses: impl IntoIterator<Item = AgentStatus>) -> Option<AgentStatus> {
        statuses.into_iter().max_by_key(|s| s.priority())
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Pane {
    pub id: String,
    pub tab_id: String,
    pub workspace_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub label: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub title: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub agent: Option<String>,
    #[serde(default)]
    pub status: AgentStatus,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cwd: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub terminal_id: Option<String>,
    #[serde(default)]
    pub focused: bool,
    #[serde(default)]
    pub revision: i64,
    #[serde(default)]
    pub tokens: std::collections::BTreeMap<String, String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Tab {
    pub id: String,
    pub workspace_id: String,
    #[serde(default)]
    pub label: String,
    #[serde(default)]
    pub number: i64,
    #[serde(default)]
    pub status: AgentStatus,
    #[serde(default)]
    pub focused: bool,
    #[serde(default)]
    pub panes: Vec<Pane>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Workspace {
    pub id: String,
    pub label: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub path: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub home: Option<String>,
    #[serde(default)]
    pub number: i64,
    #[serde(default)]
    pub status: AgentStatus,
    #[serde(default)]
    pub focused: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub active_tab_id: Option<String>,
    #[serde(default)]
    pub tabs: Vec<Tab>,
    #[serde(default)]
    pub tokens: std::collections::BTreeMap<String, String>,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Focus {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub workspace_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tab_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pane_id: Option<String>,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HerdrInfo {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub version: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub protocol: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub binary: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionSnapshot {
    pub revision: i64,
    pub seq: i64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub captured_at: Option<String>,
    #[serde(default)]
    pub herdr: HerdrInfo,
    #[serde(default)]
    pub focus: Focus,
    #[serde(default)]
    pub workspaces: Vec<Workspace>,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SemanticEvent {
    pub seq: i64,
    #[serde(rename = "type")]
    pub kind: String,
    pub at: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub workspace_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tab_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pane_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub status: Option<AgentStatus>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub label: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub message: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub revision: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub detail: Option<serde_json::Value>,
}

// ---------------------------------------------------------------------------
// Herdr-facing model (snake_case, maximally tolerant).
// ---------------------------------------------------------------------------

#[derive(Clone, Debug, Default, Deserialize)]
pub struct HerdrWorkspace {
    #[serde(default)]
    pub workspace_id: String,
    #[serde(default)]
    pub number: i64,
    #[serde(default)]
    pub label: String,
    #[serde(default)]
    pub focused: bool,
    #[serde(default)]
    pub active_tab_id: Option<String>,
    #[serde(default)]
    pub agent_status: Option<String>,
    #[serde(default)]
    pub tokens: std::collections::BTreeMap<String, String>,
}

#[derive(Clone, Debug, Default, Deserialize)]
pub struct HerdrTab {
    #[serde(default)]
    pub tab_id: String,
    #[serde(default)]
    pub workspace_id: String,
    #[serde(default)]
    pub number: i64,
    #[serde(default)]
    pub label: Option<String>,
    #[serde(default)]
    pub focused: bool,
    #[serde(default)]
    pub agent_status: Option<String>,
}

#[derive(Clone, Debug, Default, Deserialize)]
pub struct HerdrPane {
    #[serde(default)]
    pub pane_id: String,
    #[serde(default)]
    pub tab_id: String,
    #[serde(default)]
    pub workspace_id: String,
    #[serde(default)]
    pub label: Option<String>,
    #[serde(default)]
    pub terminal_title: Option<String>,
    #[serde(default)]
    pub terminal_title_stripped: Option<String>,
    #[serde(default)]
    pub agent: Option<String>,
    #[serde(default)]
    pub agent_status: Option<String>,
    #[serde(default)]
    pub cwd: Option<String>,
    #[serde(default)]
    pub foreground_cwd: Option<String>,
    #[serde(default)]
    pub terminal_id: Option<String>,
    #[serde(default)]
    pub focused: bool,
    #[serde(default)]
    pub revision: i64,
    #[serde(default)]
    pub tokens: std::collections::BTreeMap<String, String>,
}

#[derive(Clone, Debug, Default, Deserialize)]
pub struct HerdrSnapshot {
    #[serde(default)]
    pub version: Option<String>,
    #[serde(default)]
    pub protocol: Option<i64>,
    #[serde(default)]
    pub focused_workspace_id: Option<String>,
    #[serde(default)]
    pub focused_tab_id: Option<String>,
    #[serde(default)]
    pub focused_pane_id: Option<String>,
    #[serde(default)]
    pub workspaces: Vec<HerdrWorkspace>,
    #[serde(default)]
    pub tabs: Vec<HerdrTab>,
    #[serde(default)]
    pub panes: Vec<HerdrPane>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn status_round_trip() {
        for (wire, status) in [
            ("blocked", AgentStatus::Blocked),
            ("working", AgentStatus::Working),
            ("done", AgentStatus::Done),
            ("idle", AgentStatus::Idle),
            ("unknown", AgentStatus::Unknown),
        ] {
            assert_eq!(AgentStatus::from_wire(Some(wire)), status);
            assert_eq!(status.as_wire(), wire);
        }
        assert_eq!(AgentStatus::from_wire(Some("failed")), AgentStatus::Unknown);
        assert_eq!(AgentStatus::from_wire(None), AgentStatus::Unknown);
    }

    #[test]
    fn roll_up_order() {
        use AgentStatus::*;
        assert_eq!(AgentStatus::roll_up([Idle, Working, Blocked]), Some(Blocked));
        assert_eq!(AgentStatus::roll_up([Done, Working, Idle]), Some(Working));
        assert_eq!(AgentStatus::roll_up([Idle, Done]), Some(Done));
        assert_eq!(AgentStatus::roll_up([Unknown, Idle]), Some(Idle));
        assert_eq!(AgentStatus::roll_up([Unknown]), Some(Unknown));
        let empty: Vec<AgentStatus> = vec![];
        assert_eq!(AgentStatus::roll_up(empty), None);
    }

    #[test]
    fn serialization_is_camel_case() {
        let pane = Pane {
            id: "w1:p1".into(),
            tab_id: "w1:t1".into(),
            workspace_id: "w1".into(),
            label: None,
            title: Some("t".into()),
            agent: Some("codex".into()),
            status: AgentStatus::Idle,
            cwd: None,
            terminal_id: Some("term_x".into()),
            focused: false,
            revision: 3,
            tokens: Default::default(),
        };
        let v = serde_json::to_value(&pane).unwrap();
        assert_eq!(v["tabId"], "w1:t1");
        assert_eq!(v["workspaceId"], "w1");
        assert_eq!(v["terminalId"], "term_x");
        assert!(v.get("label").is_none());
        assert_eq!(v["status"], "idle");
    }
}
