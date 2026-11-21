package dev.herdr.mobile.feature.home

import org.junit.Assert.assertEquals
import org.junit.Test

/** The header and failure body must show one short, truthful sentence. */
class ConnectionCopyTest {

    @Test
    fun `a transport error is reduced to the endpoint`() {
        assertEquals(
            "Can't reach 127.0.0.1:8766",
            connectionProblem("Connection refused: Failed to connect to /127.0.0.1:8766"),
        )
    }

    @Test
    fun `a missing profile is not rendered as a retryable host failure`() {
        assertEquals(true, isUnconfigured("No host profile configured"))
        assertEquals(false, isUnconfigured("Connection refused"))
        assertEquals("No host configured", connectionProblem("No host profile configured"))
        assertEquals(
            "Add a host profile in Settings, then come back.",
            connectionDetail("No host profile configured"),
        )
    }

    @Test
    fun `the failure body strips transport noise already in the header`() {
        assertEquals(
            "Connection refused.",
            connectionDetail(
                "Connection refused: Failed to connect to /127.0.0.1:8766",
            ),
        )
        assertEquals(
            "The daemon is not reachable.",
            connectionDetail(null),
        )
    }
}
