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
 * Alt-screen and mouse tracking mean the app paints its own viewport: the local
 * transcript is stale by definition, so scroll has to be a remote request.
 *
 * Everything else prefers the local transcript — including panes that carry an
 * agent label. Herdr's TUI keeps a stale label after the agent exits (a plain
 * `user@host$` prompt still reports the old agent), and trusting it sent SGR
 * wheel bytes into a shell that ignores them: the user saw a dead scroll.
 *
 * The one remote case left is a main-screen agent with no banked history (omp,
 * whose transcript lives in host scrollback): there is nothing local to shift,
 * so page keys are the only way to reach it.
 */
fun scrollOwner(
    altScreen: Boolean,
    mouseTracking: Boolean,
    hostBackedAgent: Boolean,
    localHistoryRows: Int,
): ScrollOwner = when {
    altScreen || mouseTracking -> ScrollOwner.REMOTE
    hostBackedAgent && localHistoryRows <= 0 -> ScrollOwner.REMOTE
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
