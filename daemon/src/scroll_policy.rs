//! Single decision point for translating a scroll request into terminal
//! actions.
//!
//! Both terminal backends used to carry their own copy of the direction/
//! page-key/ignore rules:
//!
//! - the Herdr TUI backend forwards a semantic `terminal.scroll` RPC (Herdr
//!   itself decides between PgUp/PgDn bytes and host scrollback), and
//! - the herdr-core backend can only type raw bytes into the PTY, so it must
//!   not send PgUp into a shell.
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
    /// Send PgUp (`up`) or PgDn (`!up`) `pages` times.
    PageKey { up: bool, pages: u32 },
    /// Send wheel notches in the requested direction (negative = up).
    Wheel { notches: i64 },
    /// Nothing to send.
    Ignore,
}

/// xterm's conventional rows per wheel notch for mouse-reporting apps.
const MOUSE_ROWS_PER_NOTCH: u64 = 3;

/// Rows advanced by one wheel event for apps that scroll on the wheel, or
/// `None` when the app uses page keys. Mirrors AgentMatrix.kt's scroll routes.
fn wheel_rows_per_event(identity: Option<&str>) -> Option<u64> {
    let identity = identity?;
    if identity.eq_ignore_ascii_case("qwen") {
        Some(3)
    } else if identity.eq_ignore_ascii_case("codex")
        || identity.eq_ignore_ascii_case("pi")
        || identity.eq_ignore_ascii_case("opencode")
    {
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
    ScrollAction::PageKey { up, pages: rows.min(u32::MAX as u64) as u32 }
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
        assert_eq!(scroll_action(up), ScrollAction::PageKey { up: true, pages: 12 });
        let down = ScrollRequest { allow_page_key: true, ..core(3) };
        assert_eq!(scroll_action(down), ScrollAction::PageKey { up: false, pages: 3 });
    }

    #[test]
    fn alt_screen_alone_allows_page_keys_on_core() {
        let action = scroll_action(ScrollRequest { alt_screen: true, ..core(-2) });
        assert_eq!(action, ScrollAction::PageKey { up: true, pages: 2 });
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
        for identity in ["codex", "pi", "opencode", "Codex"] {
            let request = ScrollRequest { app_identity: Some(identity), ..herdr(-4) };
            assert_eq!(
                scroll_action(request),
                ScrollAction::Wheel { notches: -4 },
                "{identity} advances one row per notch"
            );
        }
        let qwen = ScrollRequest { app_identity: Some("qwen"), ..herdr(9) };
        assert_eq!(scroll_action(qwen), ScrollAction::Wheel { notches: 3 });
    }

    #[test]
    fn known_page_key_app_with_permission_scrolls_pages() {
        let action = scroll_action(ScrollRequest {
            app_identity: Some("omp"),
            allow_page_key: true,
            ..core(-4)
        });
        assert_eq!(action, ScrollAction::PageKey { up: true, pages: 4 });
    }

    #[test]
    fn herdr_transport_always_forwards_page_keys() {
        // The TUI path relies on Herdr's host-scrollback fallback and never
        // carries the core-only allow bit today.
        assert_eq!(scroll_action(herdr(-5)), ScrollAction::PageKey { up: true, pages: 5 });
        assert_eq!(
            scroll_action(ScrollRequest { allow_page_key: false, ..herdr(5) }),
            ScrollAction::PageKey { up: false, pages: 5 }
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
}
