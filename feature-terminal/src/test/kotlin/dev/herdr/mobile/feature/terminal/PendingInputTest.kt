package dev.herdr.mobile.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import dev.herdr.mobile.core.network.testing.FakeHerdrSession
import dev.herdr.mobile.core.network.testing.FakeTerminalConnection

class PendingInputTest {

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)

    private val results = mutableListOf<String>()

    private fun tag(value: String): suspend (ConnectionTerminalBackend) -> Unit =
        { _ -> results += value }

    private fun fakeBackend(): ConnectionTerminalBackend =
        ConnectionTerminalBackend(FakeTerminalConnection("w1:p1"), scope)

    @Test
    fun `drain preserves arrival order and empties the queue`() {
        val queue = PendingInput()
        queue.enqueue(tag("a"))
        queue.enqueue(tag("b"))
        queue.enqueue(tag("c"))

        val drained = queue.drain()
        assertEquals(3, drained.size)
        runBlocking { drained.forEach { it(fakeBackend()) } }
        assertEquals(listOf("a", "b", "c"), results)
        assertTrue(queue.drain().isEmpty())
    }

    @Test
    fun `the oldest entry is dropped past the bound`() {
        val queue = PendingInput(maxSize = 3)
        listOf("a", "b", "c", "d").forEach { queue.enqueue(tag(it)) }

        val drained = queue.drain()
        runBlocking { drained.forEach { it(fakeBackend()) } }
        assertEquals(listOf("b", "c", "d"), results)
    }

    @Test
    fun `clear drops everything`() {
        val queue = PendingInput()
        queue.enqueue(tag("a"))
        queue.clear()
        assertTrue(queue.drain().isEmpty())
    }
}
