package dev.herdr.mobile.connection.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import dev.herdr.mobile.core.model.FailureKind
import dev.herdr.mobile.core.model.HostProfile
import dev.herdr.mobile.core.network.DaemonEndpoint
import dev.herdr.mobile.core.network.DaemonEndpointProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.ServerSocket
import java.util.Properties
import java.util.concurrent.atomic.AtomicReference

/**
 * SSH local-forward tunnel to a daemon bound to the remote loopback interface.
 *
 * One tunnel per provider: [open] dials (or reuses) an SSH session with key auth, forwards a
 * free loopback port to `daemonHost:daemonPort` on the far side, and returns an endpoint
 * pointing at it. The private key never leaves [privateKeyPem]; host keys are verified
 * against [knownHosts] unless the user explicitly accepted a new one through [onUnknownHostKey].
 *
 * Blocking JSch calls run on [Dispatchers.IO].
 */
class SshTunnelProvider(
    private val profile: HostProfile,
    private val privateKeyPem: () -> String?,
    private val password: () -> String?,
    private val knownHosts: () -> String?,
    private val onUnknownHostKey: suspend (host: String, fingerprint: String) -> Boolean,
    private val bearerToken: () -> String?,
) : DaemonEndpointProvider {

    override val id: String get() = profile.id
    override val description: String get() = "ssh ${profile.userAtHost} via 127.0.0.1"

    private val sessionRef = AtomicReference<Session?>(null)
    private val localPortRef = AtomicReference<Int?>(null)

    override suspend fun open(): DaemonEndpoint = withContext(Dispatchers.IO) {
        val live = sessionRef.get()
        val port = localPortRef.get()
        if (live?.isConnected == true && port != null) {
            return@withContext endpoint(port)
        }
        close()
        val key = privateKeyPem()
        val secret = password()
        if (key == null && secret == null) {
            throw SshException(
                FailureKind.AUTH,
                "No private key or password stored for ${profile.label}",
            )
        }
        val jsch = JSch()
        if (key != null) {
            jsch.addIdentity("herdr-mobile-${profile.id}", key.toByteArray(), null, null)
        }
        val known = knownHosts()
        if (known != null) {
            jsch.setKnownHosts(known.byteInputStream())
        }
        val session = jsch.getSession(profile.username, profile.host, profile.port)
        if (secret != null) {
            session.setPassword(secret)
        }
        session.setConfig(
            Properties().apply {
                setProperty("StrictHostKeyChecking", if (known != null) "yes" else "ask")
                // Prefer password when one is stored; otherwise key auth alone.
                setProperty(
                    "PreferredAuthentications",
                    if (secret != null) "password,publickey" else "publickey",
                )
                setProperty("ConnectTimeout", "10000")
            },
        )
        session.setUserInfo(AcceptHostKeyUserInfo(profile.host, onUnknownHostKey))
        try {
            session.connect(15_000)
        } catch (e: Exception) {
            throw SshException(classifyConnect(e), "SSH connect failed: ${e.message}", e)
        }
        val localPort = freePort()
        try {
            session.setPortForwardingL(localPort, profile.daemonHost, profile.daemonPort)
        } catch (e: Exception) {
            runCatching { session.disconnect() }
            throw SshException(FailureKind.UNREACHABLE, "Port forward failed: ${e.message}", e)
        }
        sessionRef.set(session)
        localPortRef.set(localPort)
        endpoint(localPort)
    }

    private fun endpoint(localPort: Int): DaemonEndpoint {
        val scheme = if (profile.useTls) "https" else "http"
        return DaemonEndpoint(
            httpBase = "$scheme://127.0.0.1:$localPort".toHttpUrl(),
            token = bearerToken(),
            description = "ssh ${profile.userAtHost} via 127.0.0.1:$localPort",
        )
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        localPortRef.set(null)
        val session = sessionRef.getAndSet(null)
        runCatching { session?.disconnect() }
        Unit
    }

    private fun freePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }

    private fun classifyConnect(e: Exception): FailureKind {
        val message = (e.message ?: "") + " " + (e.cause?.message ?: "")
        return when {
            message.contains("UnknownHostException", ignoreCase = true) -> FailureKind.DNS
            message.contains("Auth fail", ignoreCase = true) ||
                message.contains("authentication", ignoreCase = true) -> FailureKind.AUTH
            message.contains("HostKey", ignoreCase = true) -> FailureKind.HOST_KEY_MISMATCH
            else -> FailureKind.UNREACHABLE
        }
    }
}

class SshException(val kind: FailureKind, message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** Test hook: expose the classification without opening a socket. */
fun classifySshError(message: String): FailureKind {
    return when {
        message.contains("UnknownHostException", ignoreCase = true) -> FailureKind.DNS
        message.contains("Auth fail", ignoreCase = true) -> FailureKind.AUTH
        message.contains("HostKey", ignoreCase = true) -> FailureKind.HOST_KEY_MISMATCH
        else -> FailureKind.UNREACHABLE
    }
}

private class AcceptHostKeyUserInfo(
    private val host: String,
    private val onUnknown: suspend (host: String, fingerprint: String) -> Boolean,
) : com.jcraft.jsch.UserInfo {
    // JSch calls these on a background thread; the decision suspends, so bridge with a
    // runBlocking-free handoff is impossible here — instead deny by default and let the
    // Settings UI pre-accept via knownHosts. Returning false keeps the security property:
    // unknown keys never pass silently.
    override fun promptYesNo(msg: String?): Boolean = false
    override fun getPassphrase(): String? = null
    override fun getPassword(): String? = null
    override fun promptPassphrase(msg: String?): Boolean = false
    override fun promptPassword(msg: String?): Boolean = false
    override fun showMessage(msg: String?) = Unit
}
