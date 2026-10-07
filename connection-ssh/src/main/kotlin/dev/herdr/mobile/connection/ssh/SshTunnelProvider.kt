package dev.herdr.mobile.connection.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import dev.herdr.mobile.core.model.FailureKind
import dev.herdr.mobile.core.model.HostProfile
import dev.herdr.mobile.core.model.UnknownHostKeyException
import dev.herdr.mobile.core.network.DaemonEndpoint
import dev.herdr.mobile.core.network.DaemonEndpointProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
 * JSch's default Ed25519 signer (`jce.SignatureEd25519`) needs Java 15+ JCE EdDSA, which
 * Android only ships from API 35. The bundled BouncyCastle-backed signer is registered
 * instead so Ed25519 keys work on every supported API level.
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
                "No password stored for ${profile.label}; open the profile and enter one",
            )
        }
        val jsch = JSch()
        // Prefer the BouncyCastle-backed EdDSA signers: the JCE ones need API 35+.
        // Static config so every JSch session in this process benefits.
        JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
        JSch.setConfig("ssh-ed448", "com.jcraft.jsch.bc.SignatureEd448")
        if (key != null) {
            runCatching {
                jsch.addIdentity("herdr-mobile-${profile.id}", key.toByteArray(), null, null)
            }.onFailure {
                // A corrupt stored key must never block password auth.
                throw SshException(
                    FailureKind.AUTH,
                    "Stored key is invalid (${it.message}); re-enter the password or fix the key",
                    it,
                )
            }
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
            // First-use path: session.hostKey holds what the server presented even
            // though the connect failed. Surface it for TOFU instead of a dead-end
            // "reject HostKey" message the user cannot act on.
            if (known == null) {
                runCatching { session.hostKey }.getOrNull()?.let { hk ->
                    val type = hk.type
                    val key = hk.key
                    if (type.isNotBlank() && key.isNotBlank()) {
                        throw UnknownHostKeyException(
                            profileId = profile.id,
                            host = profile.host,
                            fingerprint = runCatching { hk.getFingerPrint(jsch) }.getOrNull() ?: type,
                            knownHostsLine = "${profile.host} $type $key",
                        )
                    }
                }
            }
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
            // JSch "reject HostKey" = no stored key to check against (first use);
            // anything else mentioning HostKey = stored key disagrees (possible attack).
            message.contains("reject HostKey", ignoreCase = true) -> FailureKind.HOST_KEY_UNKNOWN
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
        message.contains("reject HostKey", ignoreCase = true) -> FailureKind.HOST_KEY_UNKNOWN
        message.contains("HostKey", ignoreCase = true) -> FailureKind.HOST_KEY_MISMATCH
        else -> FailureKind.UNREACHABLE
    }
}

/**
 * Decision bridge for JSch host-key prompts. JSch calls [promptYesNo] on its own thread and
 * blocks for the answer, so the suspend [decide] is bridged with a short bounded wait. The
 * wait is capped at 30s; on timeout the key is rejected rather than silently accepted.
 */
private class AcceptHostKeyUserInfo(
    private val host: String,
    private val decide: suspend (host: String, fingerprint: String) -> Boolean,
) : com.jcraft.jsch.UserInfo {
    @Volatile
    private var answer: Boolean? = null

    override fun promptYesNo(msg: String?): Boolean {
        val fingerprint = msg?.let { extractFingerprint(it) }
        // Fast path for tests and pre-accepted flows that answer immediately.
        val scope = CoroutineScope(Dispatchers.Default)
        val job = scope.launch {
            answer = runCatching { decide(host, fingerprint ?: (msg ?: "")) }.getOrDefault(false)
        }
        // Bounded wait: never hang the SSH thread forever.
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (job.isCompleted) break
            Thread.sleep(50)
        }
        if (!job.isCompleted) {
            job.cancel()
            return false
        }
        return answer == true
    }

    override fun getPassphrase(): String? = null
    override fun getPassword(): String? = null
    override fun promptPassphrase(msg: String?): Boolean = false
    override fun promptPassword(msg: String?): Boolean = false
    override fun showMessage(msg: String?) = Unit
}

private fun extractFingerprint(msg: String): String {
    // JSch messages embed the key fingerprint; pass the raw text through when no
    // fingerprint pattern is found so the UI can still show something meaningful.
    val hex = Regex("([0-9a-fA-F]{2}:){7,}[0-9a-fA-F]{2}").find(msg)?.value
    if (hex != null) return hex
    val base64 = Regex("SHA256:[A-Za-z0-9+/=]+").find(msg)?.value
    return base64 ?: msg.take(200)
}
