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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    // Serializes concurrent open() (reconnect loop + kick + test): without it
    // two callers both see sessionRef==null, both dial, and the loser leaks a
    // connected session + local forward.
    private val openMutex = Mutex()

    override suspend fun open(): DaemonEndpoint = withContext(Dispatchers.IO) {
        val result: DaemonEndpoint = openMutex.withLock {
            val live = sessionRef.get()
            val port = localPortRef.get()
            if (live?.isConnected == true && port != null) {
                endpoint(port)
            } else {
                openLocked()
            }
        }
        result
    }

    private suspend fun openLocked(): DaemonEndpoint {
        closeLocked()
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
            val keyOk = runCatching {
                jsch.addIdentity("herdr-mobile-${profile.id}", key.toByteArray(), null, null)
            }.isSuccess
            if (!keyOk && secret != null) {
                // A corrupt stored key must never block password auth: skip the
                // key and continue with the password below.
                android.util.Log.w("SshTunnel", "stored key unusable, falling back to password auth")
            } else if (!keyOk) {
                throw SshException(
                    FailureKind.AUTH,
                    "Stored key is invalid; fix the key or enter a password",
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
        // Race-free port pick: hold a ServerSocket open to reserve the port,
        // read its number, then close it immediately before forwarding. The
        // window is microseconds (vs. close-then-rebind-later), and JSch's
        // setPortForwardingL needs a CLOSED port — forwarding onto a HELD
        // socket fails with "cannot be bound".
        val localPort = ServerSocket(0).use { it.localPort }
        try {
            session.setPortForwardingL(localPort, profile.daemonHost, profile.daemonPort)
        } catch (e: Exception) {
            runCatching { session.disconnect() }
            throw SshException(FailureKind.UNREACHABLE, "Port forward failed: ${e.message}", e)
        }
        sessionRef.set(session)
        localPortRef.set(localPort)
        return endpoint(localPort)
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
        openMutex.withLock { closeLocked() }
    }

    private fun closeLocked() {
        localPortRef.set(null)
        val session = sessionRef.getAndSet(null)
        runCatching { session?.disconnect() }
    }

    private fun classifyConnect(e: Exception): FailureKind {
        val message = (e.message ?: "") + " " + (e.cause?.message ?: "")
        return classifySshError(message)
    }
}

class SshException(val kind: FailureKind, message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Single classification truth, shared by production and tests: [classifyConnect]
 * flattens exception+cause into one string and delegates here, so the test hook
 * can never diverge from the real path again.
 */
fun classifySshError(message: String): FailureKind {
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

/**
 * Decision bridge for JSch host-key prompts. JSch calls [promptYesNo] on its own thread and
 * blocks for the answer, so the suspend [decide] is bridged with a short bounded wait. The
 * wait is capped at 30s; on timeout the key is rejected rather than silently accepted.
 */
private class AcceptHostKeyUserInfo(
    private val host: String,
    private val decide: suspend (host: String, fingerprint: String) -> Boolean,
) : com.jcraft.jsch.UserInfo {
    override fun promptYesNo(msg: String?): Boolean {
        val fingerprint = msg?.let { extractFingerprint(it) }
        // Per-call latch + local result: no shared @Volatile slot (concurrent
        // prompts cannot race), no busy sleep, no leaked scope — the scope is
        // cancelled in finally on every path.
        val latch = java.util.concurrent.CountDownLatch(1)
        val answerRef = java.util.concurrent.atomic.AtomicBoolean(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            try {
                answerRef.set(runCatching { decide(host, fingerprint ?: (msg ?: "")) }.getOrDefault(false))
            } finally {
                latch.countDown()
            }
        }
        try {
            // Bounded wait: never hang the SSH thread forever. Timeout rejects
            // rather than silently accepting.
            if (!latch.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
                return false
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        } finally {
            scope.cancel()
        }
        return answerRef.get()
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
