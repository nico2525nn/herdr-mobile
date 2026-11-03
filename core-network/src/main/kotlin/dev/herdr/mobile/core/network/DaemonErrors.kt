package dev.herdr.mobile.core.network

import dev.herdr.mobile.core.model.FailureKind
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Maps a thrown transport/HTTP failure onto the typed [FailureKind] Settings renders.
 *
 * Walks the cause chain because OkHttp and the SSH layer both wrap the interesting error.
 */
internal fun classifyFailure(e: Throwable): FailureKind {
    var cause: Throwable? = e
    while (cause != null) {
        when (cause) {
            is UnknownHostException -> return FailureKind.DNS
            is SocketTimeoutException -> return FailureKind.UNREACHABLE
            is java.net.ConnectException ->
                return if ((cause.message ?: "").contains("refused", ignoreCase = true)) {
                    FailureKind.UNREACHABLE
                } else {
                    FailureKind.UNKNOWN
                }
            is DaemonException -> return when (cause.code) {
                "unauthorized" -> FailureKind.AUTH
                "host_key_mismatch" -> FailureKind.HOST_KEY_MISMATCH
                "daemon_unavailable" -> FailureKind.DAEMON_UNAVAILABLE
                "protocol_mismatch" -> FailureKind.PROTOCOL_MISMATCH
                else -> FailureKind.UNKNOWN
            }
        }
        if (cause.message?.contains("reject HostKey", ignoreCase = true) == true) {
            return FailureKind.HOST_KEY_UNKNOWN
        }
        if (cause.message?.contains("HostKey", ignoreCase = true) == true ||
            cause.message?.contains("host key", ignoreCase = true) == true
        ) {
            return FailureKind.HOST_KEY_MISMATCH
        }
        if (cause.message?.contains("Auth fail", ignoreCase = true) == true ||
            cause.message?.contains("authentication", ignoreCase = true) == true
        ) {
            return FailureKind.AUTH
        }
        cause = cause.cause
    }
    return FailureKind.UNKNOWN
}

/** User-facing line for a failure: typed daemon errors keep their own message. */
internal fun userMessage(e: Throwable): String {
    if (e is DaemonException) return e.message
    var cause: Throwable? = e
    while (cause != null) {
        when (cause) {
            is UnknownHostException -> return "Cannot resolve ${cause.message}"
            is SocketTimeoutException -> return "Timed out reaching the daemon"
            is java.net.ConnectException -> return "Connection refused: ${cause.message}"
        }
        cause = cause.cause
    }
    return e.message ?: e.javaClass.simpleName
}
