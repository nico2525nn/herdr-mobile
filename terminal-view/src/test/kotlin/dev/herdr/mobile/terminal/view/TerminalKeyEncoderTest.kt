package dev.herdr.mobile.terminal.view

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalKeyEncoderTest {

    @Test
    fun `default layout has two rows of seven`() {
        assertEquals(2, TerminalKeyEncoder.DEFAULT_LAYOUT.size)
        assertEquals(7, TerminalKeyEncoder.DEFAULT_LAYOUT[0].size)
        assertEquals(7, TerminalKeyEncoder.DEFAULT_LAYOUT[1].size)
    }

    @Test
    fun `arrows encode as CSI`() {
        val row1 = TerminalKeyEncoder.DEFAULT_LAYOUT[0]
        val up = row1.first { it.label == "↑" }
        assertArrayEquals(byteArrayOf(0x1B, '['.code.toByte(), 'A'.code.toByte()), up.bytes)
    }

    @Test
    fun `ctrl modifier maps letters to control codes`() {
        val c = TerminalKeyEncoder.Key("c", bytes = byteArrayOf('c'.code.toByte()))
        assertArrayEquals(
            byteArrayOf(0x03),
            TerminalKeyEncoder.withModifier(TerminalKeyEncoder.Modifier.CTRL, c),
        )
    }

    @Test
    fun `alt modifier prefixes escape`() {
        val x = TerminalKeyEncoder.Key("x", bytes = byteArrayOf('x'.code.toByte()))
        assertArrayEquals(
            byteArrayOf(0x1B, 'x'.code.toByte()),
            TerminalKeyEncoder.withModifier(TerminalKeyEncoder.Modifier.ALT, x),
        )
    }

    @Test
    fun `interrupt is ctrl-c byte`() {
        assertArrayEquals(byteArrayOf(0x03), TerminalKeyEncoder.interrupt())
    }
}
