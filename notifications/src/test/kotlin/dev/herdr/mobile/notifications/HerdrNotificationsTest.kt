package dev.herdr.mobile.notifications

import dev.herdr.mobile.core.model.AgentStatus
import dev.herdr.mobile.core.model.SemanticEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HerdrNotificationsTest {

    private fun event(type: String, status: AgentStatus?) = SemanticEvent(
        seq = 1,
        type = type,
        workspaceId = "w1",
        tabId = "w1:t1",
        paneId = "w1:p1",
        status = status,
    )

    @Test
    fun doneNotifiesWhenEnabled() {
        assertTrue(
            HerdrNotifications.shouldNotify(
                event(SemanticEvent.TYPE_PANE_STATUS_CHANGED, AgentStatus.DONE),
                notifyDone = true,
                notifyBlocked = true,
            ),
        )
    }

    @Test
    fun doneSuppressedWhenDisabled() {
        assertFalse(
            HerdrNotifications.shouldNotify(
                event(SemanticEvent.TYPE_PANE_STATUS_CHANGED, AgentStatus.DONE),
                notifyDone = false,
                notifyBlocked = true,
            ),
        )
    }

    @Test
    fun blockedNotifiesWhenEnabled() {
        assertTrue(
            HerdrNotifications.shouldNotify(
                event(SemanticEvent.TYPE_PANE_STATUS_CHANGED, AgentStatus.BLOCKED),
                notifyDone = true,
                notifyBlocked = true,
            ),
        )
    }

    @Test
    fun workingAndIdleNeverNotify() {
        for (status in listOf(AgentStatus.WORKING, AgentStatus.IDLE, AgentStatus.UNKNOWN, null)) {
            assertFalse(
                HerdrNotifications.shouldNotify(
                    event(SemanticEvent.TYPE_PANE_STATUS_CHANGED, status),
                    notifyDone = true,
                    notifyBlocked = true,
                ),
            )
        }
    }

    @Test
    fun nonStatusEventsNeverNotify() {
        assertFalse(
            HerdrNotifications.shouldNotify(
                event(SemanticEvent.TYPE_TAB_CREATED, AgentStatus.DONE),
                notifyDone = true,
                notifyBlocked = true,
            ),
        )
    }

    @Test
    fun failedNeverNotifiesWithoutSignal() {
        // Herdr emits no failure status; a stray FAILED must not alert.
        assertFalse(
            HerdrNotifications.shouldNotify(
                event(SemanticEvent.TYPE_PANE_STATUS_CHANGED, AgentStatus.FAILED),
                notifyDone = true,
                notifyBlocked = true,
            ),
        )
    }
}
