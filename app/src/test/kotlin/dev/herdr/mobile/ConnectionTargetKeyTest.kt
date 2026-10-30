package dev.herdr.mobile

import dev.herdr.mobile.core.model.AppSettings
import dev.herdr.mobile.core.model.BackendMode
import dev.herdr.mobile.core.model.HostProfile
import dev.herdr.mobile.core.model.TransportMode
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ConnectionTargetKeyTest {
    @Test
    fun profileTransportAndEndpointChangesUseDifferentCacheKeys() {
        // Bug class #3: cached sessions/events from one connection target must not survive a switch.
        val first = HostProfile(id = "first", label = "first", host = "one.test", username = "user")
        val second = HostProfile(id = "second", label = "second", host = "two.test", username = "user")
        val ssh = AppSettings(hostProfiles = listOf(first, second), activeProfileId = first.id)

        assertNotEquals(connectionTargetKey(ssh), connectionTargetKey(ssh.copy(activeProfileId = second.id)))
        assertNotEquals(connectionTargetKey(ssh), connectionTargetKey(ssh.copy(
            hostProfiles = listOf(first.copy(host = "changed.test"), second),
        )))
        assertNotEquals(connectionTargetKey(ssh), connectionTargetKey(ssh.copy(
            hostProfiles = listOf(first.copy(backendMode = BackendMode.CORE), second),
        )))

        val direct = ssh.copy(
            transportMode = TransportMode.DIRECT,
            directUrl = "https://one.test",
            directBackendMode = BackendMode.CORE,
        )
        assertNotEquals(connectionTargetKey(ssh), connectionTargetKey(direct))
        assertNotEquals(connectionTargetKey(direct), connectionTargetKey(direct.copy(directUrl = "https://two.test")))
    }
}
