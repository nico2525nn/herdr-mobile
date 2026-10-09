package dev.herdr.mobile.terminal.view

import dev.herdr.mobile.terminal.emulator.TerminalCell

/**
 * One immutable content snapshot: full history + live screen, safe on any thread.
 *
 * The VIEW owns the viewport offset (termux mTopRow model) and slices
 * [history] + [screen] itself, so scrolling never round-trips through the
 * emulator and never copies the grid. [history] rows are shared references
 * (the emulator never mutates a banked row in place); [screen] rows are
 * defensive copies (the live grid mutates under us).
 *
 * The View diffs [revision] to skip redraws.
 */
data class FrameSnapshot(
    val history: List<List<TerminalCell>>,
    val screen: List<List<TerminalCell>>,
    val cursorRow: Int,
    val cursorCol: Int,
    val cursorVisible: Boolean,
    val usingAlternateScreen: Boolean,
    val revision: Long,
    val cols: Int,
    val rowCount: Int,
) {
    companion object {
        fun empty() = FrameSnapshot(
            history = emptyList(),
            screen = emptyList(),
            cursorRow = 0,
            cursorCol = 0,
            cursorVisible = false,
            usingAlternateScreen = false,
            revision = -1,
            cols = 0,
            rowCount = 0,
        )
    }
}
