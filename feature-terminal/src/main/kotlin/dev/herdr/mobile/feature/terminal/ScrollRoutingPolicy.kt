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
    ScrollMode.LOCAL -> ScrollRouting.LOCAL
    ScrollMode.WHEEL -> ScrollRouting.REMOTE_WHEEL
    ScrollMode.PAGE -> ScrollRouting.REMOTE_PAGE
    ScrollMode.AUTO -> when {
        pane == null -> ScrollRouting.AUTO
        pane.scroll != null && pane.hostScrollable -> ScrollRouting.REMOTE_HOST
        pane.scroll != null ->
            if (AgentMatrix.useWheelScroll(pane.agent)) {
                ScrollRouting.REMOTE_WHEEL
            } else {
                ScrollRouting.REMOTE_PAGE
            }
        else -> ScrollRouting.AUTO
    }
}
