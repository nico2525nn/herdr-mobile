package dev.herdr.mobile.core.network

import dev.herdr.mobile.core.model.TerminalAttachmentState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure control parsing + burst merge: the wire semantics TerminalSocket relies on,
 * exercised without a socket.
 */
class TerminalControlTest {

    private val preludeJson =
        """
        {"type":"ready","paneId":"pane-7","cols":0,"rows":0,"resumed":true,
         "historyRows":900,"historyTruncated":true,
         "historyError":"truncated by daemon","visibleOk":false}
        """.trimIndent()

    private val geometryJson = """{"type":"ready","cols":132,"rows":43}"""

    @Test
    fun preludeThenGeometryCarriesHistoryAndUpdatesGeometry() {
        val prelude = parseReadyRecord(preludeJson, previous = null, fallbackPaneId = "fallback")
        assertTrue("prelude must parse", prelude != null)
        prelude!!
        assertEquals("pane-7", prelude.paneId)
        assertEquals(0, prelude.cols)
        assertEquals(0, prelude.rows)
        assertTrue(prelude.resumed)
        assertEquals(900, prelude.historyRows)
        assertTrue(prelude.historyTruncated)
        assertEquals("truncated by daemon", prelude.historyError)
        assertFalse(prelude.visibleOk)

        val transition = parseTerminalControl(
            text = geometryJson,
            previous = prelude.toAttachedState(),
            fallbackPaneId = "pane-7",
        )
        assertTrue("geometry must parse", transition != null)
        transition!!
        val geometry = transition.inbound as TerminalInbound.Ready
        assertEquals(132, geometry.cols)
        assertEquals(43, geometry.rows)
        assertEquals("paneId absent in geometry: falls back to the socket pane", "pane-7", geometry.paneId)
        assertFalse("resumed absent: defaults to false", geometry.resumed)
        assertEquals("historyRows carried", 900, geometry.historyRows)
        assertTrue("historyTruncated carried", geometry.historyTruncated)
        assertEquals("historyError carried", "truncated by daemon", geometry.historyError)
        assertFalse("visibleOk carried", geometry.visibleOk)

        val attached = transition.state as TerminalAttachmentState.Attached
        assertEquals(132, attached.cols)
        assertEquals(43, attached.rows)
        assertEquals(900, attached.historyRows)
        assertFalse(attached.visibleOk)
    }

    @Test
    fun visibleOkOnlyFalseWhenKeyPresent() {
        // Absent with no previous state: attached view is fine.
        val fresh = parseReadyRecord("""{"type":"ready","cols":80,"rows":24}""", null, "p")
        assertTrue(fresh != null)
        assertTrue(fresh!!.visibleOk)

        // Absent with a previous failure: carried forward.
        val unhealthy = preludeJson.let { parseReadyRecord(it, null, "p") }!!.toAttachedState()
        val carried = parseReadyRecord("""{"type":"ready","cols":80,"rows":24}""", unhealthy, "p")
        assertFalse(carried!!.visibleOk)

        // Present and true overrides the previous failure.
        val healthy = parseReadyRecord(
            """{"type":"ready","cols":80,"rows":24,"visibleOk":true}""",
            unhealthy,
            "p",
        )
        assertTrue(healthy!!.visibleOk)

        // Present non-"false" value is fine; only the literal false is not.
        val odd = parseReadyRecord(
            """{"type":"ready","cols":80,"rows":24,"visibleOk":"no"}""",
            null,
            "p",
        )
        assertTrue(odd!!.visibleOk)
    }

    @Test
    fun malformedReadyJsonReturnsNull() {
        assertNull(parseReadyRecord("not json", null, "p"))
        assertNull(parseReadyRecord("{", null, "p"))
        assertNull(parseReadyRecord("[]", null, "p"))
        assertNull(parseTerminalControl("not json", null, "p"))
    }

    @Test
    fun unknownTypeIsIgnored() {
        assertNull(parseTerminalControl("""{"type":"pong"}""", null, "p"))
    }

