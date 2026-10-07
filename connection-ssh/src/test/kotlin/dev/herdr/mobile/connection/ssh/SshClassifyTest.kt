package dev.herdr.mobile.connection.ssh

import dev.herdr.mobile.core.model.FailureKind
import org.junit.Assert.assertEquals
import org.junit.Test

class SshClassifyTest {

    @Test
    fun `dns failures classify as DNS`() {
        assertEquals(
            FailureKind.DNS,
            classifySshError("java.net.UnknownHostException: example.invalid"),
        )
    }

    @Test
    fun `auth failures classify as AUTH`() {
        assertEquals(FailureKind.AUTH, classifySshError("Auth fail for methods 'publickey'"))
    }

    @Test
    fun `host key problems classify as HOST_KEY_MISMATCH`() {
        assertEquals(
            FailureKind.HOST_KEY_MISMATCH,
            classifySshError("HostKey has been changed"),
        )
    }

    @Test
    fun `first-use reject classifies as HOST_KEY_UNKNOWN`() {
        assertEquals(
            FailureKind.HOST_KEY_UNKNOWN,
            classifySshError("reject HostKey: 100.64.34.116"),
        )
    }

    @Test
    fun `anything else is UNREACHABLE`() {
        assertEquals(FailureKind.UNREACHABLE, classifySshError("Connection timed out"))
    }
}
