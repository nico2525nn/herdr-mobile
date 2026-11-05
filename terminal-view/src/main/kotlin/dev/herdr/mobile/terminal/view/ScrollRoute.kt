package dev.herdr.mobile.terminal.view


/**
 * Where a scroll gesture should go.
 *
 * Decided from what the transport can actually prove, not from an app name:
 *
 * - [HOST] — herdr reports scrollback for the pane
 *   (`max_offset_from_bottom > 0`). The host offset is line-exact and works for
 *   any app, so it always wins.
 * - [APP_INPUT] — the pane is a full-screen program: it enabled the alternate
 *   screen and/or mouse tracking, or the mirror never saw those flags because
 *   the TUI backend replays frames instead of bytes. Full-screen apps paint
 *   their own viewport, so the only levers are wheel notches and page keys.
 * - [LOCAL] — a plain shell with a real transcript in this emulator.
 * - [NONE] — nothing to move.
 */
enum class ScrollSource { HOST, APP_INPUT, LOCAL, NONE }

fun scrollSource(
    hostScrollKnown: Boolean,
    hostScrollable: Boolean,
    agentPane: Boolean,
    altScreen: Boolean,
    mouseTracking: Boolean,
    localHistoryRows: Int,
): ScrollSource = when {
    // The host's report is authoritative and covers every app: scrollback
    // means the host can move lines (shells and main-screen apps); no
    // scrollback means the pane is a full-screen program painting its own
    // viewport. This is the signal that does not need app labels or a DECSET
    // the TUI mirror never sees.
    hostScrollKnown && hostScrollable -> ScrollSource.HOST
    hostScrollKnown -> ScrollSource.APP_INPUT
    hostScrollable -> ScrollSource.HOST
    altScreen || mouseTracking -> ScrollSource.APP_INPUT
    agentPane -> ScrollSource.APP_INPUT
    localHistoryRows > 0 -> ScrollSource.LOCAL
    else -> ScrollSource.APP_INPUT
}

/**
 * How input reaches a full-screen app.
 *
 * Every TUI audited here (codex, omp, opencode, grok, pi) enables SGR mouse
 * reporting (`?1000h`/`?1002h`/`?1003h` + `?1006h`) and the alternate screen,
 * so a wheel notch is the fine-grained lever and a page key is the coarse one.
 * The order is measured at runtime: wheel first, page keys when a wheel gesture
 * moves nothing.
 */
enum class ScrollMechanism { HOST, WHEEL, PAGE_KEY }

/**
 * The one lever for a full-screen pane, from the audited per-agent table.
 *
 * This is deliberately not probed at runtime: the earlier probe mistook a live
 * repaint for "wheel did nothing" and silently downgraded codex/pi to whole
 * pages. The table is the behaviour these apps actually implement (codex, pi,
 * opencode and qwen scroll on SGR wheel reports; omp, grok and claude take page
 * keys), and the app never gets to second-guess it.
 */
fun scrollMechanisms(
    hostScrollable: Boolean,
    agentPane: Boolean,
    wheelAgent: Boolean,
): List<ScrollMechanism> = buildList {
    if (hostScrollable) {
        add(ScrollMechanism.HOST)
    } else {
        add(if (wheelAgent) ScrollMechanism.WHEEL else ScrollMechanism.PAGE_KEY)
    }
}

/**
 * The lever list for the user's chosen mode.
 *
 * AUTO follows the audited table; the explicit modes exist because the same
 * app can behave differently per setup (a TUI that enables mouse reporting in
 * one config and not another). LOCAL means "let the mirror's own transcript
 * scroll", which is what plain shells use.
 */
fun scrollMechanismsForMode(
    mode: dev.herdr.mobile.core.model.ScrollMode,
    hostScrollable: Boolean,
    agentPane: Boolean,
    wheelAgent: Boolean,
): List<ScrollMechanism> = when (mode) {
    dev.herdr.mobile.core.model.ScrollMode.AUTO ->
        scrollMechanisms(hostScrollable, agentPane, wheelAgent)
    dev.herdr.mobile.core.model.ScrollMode.WHEEL -> listOf(ScrollMechanism.WHEEL)
    dev.herdr.mobile.core.model.ScrollMode.PAGE -> listOf(ScrollMechanism.PAGE_KEY)
    dev.herdr.mobile.core.model.ScrollMode.LOCAL -> emptyList()
}
