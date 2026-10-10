package dev.herdr.mobile.terminal.view

import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dev.herdr.mobile.core.model.CursorStyle

/**
 * [TerminalViewClient] for a remote session: every keystroke goes to the network,
 * taps become server-routed mouse clicks, the keyboard never auto-shows.
 *
 * Input contract (mirrors the old HerdrTerminalView exactly):
 * - `onCodePoint` (soft-keyboard commit): forward as text, consume. The latch for
 *   sticky CTRL/ALT lives in the screen's ViewModel and is applied there.
 * - `onKeyDown` (hardware keys, DEL, arrows, TAB, ESC, Ctrl chords): map to bytes
 *   like the old `handleHardwareKey`, consume. Returning true keeps Termux's own
 *   KeyHandler (which would write into the dead local session) from running.
 * - `onSingleTapUp`: ALWAYS forward as a mouse click (server ignores it for
 *   non-mouse apps); never summon the keyboard (manual-only IME: the CJK
 *   panel's keyboard button is the single summon path).
 * - `onLongPress`: return false so Termux shows its own selection handles; the
 *   selected text is copied via `copyModeChanged` + clipboard.
 */
class TermuxViewClient(
    /** Direct text to the backend (soft-keyboard commits, mapped keys). */
    var onDirectInput: ((String) -> Unit)? = null,
    /** DEL key (sent as 0x7F by the screen). */
    var onDirectDelete: (() -> Unit)? = null,
    /** Tap cell, live-screen 0-based coords (screen forwards as mouse click). */
    var onTapCell: ((col: Int, row: Int) -> Unit)? = null,
    /** Pinch zoom delta (screen clamps through settings). */
    var onZoomFont: ((deltaSp: Float) -> Unit)? = null,
    // NOTE: no onSelection — selection copy arrives via
    // TermuxSessionClient.onCopyTextToClipboard, not the view client.
) : TerminalViewClient {

    /** Set after view creation (client is installed before attach). */
    var view: TerminalView? = null

    /** Sticky CTRL/ALT state, fed from the screen's ViewModel. */
    @Volatile
    var ctrlDown: Boolean = false

    @Volatile
    var altDown: Boolean = false

    override fun onScale(scale: Float): Float {
        // Pinch zoom is not implemented (font size lives in settings, as
        // before): return 1.0 so Termux's accumulator never grows. The delta
        // still reaches the screen in case settings-driven zoom is added.
        onZoomFont?.invoke(scale - 1f)
        return 1.0f
    }

    override fun onSingleTapUp(e: MotionEvent) {
        val v = view ?: return
        // Bypass sendMouseEvent (would emit SGR/X10 wire bytes into the dead
        // local session): the screen forwards as a terminal.mouse record and
        // Herdr routes it server-side (ghostty knows the real mouse state).
        val colRow = v.getColumnAndRow(e, false)
        onTapCell?.invoke(colRow[0], colRow[1])
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) {
        // No-op: the selection ActionMode's copy button routes through
        // TerminalSessionClient.onCopyTextToClipboard (TermuxSessionClient),
        // which the screen wires to the clipboard. Reading here would
        // double-copy on every selection exit.
    }

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        // Same mapping as the old handleHardwareKey: DEL/arrows/TAB/ESC/Enter
        // plus Ctrl chords synthesized from keycodes.
        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                onDirectDelete?.invoke()
                return true
            }
            KeyEvent.KEYCODE_ENTER -> {
                onDirectInput?.invoke("\r")
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                onDirectInput?.invoke("\u001B[A")
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                onDirectInput?.invoke("\u001B[B")
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                onDirectInput?.invoke("\u001B[D")
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                onDirectInput?.invoke("\u001B[C")
                return true
            }
            KeyEvent.KEYCODE_TAB -> {
                onDirectInput?.invoke("\t")
                return true
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                onDirectInput?.invoke("\u001B")
                return true
            }
        }
        val c = e.unicodeChar
        if (c != 0) {
            onDirectInput?.invoke(String(Character.toChars(c)))
            return true
        } else if (e.isCtrlPressed) {
            val ctrl = keyCodeToCtrlByte(keyCode)
            if (ctrl != null) {
                onDirectInput?.invoke(String(byteArrayOf(ctrl), Charsets.ISO_8859_1))
                return true
            }
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean {
        // Consume exactly what onKeyDown consumed; the framework owns the rest.
        return when (keyCode) {
            KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_ESCAPE -> true
            else -> e.unicodeChar != 0 || e.isCtrlPressed
        }
    }

    override fun onLongPress(event: MotionEvent): Boolean {
        // False = Termux shows its own selection handles.
        return false
    }

    override fun readControlKey(): Boolean = ctrlDown

    override fun readAltKey(): Boolean = altDown

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        if (codePoint <= 0) return true
        onDirectInput?.invoke(String(Character.toChars(codePoint)))
        return true
    }

    override fun onEmulatorSet() {
        // Session attached; nothing to do (resize flows through updateSize).
    }

    private fun keyCodeToCtrlByte(keyCode: Int): Byte? {
        if (keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            return (keyCode - KeyEvent.KEYCODE_A + 1).toByte()
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_SPACE -> 0x00.toByte()
            KeyEvent.KEYCODE_SLASH -> 0x1F.toByte()
            else -> null
        }
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

    @Suppress("UNUSED_PARAMETER")
    private fun unused(cursorStyle: CursorStyle) = Unit
}
