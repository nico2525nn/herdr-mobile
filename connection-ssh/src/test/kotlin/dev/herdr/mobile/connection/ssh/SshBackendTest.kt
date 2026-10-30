package dev.herdr.mobile.connection.ssh

import dev.herdr.mobile.core.model.BackendMode
import dev.herdr.mobile.core.model.HostProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class SshBackendTest {
    @Test
    fun selectedBackendIsExposedByProfileProvider() {
        val provider = SshTunnelProvider(
            profile = HostProfile(id = "p1", label = "host", host = "example.test"),
            privateKeyPem = { null },
            password = { null },
            knownHosts = { null },
            onUnknownHostKey = { _, _ -> false },
            bearerToken = { null },
            backendMode = BackendMode.CORE,
        )

        assertEquals(BackendMode.CORE, provider.backendMode)
        assertEquals("p1", provider.id)
    }
}
