package dev.herdr.mobile.core.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Wire contract for `WS /v1/terminal/{paneId}`.
 *
 * The socket carries exactly one kind of payload at a time, so raw terminal bytes never mix
 * with semantic state:
 *
 * - **Binary frames** are raw terminal bytes in both directions. Daemon to client they are the
 *   ANSI stream; client to daemon they are keystrokes. Nothing decodes or re-encodes them in
 *   transit.
 * - **Text frames** are JSON control records, declared below.
 */
object TerminalProtocol {

    const val PATH_PREFIX = "/v1/terminal/"

    /** Client to daemon: a literal string in UTF-8. */
    @Serializable
    data class InputText(
        val text: String,
        @EncodeDefault val type: String = TYPE,
    ) {
        companion object {
            const val TYPE = "input.text"
        }
    }

    /** Client to daemon: literal bytes, base64 encoded so JSON stays textual. */
    @Serializable
    data class InputBytes(
        val bytes: String,
        @EncodeDefault val type: String = TYPE,
    ) {
        companion object {
            const val TYPE = "input.bytes"
        }
    }

    @Serializable
    data class Resize(
        val cols: Int,
        val rows: Int,
        @EncodeDefault val type: String = TYPE,
    ) {
        init {
            require(cols > 0 && rows > 0) { "cols and rows must be greater than 0" }
        }

        companion object {
            const val TYPE = "resize"
        }
    }

    /**
     * Client to daemon: scroll the REMOTE (Herdr) viewport. Used ONLY for
     * alt-screen TUIs (no local history exists there); Herdr routes per
     * context (host scrollback / app arrows / mouse wheel report). Shells
     * scroll local prelude history with zero RTT instead.
     */
    @Serializable
    data class Scroll(
        val direction: String,
        val lines: Int,
        @EncodeDefault val type: String = TYPE,
    ) {
        init {
            require(lines > 0) { "lines must be greater than 0" }
        }

        companion object {
            const val TYPE = "scroll"
            const val UP = "up"
            const val DOWN = "down"
        }
    }

    @Serializable
    data class Mouse(
        val action: String,
        val button: String,
        val column: Int,
        val row: Int,
        @EncodeDefault val type: String = TYPE,
    ) {
        companion object {
            const val TYPE = "mouse"
        }
    }

    /** Daemon to client: the byte stream is live and [cols] x [rows] cells are in effect. */
    @Serializable
    data class Ready(
        @SerialName("paneId") val paneId: String,
        val cols: Int,
        val rows: Int,
        val encoding: String = "ansi",
        val resumed: Boolean = false,
        val historyRows: Int = 0,
        val historyTruncated: Boolean = false,
        val historyError: String? = null,
        val visibleOk: Boolean = true,
        @EncodeDefault val type: String = TYPE,
    ) {
        companion object {
            const val TYPE = "ready"
        }
    }

    @Serializable
    data class Closed(
        val reason: String,
        @EncodeDefault val type: String = TYPE,
    ) {
        companion object {
            const val TYPE = "closed"
        }
    }

    @Serializable
    data class Failure(
        val code: String,
        val message: String,
        @EncodeDefault val type: String = TYPE,
    ) {
        companion object {
            const val TYPE = "error"
        }
    }
}

/** Everything the terminal screen needs to describe its own link to the remote pane. */
sealed interface TerminalAttachmentState {
    data object Idle : TerminalAttachmentState

    data class Attaching(val paneId: String) : TerminalAttachmentState

    data class Attached(
        val paneId: String,
        val cols: Int,
        val rows: Int,
        /** True when the daemon replayed a full repaint after a reconnect. */
        val resumed: Boolean,
        /** Prelude history rows banked (0 + error = silent-loss visible). */
        val historyRows: Int = 0,
        val historyTruncated: Boolean = false,
        val historyError: String? = null,
        val visibleOk: Boolean = true,
    ) : TerminalAttachmentState

    data class Detached(val reason: String) : TerminalAttachmentState

    data class Failed(val code: String, val message: String) : TerminalAttachmentState
}

/** A `type`-tagged control record whose full shape is unknown to this client. */
@Serializable
data class RawTerminalRecord(
    val type: String? = null,
    val code: String? = null,
    val message: String? = null,
)

/** `{"type":"release"}` — client to daemon: close the child and the socket. */
@Serializable
data class TerminalRelease(
    @EncodeDefault val type: String = TYPE,
) {
    companion object {
        const val TYPE = "release"
    }
}

fun terminalError(code: String, message: String): JsonObject =
    buildJsonObject {
        put("type", TerminalProtocol.Failure.TYPE)
        put("code", code)
        put("message", message)
    }