package dev.herdr.mobile.core.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class OutboundMergeTest {

    private fun bytes(vararg values: Int) = OutboundFrame.Binary(ByteArray(values.size) { values[it].toByte() })
    private fun text(value: String) = OutboundFrame.TextInput(value)
    private fun control(value: String) = OutboundFrame.Text(value)

    @Test
    fun `consecutive keystroke bytes become one frame`() {
        val merged = mergeOutbound(listOf(bytes(1), bytes(2), bytes(3)))
        assertEquals(1, merged.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), (merged.single() as OutboundFrame.Binary).data)
    }

    @Test
    fun `typed text accumulates into one input record`() {
        val merged = mergeOutbound(listOf(text("h"), text("e"), text("y")))
        assertEquals(listOf(OutboundFrame.TextInput("hey")), merged)
    }

    @Test
    fun `a kind change flushes in order`() {
        val merged = mergeOutbound(listOf(text("a"), bytes(0x0D), text("b")))
        assertEquals(3, merged.size)
        assertEquals(OutboundFrame.TextInput("a"), merged[0])
        assertArrayEquals(byteArrayOf(0x0D), (merged[1] as OutboundFrame.Binary).data)
        assertEquals(OutboundFrame.TextInput("b"), merged[2])
    }

    @Test
    fun `control records never merge with input and keep their order`() {
        val resize = control("""{"type":"resize"}""")
        val scroll = control("""{"type":"scroll"}""")
        val merged = mergeOutbound(listOf(bytes(9), resize, scroll, text("x"), text("y")))
        assertEquals(4, merged.size)
        assertEquals(resize, merged[1])
        assertEquals(scroll, merged[2])
        assertEquals(OutboundFrame.TextInput("xy"), merged[3])
    }

    @Test
    fun `an empty turn stays empty`() {
        assertEquals(emptyList<OutboundFrame>(), mergeOutbound(emptyList()))
    }
}
