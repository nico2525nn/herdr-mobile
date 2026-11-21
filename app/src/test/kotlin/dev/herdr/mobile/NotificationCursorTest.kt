package dev.herdr.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationCursorTest {

    @Test
    fun `older snapshots are rejected after a newer event`() {
        val cursor = NotificationCursor()
        assertTrue(cursor.accept(11, connectionEpoch = 1))
        assertFalse(cursor.accept(10, connectionEpoch = 1))
        assertTrue(cursor.accept(11, connectionEpoch = 1))
        assertTrue(cursor.accept(12, connectionEpoch = 1))
    }

    @Test
    fun `a redial restarts the cursor when seq drops to zero`() {
        val cursor = NotificationCursor()
        assertTrue(cursor.accept(42, connectionEpoch = 1))
        assertTrue(cursor.accept(0, connectionEpoch = 2))
        assertTrue(cursor.accept(1, connectionEpoch = 2))
        assertFalse(cursor.accept(0, connectionEpoch = 2))
    }

    @Test
    fun `target reset clears both the seq and link high-water marks`() {
        val cursor = NotificationCursor()
        assertTrue(cursor.accept(42, connectionEpoch = 1))
        cursor.reset()
        assertTrue(cursor.accept(0, connectionEpoch = 1))
    }
}
