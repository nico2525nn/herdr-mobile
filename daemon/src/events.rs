use std::sync::atomic::{AtomicU64, Ordering};

use tokio::sync::broadcast;
use tracing::warn;

use crate::cache::SessionCache;
use crate::model::SemanticEvent;

/// Global sequence bus. `seq` is global, never per-connection: every subscriber sees the same
/// numbers in the same order, and a newcomer gets `stream.ready` at the current cursor with
/// no replay.
pub struct EventBus {
    tx: broadcast::Sender<SemanticEvent>,
    cursor: AtomicU64,
}

impl EventBus {
    pub fn new() -> EventBus {
        // 512 buffered events; a subscriber slower than that is dropped rather than stalling
        // the broadcast.
        let (tx, _) = broadcast::channel(512);
        EventBus {
            tx,
            cursor: AtomicU64::new(0),
        }
    }

    pub fn cursor(&self) -> i64 {
        self.cursor.load(Ordering::SeqCst) as i64
    }

    pub fn subscribe(&self) -> broadcast::Receiver<SemanticEvent> {
        self.tx.subscribe()
    }

    pub fn broadcast(&self, event: SemanticEvent) {
        self.cursor.store(event.seq as u64, Ordering::SeqCst);
        if self.tx.receiver_count() == 0 {
            return;
        }
        if let Err(e) = self.tx.send(event) {
            warn!("event broadcast had no live receivers: {e}");
        }
    }

    /// The cache is untrustworthy (reconnect, events_lost): force every client to refetch.
    pub fn broadcast_snapshot_required(&self, cache: &SessionCache) {
        let seq = cache.seq() + 1;
        // Reserve the number on the cache so the next real event cannot collide with it.
        let event = SemanticEvent {
            seq,
            kind: "snapshot.required".to_string(),
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
        // Advance the cache counter past the number we just used.
        let _ = cache.next_seq_for_broadcast(seq);
        self.broadcast(event);
    }
}

impl Default for EventBus {
    fn default() -> Self {
        Self::new()
    }
}
