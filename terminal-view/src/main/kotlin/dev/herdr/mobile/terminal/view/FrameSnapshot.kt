package dev.herdr.mobile.terminal.view

import dev.herdr.mobile.terminal.emulator.TerminalCell

/**
 * One immutable viewport, safe to read on any thread.
 *
 * The View diffs [revision] to skip redraws; everything else is plain data for Canvas.
 */
data class FrameSnapshot(
    val rows: List<List<TerminalCell>>,
    val cursorRow: Int,
    val cursorCol: Int,
    val cursorVisible: Boolean,
    val usingAlternateScreen: Boolean,
    val scrollbackOffset: Int,
    val revision: Long,
    val cols: Int,
    val rowCount: Int,
) {
    companion object {
        fun empty() = FrameSnapshot(
            rows = emptyList(),
            cursorRow = 0,
            cursorCol = 0,
            cursorVisible = false,
            usingAlternateScreen = false,
            scrollbackOffset = 0,
            revision = -1,
            cols = 0,
            rowCount = 0,
        )
    }
}
