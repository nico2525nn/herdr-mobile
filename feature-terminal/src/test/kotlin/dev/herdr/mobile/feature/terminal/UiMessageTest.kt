package dev.herdr.mobile.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The status line contract, stated as rules:
 *
 * - every message renders to something a user can read;
 * - a failure says what failed *and* why, and never leaks a null;
 * - a notice that names a destination includes it.
 *
 * These are the properties the old inline strings could not promise.
 */
class UiMessageTest {

    private fun allMessages(): List<UiMessage> = buildList {
        add(UiMessage.TargetChanged)
        add(UiMessage.WorkspaceClosed("herdr-mobile"))
        add(UiMessage.SessionClosed)
        add(UiMessage.OpenedInstead(UiMessage.OpenedInstead.TargetKind.PANE, "w1:p1"))
        add(UiMessage.OpenedInstead(UiMessage.OpenedInstead.TargetKind.TAB, null))
        add(UiMessage.OpenedInstead(UiMessage.OpenedInstead.TargetKind.WORKSPACE, null))
        add(UiMessage.AttachFailed("no endpoint"))
        add(UiMessage.AttachFailed(null))
        add(UiMessage.ReconnectTimedOut)
        add(UiMessage.StreamChanged)
        add(UiMessage.SendFailed("socket closed"))
        add(UiMessage.SendFailed(null))
        for (command in UiMessage.CommandFailed.Command.entries) {
            add(UiMessage.CommandFailed(command, "denied"))
            add(UiMessage.CommandFailed(command, null))
        }
    }

    @Test
    fun `every message renders a readable line`() {
        for (message in allMessages()) {
            val text = message.text()
            assertTrue("empty render for $message", text.isNotBlank())
            assertFalse("render for $message leaks null: $text", text.contains("null"))
            assertTrue(
                "render for $message should be a sentence with a capital: $text",
                text.first().isUpperCase(),
            )
        }
    }

    @Test
    fun `failures name the failing action and the reason`() {
        assertEquals("Cannot create tab: denied", UiMessage.CommandFailed(
            UiMessage.CommandFailed.Command.CREATE_TAB, "denied",
        ).text())
        assertEquals("Send failed: socket closed", UiMessage.SendFailed("socket closed").text())
        assertEquals("Cannot attach: no endpoint", UiMessage.AttachFailed("no endpoint").text())
    }

    @Test
    fun `a missing reason degrades to a stable fallback instead of null`() {
        assertEquals("Send failed: unknown error", UiMessage.SendFailed(null).text())
        assertEquals("Send failed: unknown error", UiMessage.SendFailed("   ").text())
    }

    @Test
    fun `notices that name a destination include it`() {
        assertEquals(
            "That workspace was closed — opened herdr-mobile",
            UiMessage.WorkspaceClosed("herdr-mobile").text(),
        )
        assertTrue(
            UiMessage.OpenedInstead(UiMessage.OpenedInstead.TargetKind.PANE, "w1:p1")
                .text()
                .contains("w1:p1"),
        )
    }

    @Test
    fun `each command has its own wording`() {
        val rendered = UiMessage.CommandFailed.Command.entries.map {
            UiMessage.CommandFailed(it, "x").text()
        }
        assertEquals(
            "two commands share a sentence, so the user cannot tell them apart",
            rendered.size,
            rendered.toSet().size,
        )
    }
}
