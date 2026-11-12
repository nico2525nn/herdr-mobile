//! Single decision point for translating a scroll request into terminal
//! actions.
//!
//! Both terminal backends used to carry their own copy of the direction/
//! page-key/ignore rules:
//!
//! - the Herdr TUI backend forwards a semantic `terminal.scroll` RPC (Herdr
//!   itself decides between PgUp/PgDn bytes and host scrollback) but prefers a
//!   `pane.scroll` ABSOLUTE host offset when the pane has scrollback, and
//! - the herdr-core backend can only type raw bytes into the PTY, so it must
//!   not send PgUp into a shell.
//!
//! Requests arrive in ROWS. Neither fallback can scroll by rows (page keys
//! move one screen, wheel events move an app-defined stride), so [rows_to_pages]
//! converts for the page-key transports while the host-scroll path keeps rows
//! and clamps them to an absolute offset with [host_scroll_target].
//!
//! Everything here is plain data: no PTY or session access, so both call sites
//! and the tests share exactly one implementation.

/// Which transport will carry the resulting action.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ScrollBackend {
    /// Herdr TUI (`terminal.scroll`). Herdr falls back to host scrollback
    /// when the app ignores page keys, so forwarding a page-key request is
    /// always safe; the same RPC can also ask for wheel routing.
    Herdr,
    /// herdr-core: raw PTY bytes. PgUp/PgDn typed into a shell can recall
    /// history or interrupt the foreground program, so page keys need
    /// evidence that the viewport belongs to an agent or alt-screen app.
    Core,
}

/// Plain-data facts about one scroll request.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ScrollRequest<'a> {
    /// Core `status.app` identity (e.g. `codex`, `qwen`); `None` for a shell.
    pub app_identity: Option<&'a str>,
    /// The attached terminal is in the alternate screen (full-screen TUI).
    pub alt_screen: bool,
    /// The app enabled mouse tracking and owns the viewport/wheel.
    pub mouse_tracking: bool,
    /// The client asserted that page keys are safe for this view.
    pub allow_page_key: bool,
    /// Signed request: negative = toward history (up), positive = toward live
    /// (down). The magnitude is in terminal rows.
    pub lines: i64,
    /// Transport that will carry the action.
    pub backend: ScrollBackend,
}

/// What the backend should send for a scroll request.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ScrollAction {
    /// Send PgUp (`up`) or PgDn (`!up`) once per SCREEN of `rows` rows. The
    /// magnitude stays in rows: only the transport knows its viewport height,
    /// so it converts to whole pages with [rows_to_pages].
    PageKey { up: bool, rows: u32 },
    /// Send wheel notches in the requested direction (negative = up).
    Wheel { notches: i64 },
    /// Nothing to send.
    Ignore,
}

/// Upper bound on page-key presses for one scroll request. A browser/app
/// may coalesce a fling into a huge row delta; uncapped, that would jump the
/// entire scrollback in a single frame.
pub const MAX_FALLBACK_PAGES: u32 = 3;

/// xterm's conventional rows per wheel notch for mouse-reporting apps.
const MOUSE_ROWS_PER_NOTCH: u64 = 3;

/// Convert a ROW delta into whole page-key presses for a `viewport_rows`-row
/// screen. Page keys are the only row-less scroll herdr-core can type and
/// Herdr maps one page onto one screen, so rows must be divided, never passed
/// through: a smaller-than-screen request still presses one page (never
/// zero), and the result is capped at [MAX_FALLBACK_PAGES].
pub fn rows_to_pages(rows: u32, viewport_rows: u32) -> u32 {
    if rows == 0 {
        return 0;
    }
    // Unknown geometry: never multiply a delta by a guessed page height.
    let pages = if viewport_rows == 0 {
        1
    } else {
        u64::from(rows).div_ceil(u64::from(viewport_rows))
    };
    pages.clamp(1, u64::from(MAX_FALLBACK_PAGES)) as u32
}

/// Absolute host-scroll target for a row delta: `clamp(current ± rows, 0, max)`,
/// where scrolling up moves AWAY from the live bottom (increasing
/// `offset_from_bottom`) and down moves toward it.
///
/// Returns `None` when the pane cannot move that way (already at the clamp
/// boundary), so callers skip the RPC instead of re-asserting the same offset.
pub fn host_scroll_target(current: u64, max: u64, up: bool, rows: u32) -> Option<u64> {
    if rows == 0 {
        return None;
    }
    let rows = u64::from(rows);
    let max = max.max(current);
    let target = if up { current.saturating_add(rows) } else { current.saturating_sub(rows) };
    let target = target.min(max);
    (target != current).then_some(target)
}

