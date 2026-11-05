package dev.herdr.mobile.terminal.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ScrollProbeTest {

    private fun screen(vararg lines: String) = lines.toList()

    @Test
    fun `an unchanged screen did not move`() {
        val lines = screen("a", "b", "c", "d")
        assertEquals(0, movedRows(lines, lines))
    }

    @Test
    fun `a shifted transcript reports the shift`() {
        val before = screen("one", "two", "three", "four", "five", "six")
        val after = screen("zero", "one", "two", "three", "four", "five", "six")
        assertEquals(1, movedRows(before, after))
    }

    @Test
    fun `a multi-row shift is measured exactly`() {
        val before = (1..12).map { "line$it" }
        val after = (1..5).map { "" } + before
        assertEquals(5, movedRows(before, after))
    }

    @Test
    fun `live repaint noise does not hide a real scroll`() {
        val before = (1..20).map { "line$it" }
        // Row 0 is a spinner: every frame differs. The rest moved by two.
        val after = listOf("spinner-42") + before.take(1) + before
        assertEquals(2, movedRows(before, after))
    }

    @Test
    fun `a pure repaint is not a scroll`() {
        val before = (1..20).map { "line$it" }
        val after = before.mapIndexed { i, line -> if (i % 2 == 0) "line$i!" else line }
        assertEquals(0, movedRows(before, after))
    }

    @Test
    fun `fewer matches than a third of the anchors is not a scroll`() {
        val before = (1..30).map { "line$it" }
        val after = (1..30).map { "other$it" } + listOf("line30", "line29")
        assertEquals(0, movedRows(before, after))
    }

    @Test
    fun `empty or blank screens never report movement`() {
        assertEquals(0, movedRows(emptyList(), screen("a")))
        assertEquals(0, movedRows(screen("a"), emptyList()))
        assertEquals(0, movedRows(screen("", " "), screen("x", "y")))
    }

    @Test
    fun `the estimate is capped`() {
        val before = (1..10).map { "line$it" }
        val after = (1..5).map { "" } + before
        assertEquals(5, movedRows(before, after))
        assertEquals(0, movedRows(before, after, maxRows = 3))
    }

    @Test
    fun `signature is stable and order sensitive`() {
        val a = screen("prompt", "output")
        assertEquals(screenSignature(a), screenSignature(a.toList()))
        assertNotEquals(screenSignature(a), screenSignature(screen("output", "prompt")))
        assertNotEquals(screenSignature(a), screenSignature(screen("prompt", "output ")))
    }
}
