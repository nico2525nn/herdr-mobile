package dev.herdr.mobile.terminal.view

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `withModifiers maps single char to ctrl chord`() {
        assertArrayEquals(
            byteArrayOf(0x03),
            TerminalKeyEncoder.withModifiers(setOf(TerminalKeyEncoder.Modifier.CTRL), "c"),
        )
    }

    @Test
    fun `withModifiers stacks ctrl and alt`() {
        assertArrayEquals(
            byteArrayOf(0x1B, 0x03),
            TerminalKeyEncoder.withModifiers(
                setOf(TerminalKeyEncoder.Modifier.CTRL, TerminalKeyEncoder.Modifier.ALT),
                "c",
            ),
        )
    }

    @Test
    fun `withModifiers rejects paste and non-ascii`() {
        val ctrl = setOf(TerminalKeyEncoder.Modifier.CTRL)
        assertEquals(null, TerminalKeyEncoder.withModifiers(ctrl, "ab"))
        assertEquals(null, TerminalKeyEncoder.withModifiers(ctrl, "あ"))
        assertEquals(null, TerminalKeyEncoder.withModifiers(emptySet(), "c"))
    }

    @Test
    fun `layout has no letter row`() {
        // Ctrl chords come from the soft keyboard with the latch armed.
        val flat = TerminalKeyEncoder.DEFAULT_LAYOUT.flatten()
        assertTrue(flat.none { it.label.length == 1 && it.label[0].isLetter() && it.bytes?.size == 1 })
    }

    @Test
    fun `withModifiers rejects meaningless ctrl combo`() {
        // CTRL+TAB has no control byte: null keeps the latch armed.
        assertEquals(
            null,
            TerminalKeyEncoder.withModifiers(setOf(TerminalKeyEncoder.Modifier.CTRL), "\t"),
        )
    }
}