    @Test
    fun closedTransitionDetaches() {
        val withMessage = parseTerminalControl(
            """{"type":"closed","message":"pane exited"}""",
            previous = null,
            fallbackPaneId = "p",
        )
        assertTrue(withMessage != null)
        assertEquals(TerminalAttachmentState.Detached("pane exited"), withMessage!!.state)
        assertEquals(TerminalInbound.Closed("pane exited"), withMessage.inbound)

        val withReason = parseClosedRecord("""{"type":"closed","reason":"lagged_resync"}""")
        assertTrue(withReason != null)
        assertEquals(TerminalAttachmentState.Detached("lagged_resync"), withReason!!.state)
        assertEquals(TerminalInbound.Closed("lagged_resync"), withReason.inbound)

        val bare = parseClosedRecord("""{"type":"closed"}""")
        assertTrue(bare != null)
        assertEquals(TerminalAttachmentState.Detached("closed"), bare!!.state)
        assertEquals(TerminalInbound.Closed("closed"), bare.inbound)

        assertNull(parseClosedRecord("garbage"))
    }

    @Test
    fun failureTransitionFails() {
        val explicit = parseTerminalControl(
            """{"type":"error","code":"denied","message":"takeover refused"}""",
            previous = null,
            fallbackPaneId = "p",
        )
        assertTrue(explicit != null)
        assertEquals(
            TerminalAttachmentState.Failed("denied", "takeover refused"),
            explicit!!.state,
        )
        assertEquals(TerminalInbound.Failed("denied", "takeover refused"), explicit.inbound)

        val bare = parseFailureRecord("""{"type":"error"}""")
        assertTrue(bare != null)
        assertEquals(TerminalAttachmentState.Failed("unknown", "attach failed"), bare!!.state)
        assertEquals(TerminalInbound.Failed("unknown", "attach failed"), bare.inbound)

        assertNull(parseFailureRecord("garbage"))
    }

    @Test
    fun mergeBurstJoinsBytesAndFlushesBeforeControl() {
        val control = TerminalInbound.Closed("cut")
        val merged = mergeBurst(
            listOf(
                TerminalInbound.Bytes(byteArrayOf(1)),
                TerminalInbound.Bytes(byteArrayOf(2, 3)),
                control,
                TerminalInbound.Bytes(byteArrayOf(4, 5, 6)),
            ),
        )
        assertEquals(3, merged.size)
        assertBytes(byteArrayOf(1, 2, 3), merged[0])
        assertEquals(control, merged[1])
        assertBytes(byteArrayOf(4, 5, 6), merged[2])
    }

    @Test
    fun mergeBurstControlFirstThenBytes() {
        val control = TerminalInbound.Failed("boom", "nope")
        val merged = mergeBurst(
            listOf(
                control,
                TerminalInbound.Bytes(byteArrayOf(7)),
                TerminalInbound.Bytes(byteArrayOf(8, 9)),
            ),
        )
        assertEquals(2, merged.size)
        assertEquals(control, merged[0])
        assertBytes(byteArrayOf(7, 8, 9), merged[1])
    }

    @Test
    fun mergeBurstSingleBytes() {
        val merged = mergeBurst(listOf(TerminalInbound.Bytes(byteArrayOf(42))))
        assertEquals(1, merged.size)
        assertBytes(byteArrayOf(42), merged[0])
    }

    @Test
    fun mergeBurstControlOnly() {
        val control = TerminalInbound.Closed("bye")
        assertEquals(listOf(control), mergeBurst(listOf(control)))
    }

    @Test
    fun mergeBurstEmptyHasNothingToEmit() {
        assertTrue(mergeBurst(emptyList()).isEmpty())
    }

    private fun assertBytes(expected: ByteArray, actual: TerminalInbound) {
        assertTrue("expected Bytes, got $actual", actual is TerminalInbound.Bytes)
        assertArrayEquals(expected, (actual as TerminalInbound.Bytes).data)
    }
}
