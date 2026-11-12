package dev.herdr.mobile

import dev.herdr.mobile.core.model.AgentStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRelayRetractTest {

    @Test
    fun `disabling a category or monitoring retracts only that alert`() {
        assertTrue(shouldRetractAlert(AgentStatus.DONE, monitoring = false, notifyDone = true, notifyBlocked = true))
        assertTrue(shouldRetractAlert(AgentStatus.DONE, monitoring = true, notifyDone = false, notifyBlocked = true))
        assertFalse(shouldRetractAlert(AgentStatus.DONE, monitoring = true, notifyDone = true, notifyBlocked = false))
        assertTrue(shouldRetractAlert(AgentStatus.BLOCKED, monitoring = true, notifyDone = true, notifyBlocked = false))
        assertFalse(shouldRetractAlert(AgentStatus.WORKING, monitoring = true, notifyDone = false, notifyBlocked = false))
    }
}
