package io.github.sagernet.libghostty

import io.github.sagernet.libghostty.GhosttyTerminalView.ScrollRouting

/**
 * Remote host/server scrolling sends row deltas over the network. An unbounded
 * fling turns one short swipe into thousands of rows: OMP's transcript has
 * 9k+ host rows, so the first flick jumped the host scrollbar straight to the
 * top. Bound the inertia to half a screen; the finger drag still tracks
 * exactly, and the user can flick again or use the host scrollbar.
 */
internal fun boundedFling(routing: ScrollRouting, altOrMouse: Boolean): Boolean =
    altOrMouse ||
        routing == ScrollRouting.REMOTE_HOST ||
        routing == ScrollRouting.REMOTE_SERVER

/** Maximum fling travel in pixels: half the viewport, never the whole host. */
internal fun flingRangePx(rows: Int, cellHeight: Int): Int {
    val safeRows = rows.coerceAtLeast(1)
    val safeCell = cellHeight.coerceAtLeast(1)
    return (safeRows / 2) * safeCell
}
