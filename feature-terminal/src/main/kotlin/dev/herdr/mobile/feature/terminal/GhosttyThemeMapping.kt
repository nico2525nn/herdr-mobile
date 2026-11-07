package dev.herdr.mobile.feature.terminal

import dev.herdr.mobile.core.model.CursorStyle
import dev.herdr.mobile.feature.terminal.theme.TerminalColorScheme
import io.github.sagernet.libghostty.GhosttyCursorStyle
import io.github.sagernet.libghostty.GhosttyTheme

/**
 * The app's nine terminal themes → libghostty's theme shape.
 *
 * Only the 16 ANSI slots are ours; the remaining 240 palette entries stay
 * null so the library keeps its xterm defaults (programs that use 256-color
 * indexes keep working).
 */
internal fun TerminalColorScheme.toGhosttyTheme(): GhosttyTheme {
    val palette = ArrayList<Int?>(256)
    for (color in ansi) palette += color.toArgb()
    repeat(256 - palette.size) { palette += null }
    return GhosttyTheme(
        foreground = foreground.toArgb(),
        background = background.toArgb(),
        cursorColor = cursor.toArgb(),
        // Selection uses the classic fg/bg swap; the theme's selection colour
        // is not part of the library's theme shape.
        selectionBackground = null,
        selectionForeground = null,
        palette = palette,
    )
}

internal fun CursorStyle.toGhosttyCursorStyle(): GhosttyCursorStyle = when (this) {
    CursorStyle.BLOCK -> GhosttyCursorStyle.BLOCK
    CursorStyle.UNDERLINE -> GhosttyCursorStyle.UNDERLINE
    CursorStyle.BAR -> GhosttyCursorStyle.BAR
}
