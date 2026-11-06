package dev.herdr.mobile.core.designsystem

/**
 * A user-facing status line, as data rather than a finished sentence.
 *
 * The view-model used to build English strings inline in twenty places: the
 * screen could not restyle them, tests could only assert on prose, and every
 * new failure mode invented its own wording. A message now carries its
 * category and parameters; [text] is the single renderer, so wording changes
 * happen once and the rules ("a failure names what failed") are testable.
 */
sealed interface UiMessage {

    /** The daemon target (host/pane routing) changed under the screen. */
    data object TargetChanged : UiMessage

    /** The open pane's workspace was closed elsewhere; [opened] is the survivor. */
    data class WorkspaceClosed(val opened: String) : UiMessage

    /** The session drained: nothing is left to attach to. */
    data object SessionClosed : UiMessage

    /** A deep link pointed at something gone; [opened] is what was opened instead. */
    data class OpenedInstead(val kind: TargetKind, val opened: String?) : UiMessage {
        enum class TargetKind { PANE, TAB, WORKSPACE }
    }

    /** The terminal stream could not be opened. */
    data class AttachFailed(val detail: String?) : UiMessage

    /** Reconnecting took longer than the screen is willing to wait. */
    data object ReconnectTimedOut : UiMessage

    /** The link redialled while the stream was opening. */
    data object StreamChanged : UiMessage

    /** Bytes could not be written to the pane. */
    data class SendFailed(val detail: String?) : UiMessage

    /** A user command was rejected by the daemon. */
    data class CommandFailed(val command: Command, val detail: String?) : UiMessage {
        enum class Command {
            CREATE_TAB,
            CLOSE_TAB,
            RENAME_TAB,
            MOVE_TAB,
            CREATE_WORKSPACE,
            RENAME_WORKSPACE,
            CLOSE_WORKSPACE,
            MOVE_WORKSPACE,
            READ_ACK,
            UPDATE_HERDR,
            SAVE_SETTINGS,
            TEST_CONNECTION,
            APPROVE_HOST_KEY,
        }
    }
}

/** The one place a [UiMessage] becomes words. */
fun UiMessage.text(): String = when (this) {
    UiMessage.TargetChanged -> "Connection target changed — choose a session"
    is UiMessage.WorkspaceClosed -> "That workspace was closed — opened $opened"
    UiMessage.SessionClosed -> "Session closed"
    is UiMessage.OpenedInstead -> when (kind) {
        UiMessage.OpenedInstead.TargetKind.PANE ->
            "Original pane is gone — opened ${opened ?: "a neighbor"} instead"
        UiMessage.OpenedInstead.TargetKind.TAB ->
            "Original tab is gone — opened a neighbor tab instead"
        UiMessage.OpenedInstead.TargetKind.WORKSPACE ->
            "Original workspace is gone — opened another workspace instead"
    }
    is UiMessage.AttachFailed -> "Cannot attach: ${detail.orUnknown()}"
    UiMessage.ReconnectTimedOut -> "Reconnect timed out — tap Retry"
    UiMessage.StreamChanged -> "Connection changed during attach — tap Retry"
    is UiMessage.SendFailed -> "Send failed: ${detail.orUnknown()}"
    is UiMessage.CommandFailed -> "${command.label()}: ${detail.orUnknown()}"
}

/**
 * A daemon/transport detail, made safe for a one-line status strip.
 *
 * Raw exception messages arrive with newlines, control bytes, stack-ish
 * repetition or kilobytes of upstream payload; the strip is one line and must
 * never render escapes or push the layout. Unknown/blank stays "unknown error"
 * so failures always name a reason.
 */
internal fun String?.orUnknown(): String {
    val cleaned = this
        ?.map { if (it.isISOControl()) ' ' else it }
        ?.joinToString("")
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return "unknown error"
    return if (cleaned.length <= MAX_DETAIL_CHARS) {
        cleaned
    } else {
        cleaned.take(MAX_DETAIL_CHARS - 1).trimEnd() + "…"
    }
}

private const val MAX_DETAIL_CHARS = 160

private fun UiMessage.CommandFailed.Command.label(): String = when (this) {
    UiMessage.CommandFailed.Command.CREATE_TAB -> "Cannot create tab"
    UiMessage.CommandFailed.Command.CLOSE_TAB -> "Cannot close tab"
    UiMessage.CommandFailed.Command.RENAME_TAB -> "Cannot rename tab"
    UiMessage.CommandFailed.Command.MOVE_TAB -> "Cannot move tab"
    UiMessage.CommandFailed.Command.RENAME_WORKSPACE -> "Cannot rename workspace"
    UiMessage.CommandFailed.Command.CLOSE_WORKSPACE -> "Cannot close workspace"
    UiMessage.CommandFailed.Command.MOVE_WORKSPACE -> "Cannot move workspace"
    UiMessage.CommandFailed.Command.READ_ACK -> "Read acknowledgement failed"
    UiMessage.CommandFailed.Command.CREATE_WORKSPACE -> "Cannot create workspace"
    UiMessage.CommandFailed.Command.UPDATE_HERDR -> "Update failed"
    UiMessage.CommandFailed.Command.SAVE_SETTINGS -> "Cannot save settings"
    UiMessage.CommandFailed.Command.TEST_CONNECTION -> "Connection test failed"
    UiMessage.CommandFailed.Command.APPROVE_HOST_KEY -> "Cannot approve host key"
}
