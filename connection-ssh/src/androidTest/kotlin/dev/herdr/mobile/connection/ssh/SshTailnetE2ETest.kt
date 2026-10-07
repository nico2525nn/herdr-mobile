package dev.herdr.mobile.connection.ssh

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.herdr.mobile.core.model.HostProfile
import dev.herdr.mobile.core.model.UnknownHostKeyException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Live tailnet E2E: SSH tunnel (key, then password) to the host daemon through Tailscale.
 * Requires the test machine on the same tailnet with sshd + daemon running.
 * Key material is injected via instrumentation args, never committed.
 *
 *   ./gradlew :connection-ssh:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.host=100.64.34.116 \
 *     -Pandroid.testInstrumentationRunnerArguments.user=nico \
 *     -Pandroid.testInstrumentationRunnerArguments.keyFile=/path/to/key \
 *     -Pandroid.testInstrumentationRunnerArguments.password=... \
 *     -Pandroid.testInstrumentationRunnerArguments.daemonPort=8765
 */
@RunWith(AndroidJUnit4::class)
class SshTailnetE2ETest {

    private fun arg(name: String): String? =
        androidx.test.platform.app.InstrumentationRegistry.getArguments().getString(name)

    @Test
    fun keyAuthTunnelReachesDaemon() = runBlocking {
        val host = arg("host") ?: error("missing host arg")
        val user = arg("user") ?: error("missing user arg")
        val keyFile = arg("keyFile") ?: error("missing keyFile arg")
        val daemonPort = arg("daemonPort")?.toIntOrNull() ?: 8765
        val pem = File(keyFile).readText()
        val profile = HostProfile(
            id = "e2e-key",
            label = "e2e-key",
            host = host,
            username = user,
            daemonPort = daemonPort,
        )
        val provider = SshTunnelProvider(
            profile = profile,
            privateKeyPem = { pem },
            password = { null },
            knownHosts = { null },
            onUnknownHostKey = { _, _ -> true },
            bearerToken = { null },
        )
        try {
            val endpoint = provider.open()
            assertTrue(endpoint.httpBase.toString().contains("127.0.0.1"))
            // Reach the daemon through the tunnel.
            val url = java.net.URL(endpoint.httpBase.toString().trimEnd('/') + "/v1/health")
            val body = (url.openConnection() as java.net.HttpURLConnection).run {
                connectTimeout = 10_000
                readTimeout = 10_000
                inputStream.bufferedReader().readText()
            }
            assertTrue(body.contains("\"ok\":true"))
        } finally {
            provider.close()
        }
    }

    @Test
    fun passwordAuthTunnelReachesDaemon() = runBlocking {
        val host = arg("host") ?: error("missing host arg")
        val user = arg("user") ?: error("missing user arg")
        // A dummy placeholder means "no real password supplied": the environment cannot
        // test password auth, so skip instead of failing against sshd.
        val password = arg("password") ?: error("missing password arg")
        org.junit.Assume.assumeTrue(
            "supply a real account password via -Pandroid.testInstrumentationRunnerArguments.password=...",
            password != "dummy" && password != "__NEEDED_FROM_USER__",
        )
        val daemonPort = arg("daemonPort")?.toIntOrNull() ?: 8765
        val profile = HostProfile(
            id = "e2e-pass",
            label = "e2e-pass",
            host = host,
            username = user,
            daemonPort = daemonPort,
        )
        val provider = SshTunnelProvider(
            profile = profile,
            privateKeyPem = { null },
            password = { password },
            knownHosts = { null },
            onUnknownHostKey = { _, _ -> true },
            bearerToken = { null },
        )
        try {
            val endpoint = provider.open()
            val url = java.net.URL(endpoint.httpBase.toString().trimEnd('/') + "/v1/health")
            val body = (url.openConnection() as java.net.HttpURLConnection).run {
                connectTimeout = 10_000
                readTimeout = 10_000
                inputStream.bufferedReader().readText()
            }
            assertTrue(body.contains("\"ok\":true"))
        } finally {
            provider.close()
        }
    }

    /**
     * First-use path with no stored host key and no auto-accept: must throw
     * [UnknownHostKeyException] carrying a fingerprint and a `host type base64`
     * known_hosts line — the payload the TOFU dialog persists on approval.
     * This is the exact failure a fresh phone install hits on Test/Connect.
     */
    @Test
    fun unknownHostKeyThrowsTofuPayload() = runBlocking {
        val host = arg("host") ?: error("missing host arg")
        val user = arg("user") ?: error("missing user arg")
        val profile = HostProfile(
            id = "e2e-tofu",
            label = "e2e-tofu",
            host = host,
            username = user,
            daemonPort = arg("daemonPort")?.toIntOrNull() ?: 8765,
        )
        val provider = SshTunnelProvider(
            profile = profile,
            privateKeyPem = { null },
            password = { "wrong-password-for-tofu-probe" },
            knownHosts = { null },
            onUnknownHostKey = { _, _ -> false },
            bearerToken = { null },
        )
        try {
            provider.open()
            error("expected UnknownHostKeyException")
        } catch (e: UnknownHostKeyException) {
            assertTrue(e.fingerprint.isNotBlank())
            val parts = e.knownHostsLine.split(" ")
            org.junit.Assert.assertEquals(3, parts.size)
            org.junit.Assert.assertEquals(host, parts[0])
            assertTrue(parts[2].isNotBlank())
        } finally {
            provider.close()
        }
    }
}
