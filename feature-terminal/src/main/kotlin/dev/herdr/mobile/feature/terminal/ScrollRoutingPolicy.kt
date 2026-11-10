package dev.herdr.mobile.feature.terminal

import dev.herdr.mobile.core.model.AgentMatrix
import dev.herdr.mobile.core.model.Pane
import dev.herdr.mobile.core.model.ScrollMode
import io.github.sagernet.libghostty.GhosttyTerminalView.ScrollRouting

/**
 * Which scroll strategy a pane needs.
 *
 * Attach replays text, not the app's DECSET history, so the engine cannot know
 * on its own whether the server owns the history. The snapshot does: a pane
 * with host scrollback scrolls rows on the server; a full-screen pane (scroll
 * reported, no scrollback) pages on the app; anything unreported falls back to
 * the engine's own viewport (the prelude banks client-side history).
 */
internal fun scrollRoutingFor(mode: ScrollMode, pane: Pane?): ScrollRouting = when (mode) {
    // The host's absolute row scroll is always more faithful than typing
    // keys at a full-screen app, so the explicit wheel/page modes only choose
    // the transport for panes without host scrollback.
    ScrollMode.LOCAL -> ScrollRouting.LOCAL
    ScrollMode.WHEEL ->
        if (pane?.hostScrollable == true) ScrollRouting.REMOTE_HOST else ScrollRouting.REMOTE_WHEEL
    ScrollMode.PAGE ->
        when {
            pane?.hostScrollable == true -> ScrollRouting.REMOTE_HOST
            pane != null && !AgentMatrix.isKnownApp(pane.agent) -> ScrollRouting.REMOTE_SERVER
            else -> ScrollRouting.REMOTE_PAGE
        }
    ScrollMode.AUTO -> when {
        pane == null -> ScrollRouting.AUTO
        pane.scroll != null && pane.hostScrollable -> ScrollRouting.REMOTE_HOST
        pane.scroll != null -> when {
            AgentMatrix.isKnownApp(pane.agent) -> {
                if (AgentMatrix.useWheelScroll(pane.agent)) {
                    ScrollRouting.REMOTE_WHEEL
                } else {
                    ScrollRouting.REMOTE_PAGE
                }
            }
            // No agent and no host scrollback: could be a shell (PgUp/PgDn
            // would hit readline history-search) or a full-screen app like
            // less. The daemon checks the foreground process before typing
            // keys, so send rows and let it decide.
            else -> ScrollRouting.REMOTE_SERVER
        }
        // No host scroll state (core backend): the engine received the raw
        // byte stream and therefore owns the real modes and local scrollback.
        // AUTO is authoritative here; typing page keys at an append-only TUI
        // (OMP) does nothing.
        else -> ScrollRouting.AUTO
    }
}
