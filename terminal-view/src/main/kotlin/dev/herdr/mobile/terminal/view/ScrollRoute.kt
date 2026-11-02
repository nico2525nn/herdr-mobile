package dev.herdr.mobile.terminal.view

import dev.herdr.mobile.core.model.AgentMatrix

/**
 * Who owns vertical scrolling for the attached pane.
 *
 * This is the whole routing policy in one place. It used to be a chain of
 * booleans spread over the host (`remoteScrollHost`), the screen
 * (`appIdentity != null`) and the view client, and the gap between them is
 * exactly where "scrolling does nothing on a shell" came from.
 */
enum class ScrollOwner {
    /** Termux shifts its own transcript (instant, no round trip). */
    LOCAL,

    /** The pane's content is repainted from the host, so scrolling is a request. */
    REMOTE,
}

/** How a [ScrollOwner.REMOTE] pane wants the scroll delivered. */
enum class RemoteScrollKind {
    /** SGR mouse-wheel notches: agent UIs that track the mouse. */
    WHEEL,

    /** Page keys: panes whose transcript lives in the host's scrollback. */
    PAGE_KEY,
}

/**
 * Routing decision, as a pure function so it can be tested without a device.
 *
 * The rule is "can anything actually move?", not "which app is this?":
 *
 * - Alt-screen and mouse tracking mean the app paints its own viewport, so the
 *   local transcript is stale by definition → remote.
 * - No local transcript rows means a local scroll has nothing to shift. That is
 *   the normal shape of a mirrored full-screen TUI (less, vim, top, agent
 *   panes): Herdr replays content frames, so the mirror never accumulates
 *   scrollback and the app label is irrelevant. → remote.
 * - Otherwise the local transcript wins, including panes that still carry an
 *   agent label. Herdr keeps the label after the agent exits (a plain
 *   `user@host$` prompt still reports the old agent); trusting labels sent SGR
 *   wheel bytes into a shell that ignored them.
 *
 * Per-app route tables therefore only choose the *unit* ([remoteScrollKind]),
 * never whether scrolling happens at all.
 */
fun scrollOwner(
    altScreen: Boolean,
    mouseTracking: Boolean,
    hostBackedAgent: Boolean,
    localHistoryRows: Int,
): ScrollOwner = when {
    altScreen || mouseTracking -> ScrollOwner.REMOTE
    localHistoryRows <= 0 -> ScrollOwner.REMOTE
    else -> ScrollOwner.LOCAL
}

/**
 * Wheel notches only when the app has actually enabled mouse tracking: SGR
 * wheel bytes are inert input for a program that never asked for them, which
 * looks exactly like a dead scroll. Everything else — alt-screen agent UIs
 * included — is paged through the host's own scrollback.
 */
fun remoteScrollKind(agent: String?, mouseTracking: Boolean): RemoteScrollKind = when {
    !mouseTracking -> RemoteScrollKind.PAGE_KEY
    AgentMatrix.useWheelScroll(agent) -> RemoteScrollKind.WHEEL
    else -> RemoteScrollKind.PAGE_KEY
}
