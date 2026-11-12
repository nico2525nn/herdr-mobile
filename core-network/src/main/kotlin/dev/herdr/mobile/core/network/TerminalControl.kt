package dev.herdr.mobile.core.network

import dev.herdr.mobile.core.model.RawTerminalRecord
import dev.herdr.mobile.core.model.TerminalAttachmentState
import dev.herdr.mobile.core.model.TerminalProtocol
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure control-record handling for [TerminalSocket]: JSON text plus the previous
 * attachment state in, the resulting state transition out. No socket, no scheduling.
 */
internal data class TerminalControlTransition(
    val state: TerminalAttachmentState,
    val inbound: TerminalInbound,
)

/**
 * Parses one text frame into the transition it causes, or null when the frame is
 * malformed or is a record this client does not know (stay attached, as before).
 */
internal fun parseTerminalControl(
    text: String,
    previous: TerminalAttachmentState.Attached?,
    fallbackPaneId: String,
): TerminalControlTransition? {
    val record = try {
        HerdrJson.decodeFromString(RawTerminalRecord.serializer(), text)
    } catch (_: Exception) {
        return null
    }
    return when (record.type) {
        TerminalProtocol.Ready.TYPE ->
            parseReadyRecord(text, previous, fallbackPaneId)
                ?.let { TerminalControlTransition(it.toAttachedState(), it) }
        TerminalProtocol.Closed.TYPE -> parseClosedRecord(text)
        TerminalProtocol.Failure.TYPE -> parseFailureRecord(text)
        // Unknown control record from a newer daemon: ignore, stay attached.
        else -> null
    }
}

/**
 * The daemon sends Ready twice: prelude Ready (history info, cols=0) then geometry
 * Ready (cols/rows, no history keys). Absent keys must NOT clobber: carry previous.
 */
internal fun parseReadyRecord(
    text: String,
    previous: TerminalAttachmentState.Attached?,
    fallbackPaneId: String,
): TerminalInbound.Ready? {
    val obj = try {
        HerdrJson.parseToJsonElement(text).jsonObject
    } catch (_: Exception) {
        return null
    }
    // `jsonPrimitive` throws when the wire value is an object/array. A
    // malformed field must degrade to its default, not kill the attach.
    fun primitive(key: String) = obj[key] as? kotlinx.serialization.json.JsonPrimitive
    fun int(key: String): Int = primitive(key)?.content?.toIntOrNull() ?: 0
    fun bool(key: String): Boolean = primitive(key)?.content == "true"
    fun optStr(key: String): String? = primitive(key)?.contentOrNull
    val hasHistory = obj.containsKey("historyRows")
    return TerminalInbound.Ready(
        paneId = primitive("paneId")?.content ?: fallbackPaneId,
        cols = int("cols"),
        rows = int("rows"),
        resumed = bool("resumed"),
        historyRows = if (hasHistory) int("historyRows") else previous?.historyRows ?: 0,
        historyTruncated = if (hasHistory) {
            bool("historyTruncated")
        } else {
            previous?.historyTruncated ?: false
        },
        historyError = if (obj.containsKey("historyError")) {
            optStr("historyError")
        } else {
            previous?.historyError
        },
        visibleOk = if (obj.containsKey("visibleOk")) {
            primitive("visibleOk")?.content != "false"
        } else {
            previous?.visibleOk ?: true
        },
    )
}

/** `{"type":"closed"}` → Detached, or null when the frame is malformed. */
internal fun parseClosedRecord(text: String): TerminalControlTransition? {
    val record = decodeControlRecord(text) ?: return null
    val reason = record.reason ?: record.message ?: "closed"
    return TerminalControlTransition(
        state = TerminalAttachmentState.Detached(reason),
        inbound = TerminalInbound.Closed(reason),
    )
}

/** `{"type":"error"}` → Failed, or null when the frame is malformed. */
internal fun parseFailureRecord(text: String): TerminalControlTransition? {
    val record = decodeControlRecord(text) ?: return null
    val code = record.code ?: "unknown"
    val message = record.message ?: "attach failed"
    return TerminalControlTransition(
        state = TerminalAttachmentState.Failed(code, message),
        inbound = TerminalInbound.Failed(code, message),
    )
}

private fun decodeControlRecord(text: String): RawTerminalRecord? =
    try {
        HerdrJson.decodeFromString(RawTerminalRecord.serializer(), text)
    } catch (_: Exception) {
        null
    }

internal fun TerminalInbound.Ready.toAttachedState(): TerminalAttachmentState.Attached =
    TerminalAttachmentState.Attached(
        paneId = paneId,
        cols = cols,
        rows = rows,
        resumed = resumed,
        historyRows = historyRows,
        historyTruncated = historyTruncated,
        historyError = historyError,
        visibleOk = visibleOk,
    )

/**
 * Burst-merge for one lane turn: live output + scroll repaints arrive as many small
 * binary frames, and forwarding each separately redraws the view N times per frame
 * (the judder under OMP output). Consecutive [TerminalInbound.Bytes] concatenate in
 * arrival order — lossless — while any control record flushes pending bytes first so
 * attach/teardown never lag; trailing bytes are flushed at the end.
 */
internal fun mergeBurst(values: List<TerminalInbound>): List<TerminalInbound> {
    val out = ArrayList<TerminalInbound>(values.size)
    val merge = ArrayDeque<ByteArray>()
    var mergedSize = 0
    fun flush() {
        if (merge.isEmpty()) return
        val bytes = ByteArray(mergedSize)
        var pos = 0
        for (chunk in merge) {
            chunk.copyInto(bytes, pos)
            pos += chunk.size
        }
        merge.clear()
        mergedSize = 0
        out += TerminalInbound.Bytes(bytes)
    }
    for (value in values) {
        if (value is TerminalInbound.Bytes) {
            merge.addLast(value.data)
            mergedSize += value.data.size
        } else {
            flush()
            out += value
        }
    }
    flush()
    return out
}
