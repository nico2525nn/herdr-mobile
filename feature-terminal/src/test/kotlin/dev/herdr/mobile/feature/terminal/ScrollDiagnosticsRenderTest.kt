package dev.herdr.mobile.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollDiagnosticsRenderTest {

    @Test
    fun `an unattached pane renders the unknown prelude marker`() {
        assertEquals(
            "hist=-1 alt=false mse=0 top=0 ev=0 rows=0 rem=0 pre=?",
            renderScrollDiagnostics(ScrollDiagnosticsSnapshot()),
        )
    }

    @Test
    fun `remote routing and history are reported`() {
        val text = renderScrollDiagnostics(
            ScrollDiagnosticsSnapshot(
                historyRows = 120,
                altActive = true,
                mouseTracking = true,
                topRow = -40,
                scrollEvents = 7,
                scrolledRows = 21,
                remoteScrolls = 5,
                remoteEffective = true,
                attachedHistoryRows = 900,
                attachedHistoryTruncated = true,
            ),
        )
        assertEquals(
            "hist=120 alt=true mse=1 top=-40 ev=7 rows=21 rem=5(R) pre=900+",
            text,
        )
    }

    @Test
    fun `history errors are shortened to 24 characters`() {
        val error = "x".repeat(40)
        val text = renderScrollDiagnostics(
            ScrollDiagnosticsSnapshot(attachedHistoryRows = 10, attachedHistoryError = error),
        )
        assertTrue(text.endsWith(" ERR:" + "x".repeat(24)))
    }
}
