package dev.herdr.mobile.feature.terminal

import dev.herdr.mobile.core.designsystem.UiMessage
import dev.herdr.mobile.core.model.ConnectionState
import dev.herdr.mobile.core.network.DaemonException
import dev.herdr.mobile.core.network.HerdrSession
import dev.herdr.mobile.core.network.TerminalConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeout

/**
 * Opens the pane stream for one target.
 *
 * The daemon can die between "snapshot says connected" and "open the socket",
 * and the tunnel can redial while the socket handshake is in flight. Both are
 * retried once against the NEW generation; a second failure surfaces as a
 * message instead of a spinner. Returns null when the attempt was abandoned
 * (generation changed, retries exhausted, or the caller was cancelled).
 */
internal suspend fun openTerminalConnection(
    session: HerdrSession,
    target: TerminalTarget,
    generation: Long,
    grid: Pair<Int, Int>,
    onMessage: (UiMessage?) -> Unit,
): TerminalConnection? {
    val (cols, rows) = grid
    var linkGeneration = session.linkGeneration
    var retryUsed = false
    var opened: TerminalConnection? = null

    while (opened == null) {
        val connection = try {
            session.openTerminal(target.paneId, cols = cols, rows = rows)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (session.targetGeneration != generation) return null
            val canRetry = !retryUsed && e is DaemonException && e.code == "not_connected"
            if (!canRetry) {
                onMessage(UiMessage.AttachFailed(e.message))
                return null
            }
            retryUsed = true
            session.kick()
            if (!awaitConnected(session)) {
                onMessage(UiMessage.ReconnectTimedOut)
                return null
            }
            currentCoroutineContext().ensureActive()
            if (session.targetGeneration != generation) return null
            linkGeneration = session.linkGeneration
            continue
        }
        if (session.targetGeneration != generation) {
            releaseQuietly(connection)
            return null
        }
        if (session.linkGeneration == linkGeneration) {
            opened = connection
            continue
        }
        // The daemon redialled while the socket was opening: release the
        // orphan before waiting for the new endpoint.
        releaseQuietly(connection)
        currentCoroutineContext().ensureActive()
        if (retryUsed) {
            onMessage(UiMessage.StreamChanged)
            return null
        }
        retryUsed = true
        if (!awaitConnected(session)) {
            onMessage(UiMessage.ReconnectTimedOut)
            return null
        }
        currentCoroutineContext().ensureActive()
        if (session.targetGeneration != generation) return null
        linkGeneration = session.linkGeneration
    }
    return opened
}

/** Waits until the session reports a live link, bounded by [timeoutMs]. */
internal suspend fun awaitConnected(session: HerdrSession, timeoutMs: Long = 30_000): Boolean = try {
    withTimeout(timeoutMs) {
        session.state.first { it.connection == ConnectionState.CONNECTED }
    }
    true
} catch (_: Exception) {
    false
}

/** Release a socket that never became the live backend. */
internal suspend fun releaseQuietly(connection: TerminalConnection) {
    try {
        withContext(NonCancellable) { connection.release() }
    } catch (_: Exception) {
    }
}
