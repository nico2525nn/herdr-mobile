mod api;
mod auth;
mod cache;
mod config;
mod events;
mod herdr;
mod model;
mod terminal;

use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Instant;

use anyhow::{bail, Context, Result};
use tokio::signal::unix::{signal, SignalKind};
use tracing::{info, warn};

use crate::cache::SessionCache;
use crate::config::Config;
use crate::events::EventBus;

#[derive(Clone)]
pub struct AppState {
    pub config: Arc<Config>,
    pub cache: Arc<SessionCache>,
    pub bus: Arc<EventBus>,
    pub terminals: Arc<terminal::TerminalRegistry>,
    pub started_at: Instant,
}

#[tokio::main]
async fn main() -> Result<()> {
    let config = Config::parse(std::env::args().skip(1))?;

    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "herdr_mobile_daemon=info".into()),
        )
        .init();

    if config.print_token {
        println!("{}", auth::generate_token());
        return Ok(());
    }

    let bind: SocketAddr = config
        .bind
        .parse()
        .with_context(|| format!("invalid --bind address {:?}", config.bind))?;
    if !is_loopback(&bind) && !config.allow_remote_bind {
        bail!(
            "refusing to bind non-loopback address {bind} without --allow-remote-bind; \
             the daemon speaks plain HTTP and must stay behind an SSH tunnel or tailnet"
        );
    }
    if !is_loopback(&bind) {
        warn!("binding non-loopback address {bind}; ensure the network path is trusted (tailnet/VPN)");
    }

    let herdr = herdr::HerdrClient::new(config.herdr_socket.clone(), config.herdr_bin.clone());
    let cache = Arc::new(SessionCache::new(herdr.clone()));
    let bus = Arc::new(EventBus::new());
    let terminals = Arc::new(terminal::TerminalRegistry::new(
        config.clone(),
        herdr.clone(),
    ));

    // Prime from Herdr before accepting traffic, but stay up if Herdr is down: /v1/health
    // reports ok:false and the resync loop keeps retrying in the background.
    match cache.refresh_from_herdr().await {
        Ok(summary) => info!(
            revision = summary.revision,
            workspaces = summary.workspaces,
            "primed session cache from herdr"
        ),
        Err(e) => warn!("herdr unreachable at startup ({e:#}); serving degraded health until it returns"),
    }

    // Herdr event subscription loop; owns cache updates and the Android event broadcast.
    {
        let cache = cache.clone();
        let bus = bus.clone();
        tokio::spawn(async move { cache::run_resync_loop(cache, bus).await });
    }

    let state = AppState {
        config: Arc::new(config),
        cache,
        bus,
        terminals,
        started_at: Instant::now(),
    };

    let app = api::router(state.clone());

    let listener = tokio::net::TcpListener::bind(bind)
        .await
        .with_context(|| format!("cannot bind {bind}"))?;
    info!("herdr-mobile-daemon listening on {bind}");

    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown_signal(state))
        .await
        .context("http server failed")?;
    Ok(())
}

async fn shutdown_signal(state: AppState) {
    let mut term = signal(SignalKind::terminate()).expect("SIGTERM handler");
    let mut int = signal(SignalKind::interrupt()).expect("SIGINT handler");
    tokio::select! {
        _ = term.recv() => info!("received SIGTERM, shutting down"),
        _ = int.recv() => info!("received SIGINT, shutting down"),
    }
    state.terminals.shutdown().await;
}

fn is_loopback(addr: &SocketAddr) -> bool {
    match addr {
        SocketAddr::V4(v4) => v4.ip().is_loopback(),
        SocketAddr::V6(v6) => v6.ip().is_loopback(),
    }
}