/// Rows advanced by one wheel event for apps that scroll on the wheel, or
/// `None` when the app uses page keys. Mirrors AgentMatrix.kt's scroll routes.
fn wheel_rows_per_event(identity: Option<&str>) -> Option<u64> {
    let identity = identity?;
    if identity.eq_ignore_ascii_case("qwen") || identity.eq_ignore_ascii_case("opencode") {
        Some(3)
    } else if identity.eq_ignore_ascii_case("pi") {
        Some(2)
    } else if identity.eq_ignore_ascii_case("codex") {
        Some(1)
    } else {
        None
    }
}

fn wheel(rows: u64, stride: u64, up: bool) -> ScrollAction {
    let notches = (rows / stride).min(i64::MAX as u64) as i64;
    ScrollAction::Wheel { notches: if up { -notches } else { notches } }
}

fn page_key(rows: u64, up: bool) -> ScrollAction {
    ScrollAction::PageKey { up, rows: rows.min(u32::MAX as u64) as u32 }
}

/// Decide what a scroll request should do. Pure function: identical inputs
/// always produce the same action.
pub fn scroll_action(request: ScrollRequest<'_>) -> ScrollAction {
    if request.lines == 0 {
        return ScrollAction::Ignore;
    }
    let up = request.lines < 0;
    let rows = request.lines.unsigned_abs();
    match request.backend {
        ScrollBackend::Herdr => {
            // Wheel-capable apps own the wheel: mouse-tracking TUIs use the
            // xterm default, and AgentMatrix wheel apps use their own stride.
            if request.mouse_tracking {
                return wheel(rows, MOUSE_ROWS_PER_NOTCH, up);
            }
            if let Some(stride) = wheel_rows_per_event(request.app_identity) {
                return wheel(rows, stride, up);
            }
            // Herdr routes page-key requests itself, so the client intent bit
            // is not needed on this transport.
            page_key(rows, up)
        }
        ScrollBackend::Core => {
            // Core only types PTY bytes. Its wheel path is raw SGR bytes sent
            // by the client, never this control frame, so the only decisions
            // here are "safe to type PgUp/PgDn?" and direction.
            if request.alt_screen || request.allow_page_key {
                page_key(rows, up)
            } else {
                ScrollAction::Ignore
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn core(lines: i64) -> ScrollRequest<'static> {
        ScrollRequest {
            app_identity: None,
            alt_screen: false,
            mouse_tracking: false,
            allow_page_key: false,
            lines,
            backend: ScrollBackend::Core,
        }
    }

    fn herdr(lines: i64) -> ScrollRequest<'static> {
        ScrollRequest { backend: ScrollBackend::Herdr, ..core(lines) }
    }

    #[test]
    fn shell_without_page_key_evidence_is_ignored() {
        // Regression for bug class #2: old clients without a scroll
        // capability bit must not type PgUp into non-TUI foreground programs.
        assert_eq!(scroll_action(core(-5)), ScrollAction::Ignore);
        assert_eq!(scroll_action(core(5)), ScrollAction::Ignore);
    }

    #[test]
    fn shell_with_page_key_allowed_scrolls_pages() {
        let up = ScrollRequest { allow_page_key: true, ..core(-12) };
        assert_eq!(scroll_action(up), ScrollAction::PageKey { up: true, rows: 12 });
        let down = ScrollRequest { allow_page_key: true, ..core(3) };
        assert_eq!(scroll_action(down), ScrollAction::PageKey { up: false, rows: 3 });
    }

    #[test]
    fn alt_screen_alone_allows_page_keys_on_core() {
        let action = scroll_action(ScrollRequest { alt_screen: true, ..core(-2) });
        assert_eq!(action, ScrollAction::PageKey { up: true, rows: 2 });
    }

    #[test]
    fn alt_screen_wheel_app_routes_wheel() {
        let action = scroll_action(ScrollRequest {
            app_identity: Some("codex"),
            alt_screen: true,
            allow_page_key: true,
            ..herdr(-6)
        });
        assert_eq!(action, ScrollAction::Wheel { notches: -6 });
    }

    #[test]
    fn mouse_tracking_app_routes_wheel() {
        let down = ScrollRequest { mouse_tracking: true, allow_page_key: true, ..herdr(9) };
        assert_eq!(scroll_action(down), ScrollAction::Wheel { notches: 3 });
        let up = ScrollRequest { mouse_tracking: true, allow_page_key: true, ..herdr(-3) };
        assert_eq!(scroll_action(up), ScrollAction::Wheel { notches: -1 });
    }

    #[test]
    fn wheel_apps_use_their_identity_stride() {
        for (identity, stride) in [("codex", 1), ("pi", 2), ("opencode", 3), ("qwen", 3)] {
            let request = ScrollRequest { app_identity: Some(identity), ..herdr(-12) };
            assert_eq!(
                scroll_action(request),
                ScrollAction::Wheel { notches: -12 / stride },
                "{identity} advances {stride} rows per notch"
            );
        }
        let codex = ScrollRequest { app_identity: Some("Codex"), ..herdr(-4) };
        assert_eq!(scroll_action(codex), ScrollAction::Wheel { notches: -4 });
    }

    #[test]
    fn known_page_key_app_with_permission_scrolls_pages() {
        let action = scroll_action(ScrollRequest {
            app_identity: Some("omp"),
            allow_page_key: true,
            ..core(-4)
        });
        assert_eq!(action, ScrollAction::PageKey { up: true, rows: 4 });
    }

    #[test]
    fn herdr_transport_always_forwards_page_keys() {
        // The TUI path relies on Herdr's host-scrollback fallback and never
        // carries the core-only allow bit today.
        assert_eq!(scroll_action(herdr(-5)), ScrollAction::PageKey { up: true, rows: 5 });
        assert_eq!(
            scroll_action(ScrollRequest { allow_page_key: false, ..herdr(5) }),
            ScrollAction::PageKey { up: false, rows: 5 }
        );
    }

    #[test]
    fn zero_lines_are_ignored_on_both_transports() {
        assert_eq!(scroll_action(core(0)), ScrollAction::Ignore);
        assert_eq!(scroll_action(herdr(0)), ScrollAction::Ignore);
    }

    #[test]
    fn line_sign_selects_direction() {
        for lines in [-1, -100] {
            assert!(matches!(scroll_action(core(lines)), ScrollAction::Ignore));
            let action = scroll_action(ScrollRequest { allow_page_key: true, ..core(lines) });
            assert!(matches!(action, ScrollAction::PageKey { up: true, .. }));
        }
        for lines in [1, 100] {
            let action = scroll_action(ScrollRequest { allow_page_key: true, ..core(lines) });
            assert!(matches!(action, ScrollAction::PageKey { up: false, .. }));
        }
    }

    #[test]
    fn rows_convert_to_whole_pages() {
        // One screen == one page; partial screens round UP so a short swipe
        // still moves the view instead of turning into a no-op.
        assert_eq!(rows_to_pages(0, 40), 0);
        assert_eq!(rows_to_pages(1, 40), 1);
        assert_eq!(rows_to_pages(40, 40), 1);
        assert_eq!(rows_to_pages(41, 40), 2);
        assert_eq!(rows_to_pages(80, 40), 2);
        // Unknown geometry still yields one page for a non-zero request.
        assert_eq!(rows_to_pages(3, 0), 1);
        assert_eq!(rows_to_pages(3, 1), 3);
    }

    #[test]
    fn page_count_is_capped_against_fling_deltas() {
        assert_eq!(rows_to_pages(100_000, 40), MAX_FALLBACK_PAGES);
        assert_eq!(rows_to_pages(u32::MAX, 1), MAX_FALLBACK_PAGES);
        // Under the cap the exact page count survives.
        assert_eq!(rows_to_pages(120, 40), 3);
    }

    #[test]
    fn host_scroll_target_clamps_and_skips_no_ops() {
        // Up = away from live (larger offset), down = toward live.
        assert_eq!(host_scroll_target(0, 100, true, 5), Some(5));
        assert_eq!(host_scroll_target(5, 100, false, 3), Some(2));
        // Clamped at both ends.
        assert_eq!(host_scroll_target(98, 100, true, 5), Some(100));
        assert_eq!(host_scroll_target(2, 100, false, 5), Some(0));
        // No movement left: caller must skip the RPC.
        assert_eq!(host_scroll_target(0, 100, false, 5), None);
        assert_eq!(host_scroll_target(100, 100, true, 5), None);
        assert_eq!(host_scroll_target(10, 100, true, 0), None);
        // A max below the current offset (stale report) must not drag the
        // view backwards on an up-scroll: the no-op path wins.
        assert_eq!(host_scroll_target(30, 10, true, 5), None);
        assert_eq!(host_scroll_target(30, 10, false, 5), Some(25));
    }
}
