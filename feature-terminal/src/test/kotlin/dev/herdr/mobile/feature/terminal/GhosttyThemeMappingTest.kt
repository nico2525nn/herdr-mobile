package dev.herdr.mobile.feature.terminal

import dev.herdr.mobile.core.model.CursorStyle
import dev.herdr.mobile.feature.terminal.theme.TerminalColor
import dev.herdr.mobile.feature.terminal.theme.TerminalColorScheme
import io.github.sagernet.libghostty.GhosttyCursorStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GhosttyThemeMappingTest {

    private fun color(index: Int) = TerminalColor(index, index, index)

    private val scheme = TerminalColorScheme(
        background = TerminalColor(1, 2, 3),
        foreground = TerminalColor(4, 5, 6),
        cursor = TerminalColor(7, 8, 9),
        selection = TerminalColor(10, 11, 12),
        ansi = (0 until 16).map(::color),
    )

    @Test
    fun `theme colors become argb`() {
        val theme = scheme.toGhosttyTheme()
        assertEquals(0xFF010203.toInt(), theme.background)
        assertEquals(0xFF040506.toInt(), theme.foreground)
        assertEquals(0xFF070809.toInt(), theme.cursorColor)
    }

    @Test
    fun `the sixteen ansi slots are ours and the rest stay library defaults`() {
        val theme = scheme.toGhosttyTheme()
        assertEquals(256, theme.palette.size)
        for (index in 0 until 16) {
            assertEquals(0xFF000000.toInt() or (index shl 16) or (index shl 8) or index, theme.palette[index])
        }
        assertNull(theme.palette[16])
        assertNull(theme.palette[255])
    }

    @Test
    fun `selection defers to the engine's inverse video`() {
        val theme = scheme.toGhosttyTheme()
        assertNull(theme.selectionBackground)
        assertNull(theme.selectionForeground)
    }

    @Test
    fun `cursor styles map one to one`() {
        assertEquals(GhosttyCursorStyle.BLOCK, CursorStyle.BLOCK.toGhosttyCursorStyle())
        assertEquals(GhosttyCursorStyle.UNDERLINE, CursorStyle.UNDERLINE.toGhosttyCursorStyle())
        assertEquals(GhosttyCursorStyle.BAR, CursorStyle.BAR.toGhosttyCursorStyle())
    }
}
