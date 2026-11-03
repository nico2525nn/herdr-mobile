package dev.herdr.mobile.feature.terminal

import dev.herdr.mobile.core.model.SessionSnapshot
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The daemon's snapshot must round-trip the scroll state the router reads. */
class PaneScrollParseTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `pane scroll is parsed from the daemon payload`() {
        val payload = """
            {"revision":1,"seq":1,"workspaces":[{"id":"w1","label":"w1","focused":false,
            "activeTabId":"t1","tabs":[{"id":"t1","workspaceId":"w1","panes":[
            {"id":"p1","tabId":"t1","workspaceId":"w1","agent":"codex",
             "scroll":{"offsetFromBottom":3,"maxOffsetFromBottom":42,"viewportRows":52}}]}]}]}
        """.trimIndent()
        val snapshot = json.decodeFromString(SessionSnapshot.serializer(), payload)
        val pane = snapshot.pane("p1")
        assertEquals(3L, pane?.scroll?.offsetFromBottom)
        assertEquals(42L, pane?.scroll?.maxOffsetFromBottom)
        assertEquals(52L, pane?.scroll?.viewportRows)
        assertTrue(pane?.hostScrollable == true)
    }
}
