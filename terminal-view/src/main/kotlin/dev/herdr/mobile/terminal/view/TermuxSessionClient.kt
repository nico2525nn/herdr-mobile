package dev.herdr.mobile.terminal.view

import android.util.Log
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import dev.herdr.mobile.core.model.CursorStyle

/**
 * [TerminalSessionClient] for a remote session: bell/title/colors callbacks plus the
 * cursor-style setting. `onSessionFinished` never fires (no process is ever forked).
 * `onTextChanged` is intentionally empty — the pump calls `view.onScreenUpdated()`
 * directly after every append, so a session-level invalidate would double-draw.
 */
class TermuxSessionClient(
    /** BEL sink (screen haptics). */
    var onBell: (() -> Unit)? = null,
    /** OSC title sink. */
    var onTitleChanged: ((String) -> Unit)? = null,
    /** Palette recolor sink (view repaint). */
    var onColorsChanged: (() -> Unit)? = null,
    /** OSC-52 copy sink (screen clipboard). */
    var onCopyText: ((String) -> Unit)? = null,
) : TerminalSessionClient {

    /** Our CursorStyle setting, mapped to Termux's int constants. */
    @Volatile
    var cursorStyle: CursorStyle = CursorStyle.BLOCK

    override fun getTerminalCursorStyle(): Int = when (cursorStyle) {
        CursorStyle.BLOCK -> TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
        CursorStyle.UNDERLINE -> TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE
        CursorStyle.BAR -> TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        // Deliberate no-op (see class doc).
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        changedSession.title?.let { onTitleChanged?.invoke(it) }
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        // Never fires: forkless session, no process to wait on.
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        if (text.isNotEmpty()) onCopyText?.invoke(text)
    }

    override fun onPasteTextFromClipboard(session: TerminalSession) {
        // No-op: see TermuxOutput.
    }

    override fun onBell(session: TerminalSession) {
        onBell?.invoke()
    }

    override fun onColorsChanged(session: TerminalSession) {
        onColorsChanged?.invoke()
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        // Blink state changes need no host action (the view drives the blink).
    }

    override fun logError(tag: String, message: String) {
        Log.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, "termux stacktrace", e)
    }
}
