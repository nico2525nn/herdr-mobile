use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result};
use tokio::process::Command;
use tokio::sync::broadcast;
use tracing::{debug, warn};

use crate::cache::SessionCache;
use crate::events::EventBus;
use crate::model::{version_is_newer, HerdrManifest};

const MANIFEST_URL: &str = "https://herdr.dev/latest.json";
/// One check on startup, then this cadence. Herdr ships weekly at most; a
/// phone must never notice the worker exists.
const CHECK_INTERVAL: Duration = Duration::from_secs(6 * 3600);

/// Single-flight update state shared by the pill (`/v1/herdr/update` GET) and
/// the run button (POST): GET does a live manifest check and reports it
/// (without broadcasting — callers already hold the fresh payload), POST runs
/// `herdr update --handoff` then re-checks and broadcasts so clients refetch.
#[derive(Clone, Debug)]
pub struct UpdateStatus {
    pub latest_version: Option<String>,
    pub current_version: Option<String>,
    pub up_to_date: bool,
    pub running: bool,
    pub last_error: Option<String>,
    pub last_checked_at: i64,
}

struct Updater {
    cache: Arc<SessionCache>,
    bus: Arc<EventBus>,
    tx: broadcast::Sender<UpdateStatus>,
    running: std::sync::Mutex<bool>,
}

impl Updater {
    fn now() -> i64 {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_secs() as i64)
            .unwrap_or(0)
    }

    /// Read latest.json with the platform's curl (same tool + timeout profile
    /// as install.sh), verify it parses, and compare against the running
    /// Herdr (daemon's own `ping` version — same source as HealthReport).
    /// `HERDR_MOBILE_FAKE_LATEST=x.y.z` overrides the manifest version only:
    /// E2E pill check without touching the network (notes still fetch).
    async fn fetch_manifest(&self) -> Result<HerdrManifest> {
        let out = Command::new("curl")
            .args(["-fsSL", "--retry", "2", "--connect-timeout", "10", "--max-time", "20"])
            .arg(MANIFEST_URL)
            .output()
            .await
            .context("curl latest.json failed to run")?;
        if !out.status.success() {
            anyhow::bail!("latest.json fetch failed: {}", String::from_utf8_lossy(&out.stderr).trim());
        }
        let mut manifest: HerdrManifest =
            serde_json::from_slice(&out.stdout).context("latest.json is not valid JSON")?;
        if let Ok(fake) = std::env::var("HERDR_MOBILE_FAKE_LATEST") {
            if !fake.trim().is_empty() {
                manifest.version = Some(fake.trim().to_string());
            }
        }
        Ok(manifest)
    }

    fn current_version(&self) -> Option<String> {
        self.cache.snapshot().and_then(|s| s.herdr.version.clone()).filter(|v| !v.trim().is_empty())
    }

    async fn refresh(&self, trigger_event: bool) -> UpdateStatus {
        let current = self.current_version();
        let status = match self.fetch_manifest().await {
            Ok(manifest) => {
                let latest = manifest.version.clone().map(|v| v.trim_start_matches('v').to_string());
                let up_to_date = match (&current, &latest) {
                    (Some(c), Some(l)) => !version_is_newer(c, l),
                    _ => true,
                };
                self.cache
                    .set_update(HerdrManifest { version: latest.clone(), notes: manifest.notes.clone() }, None)
                    .await;
                UpdateStatus {
                    latest_version: latest,
                    current_version: current,
                    up_to_date,
                    running: false,
                    last_error: None,
                    last_checked_at: Self::now(),
                }
            }
            Err(e) => {
                let msg = format!("{e:#}");
                warn!("herdr update check failed: {msg}");
                self.cache.set_update(HerdrManifest::default(), Some(msg.clone())).await;
                UpdateStatus {
                    latest_version: None,
                    current_version: current,
                    up_to_date: true,
                    running: false,
                    last_error: Some(msg),
                    last_checked_at: Self::now(),
                }
            }
        };
        if trigger_event {
            self.bus.broadcast_update(&self.cache);
        }
        let _ = self.tx.send(status.clone());
        status
    }
}

/// Long-lived worker handle, owned by AppState.
#[derive(Clone)]
pub struct UpdateWatcher {
    updater: Arc<Updater>,
}

impl UpdateWatcher {
    pub fn new(cache: Arc<SessionCache>, bus: Arc<EventBus>) -> Self {
        let (tx, _) = broadcast::channel(4);
        Self {
            updater: Arc::new(Updater {
                cache,
                bus,
                tx,
                running: std::sync::Mutex::new(false),
            }),
        }
    }

    /// Spawn the startup check + 6h cadence. Fire-and-forget: failures only
    /// write `update.error` into the cache, never crash or spam.
    pub fn spawn(&self) {
        let updater = self.updater.clone();
        tokio::spawn(async move {
            updater.refresh(true).await;
            loop {
                tokio::time::sleep(CHECK_INTERVAL).await;
                updater.refresh(true).await;
            }
        });
    }

    pub async fn status(&self) -> UpdateStatus {
        self.updater.refresh(false).await
    }

    /// Run `herdr update --handoff` (same binary the daemon resolves). One at a
    /// time: concurrent runs would race the installer.
    pub async fn run_update(&self) -> Result<String> {
        {
            let mut running = self.updater.running.lock().expect("update lock");
            if *running {
                anyhow::bail!("an update is already running");
            }
            *running = true;
        }
        let result = self.run_update_inner().await;
        *self.updater.running.lock().expect("update lock") = false;
        result
    }

    async fn run_update_inner(&self) -> Result<String> {
        let bin = self
            .updater
            .cache
            .snapshot()
            .and_then(|s| s.herdr.binary.clone())
            .unwrap_or_else(|| "herdr".to_string());
        debug!("running herdr update via {bin}");
        // `herdr update` refuses inside a herdr session (and this daemon's
        // ambient env may carry HERDR_* vars): strip the session markers so
        // the updater sees a plain outside-herdr environment, exactly like
        // running it in the user's terminal.
        const HERDR_ENV_KEYS: [&str; 6] = [
            "HERDR_ENV",
            "HERDR_PANE_ID",
            "HERDR_TAB_ID",
            "HERDR_WORKSPACE_ID",
            "HERDR_SESSION",
            "HERDR_BIN_PATH",
        ];
        let mut cmd = Command::new(&bin);
        cmd.arg("update").arg("--handoff");
        for key in HERDR_ENV_KEYS {
            cmd.env_remove(key);
        }
        let out = cmd
            .stdin(std::process::Stdio::null())
            .output()
            .await
            .with_context(|| format!("failed to execute {bin} update"))?;
        let combined = format!(
            "{}{}",
            String::from_utf8_lossy(&out.stdout),
            String::from_utf8_lossy(&out.stderr)
        );
        if !out.status.success() {
            anyhow::bail!("herdr update failed: {}", combined.trim());
        }
        // Re-check so the pill reflects the just-installed version without
        // waiting for the next tick (also heals a post-restart version jump).
        self.updater.refresh(true).await;
        Ok(combined.trim().to_string())
    }
}
