package dev.herdr.mobile.terminal.view

/**
 * Evidence that one scroll request reached the app.
 *
 * The app cannot know statically whether a TUI honours PgUp, wheel bytes or a
 * host offset, and the mirror carries no DECSET state to ask. So the first
 * request per gesture is checked: capture the visible screen before it moves,
 * then compare after the repaint settles.
 */
internal data class ScrollProbe(
    val mechanism: ScrollMechanism,
    val rows: Int,
    /** Visible lines when the probe started (the scroll anchor set). */
    val lines: List<String>,
    /** Hash of [lines]: one screen read per probe, not two. */
    val signature: Int,
)

/** Upper bound for a measured shift; a real page is well below this. */
internal const val MAX_MEASURED_ROWS = 80

/** FNV-1a over the visible lines: cheap, stable while the pane is idle. */
internal fun screenSignature(lines: List<String>): Int {
    var hash = 0x811c9dc5.toInt()
    for (line in lines) {
        for (c in line) hash = (hash xor c.code) * 0x01000193
        hash = (hash xor '\n'.code) * 0x01000193
    }
    return hash
}

/**
 * Rows the transcript moved, estimated by finding the vertical shift that best
 * matches the old screen against the new one.
 *
 * A single anchor line is fragile: live panes repaint rows (spinners,
 * counters) and a diagnostics overlay may own the first line. Voting over
 * every non-blank line rejects those outliers. Returns 0 unless a shift
 * explains a meaningful fraction of the screen, so a live repaint is not
 * mistaken for a scroll.
 */
internal fun movedRows(
    before: List<String>,
    after: List<String>,
    maxRows: Int = MAX_MEASURED_ROWS,
): Int {
    if (before.isEmpty() || after.isEmpty()) return 0
    val anchors = before.count { it.isNotBlank() }
    if (anchors == 0) return 0
    var bestShift = 0
    var bestScore = 0
    val maxShift = minOf(after.size - 1, maxRows)
    for (shift in 1..maxShift) {
        var score = 0
        for (i in 0 until minOf(before.size, after.size - shift)) {
            if (before[i].isNotBlank() && before[i] == after[i + shift]) score++
        }
        if (score > bestScore) {
            bestScore = score
            bestShift = shift
        }
    }
    return if (bestScore >= maxOf(3, anchors / 3)) bestShift else 0
}
