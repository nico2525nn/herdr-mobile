use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;

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
    /// Single sequencer: allocation, send and snapshot cursor advance are one
    /// critical section, so concurrent producers cannot publish out of order
    /// or reuse a sequence number.
    publish_lock: Mutex<()>,
}

impl EventBus {
    pub fn new() -> EventBus {
        // 512 buffered events; a subscriber slower than that is dropped rather than stalling
        // the broadcast.
        let (tx, _) = broadcast::channel(512);
        EventBus {
            tx,
            cursor: AtomicU64::new(0),
            publish_lock: Mutex::new(()),
        }
    }

    pub fn cursor(&self) -> i64 {
        self.cursor.load(Ordering::SeqCst) as i64
    }

    pub fn subscribe(&self) -> broadcast::Receiver<SemanticEvent> {
        self.tx.subscribe()
    }

    /// Sequenced broadcast for a normal cache event. The snapshot cursor is
    /// advanced after the send so a refetch observes the event as current.
    pub fn publish(&self, cache: &SessionCache, event: SemanticEvent) {
        self.publish_event(cache, event, true);
    }

    /// The cache is stale; clients must refetch. Reserve a unique seq for the
    /// notification, but do NOT stamp the stale snapshot (a refetch would then
    /// look current and no later gap would heal it).
    pub fn publish_without_stamp(&self, cache: &SessionCache, event: SemanticEvent) {
        self.publish_event(cache, event, false);
    }

    fn publish_event(&self, cache: &SessionCache, mut event: SemanticEvent, stamp: bool) {
        let _guard = self.publish_lock.lock().expect("event sequencer poisoned");
        let seq = cache.allocate_seq();
        event.seq = seq;
        self.cursor.store(seq as u64, Ordering::SeqCst);
        if self.tx.receiver_count() > 0 {
            if let Err(e) = self.tx.send(event) {
                warn!("event broadcast had no live receivers: {e}");
            }
        }
        if stamp {
            cache.stamp_snapshot_seq(seq);
        }
    }

    /// The live snapshot's `update` block just changed (new check result):
    /// clients refetch once instead of maintaining a second fold path.
    pub fn broadcast_update(&self, cache: &SessionCache) {
        let event = SemanticEvent {
            seq: 0,
            kind: "herdr.update_available".to_string(),
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
        // The snapshot update block is already fresh; publish() stamps the
        // reserved seq so a client refetch cannot receive the older N.
        self.publish(cache, event);
    }

    /// The cache is untrustworthy (reconnect, events_lost): force every client to refetch.
    pub fn broadcast_snapshot_required(&self, cache: &SessionCache) {
        let event = SemanticEvent {
            seq: 0,
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
        // Reserve the number so the next real event cannot collide. Do NOT
        // stamp the snapshot with it: the cache is still the pre-break copy,
        // and a client refetching now would receive stale data marked current
        // (then no gap would ever force another fetch).
        self.publish_without_stamp(cache, event);
    }
}

impl Default for EventBus {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    fn event() -> SemanticEvent {
        SemanticEvent {
            seq: 0,
            kind: "test".to_owned(),
            at: crate::cache::now_rfc3339(),
            workspace_id: None,
            tab_id: None,
            pane_id: None,
            status: None,
            label: None,
            message: None,
            revision: None,
            detail: None,
        }
    }

    #[tokio::test]
    async fn concurrent_publishers_get_unique_monotonic_sequences() {
        let cache = Arc::new(SessionCache::new(crate::herdr::HerdrClient::new(
            "/nonexistent.sock".into(),
            "herdr".into(),
        )));
        let bus = Arc::new(EventBus::new());
        let mut rx = bus.subscribe();
        let mut tasks = Vec::new();
        for _ in 0..32 {
            let cache = cache.clone();
            let bus = bus.clone();
            tasks.push(tokio::spawn(async move { bus.publish(&cache, event()) }));
        }
        for task in tasks {
            task.await.unwrap();
        }

        let mut seen = Vec::new();
        for _ in 0..32 {
            seen.push(rx.recv().await.unwrap().seq);
        }
        assert_eq!(seen, (1..=32).collect::<Vec<_>>());
        assert_eq!(bus.cursor(), 32);
        assert_eq!(cache.seq(), 32);
    }
}
