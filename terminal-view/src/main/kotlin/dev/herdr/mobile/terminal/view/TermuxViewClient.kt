package dev.herdr.mobile.terminal.view

import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

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
    /**
     * Direct text to the backend (soft-keyboard commits ONLY — the VM applies
     * the sticky latch and paste-normalizes multi-char chunks here).
     */
    var onDirectInput: ((String) -> Unit)? = null,
    /**
     * Pre-encoded hardware-key bytes (KeyHandler port output, CTRL table):
     * travel as raw bytes, bypassing the text path's paste normalization
     * (which would strip their ESC leaders and corrupt every CSI sequence
     * into visible "[A"-style garbage — observed live).
     */
    var onDirectBytes: ((ByteArray) -> Unit)? = null,
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
        // TerminalView already requests focus immediately before invoking this
        // callback (upstream `TerminalView.java:151-160`), so no requestFocus
        // here — the binding below only serves getColumnAndRow.
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

    /** Combining-accent state for the unicode path (Termux parity). */
    private var combiningAccent = 0

    /** Page-scroll paging (shift+PgUp/PgDn on remote panes). Set by the screen. */
    var onPageScroll: ((up: Boolean) -> Unit)? = null

    private fun keyModeOf(e: KeyEvent): Int {
        var mode = 0
        if (e.isCtrlPressed || ctrlDown) mode = mode or TerminalKeyEncoder.KEYMOD_CTRL
        if (e.isAltPressed || altDown) mode = mode or TerminalKeyEncoder.KEYMOD_ALT
        if (e.isShiftPressed) mode = mode or TerminalKeyEncoder.KEYMOD_SHIFT
        if (e.isNumLockOn) mode = mode or TerminalKeyEncoder.KEYMOD_NUM_LOCK
        // Termux ignores Fn-held keys here (function key remapping upstream).
        return mode
    }

    /** Same MetaState transformation as upstream `TerminalView.java:749-822`; key-up uses it too. */
    private fun unicodeChar(e: KeyEvent): Int {
        var bitsToClear = KeyEvent.META_CTRL_MASK
        if (e.metaState and KeyEvent.META_ALT_RIGHT_ON == 0) {
            bitsToClear = bitsToClear or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        }
        var effectiveMeta = e.metaState and bitsToClear.inv()
        if (e.isShiftPressed) {
            effectiveMeta = effectiveMeta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        }
        return e.getUnicodeChar(effectiveMeta)
    }

    private fun cursorApp(): Boolean {
        return try {
            view?.currentSession?.emulator?.isCursorKeysApplicationMode == true
        } catch (_: Exception) {
            false
        }
    }

    private fun keypadApp(): Boolean {
        return try {
            view?.currentSession?.emulator?.isKeypadApplicationMode == true
        } catch (_: Exception) {
            false
        }
    }

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        // Termux TerminalView.onKeyDown order (upstream `TerminalView.java:749-822`), remote-adapted: ACTION_MULTIPLE
        // chars, shift+PgUp/PgDn paging, KeyHandler mapping (full modifiers),
        // then the unicode path (meta-cleared, combining accents, CTRL table,
        // BT quirks, ALT prefix). Anything unclaimed returns false so Termux's
        // own handler runs — but that writes into the dead local session, so
        // the port above must stay complete (see TerminalKeyEncoder).
        if (e.action == KeyEvent.ACTION_MULTIPLE && keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            val chars = e.characters ?: return false
            if (chars.isEmpty()) return false
            onDirectBytes?.invoke(chars.toByteArray(Charsets.UTF_8))
            return true
        }
        if ((keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == KeyEvent.KEYCODE_PAGE_DOWN) &&
            e.isShiftPressed
        ) {
            // Termux scrolls local scrollback here; remote panes have none
            // (server owns history), so page remotely instead. Local panes
            // (plain shells) fall through to Termux's own doScroll.
            val remote = host?.remoteEffective == true
            if (remote) {
                onPageScroll?.invoke(keyCode == KeyEvent.KEYCODE_PAGE_UP)
                return true
            }
            return false
        }
        if (!e.isFunctionPressed) {
            val code = TerminalKeyEncoder.termuxKey(keyCode, keyModeOf(e), cursorApp(), keypadApp())
            if (code != null) {
                // DEL travels the delete path (screen sends 0x7F verbatim);
                // everything else goes as raw bytes (bypassing text-path
                // normalization, which eats ESC leaders).
                if (keyCode == KeyEvent.KEYCODE_DEL && code == "\u007F") {
                    onDirectDelete?.invoke()
                } else {
                    onDirectBytes?.invoke(code.toByteArray(Charsets.ISO_8859_1))
                }
                return true
            }
        }
        // Unicode path: clear CTRL (handled below), let right-Alt compose.
        val result = unicodeChar(e)
        if (result == 0) {
            // No character and no mapping: last resort is the CTRL table for
            // keycodes whose unicode is 0 under CTRL (Termux inputCodePoint
            // covers this via onCodePoint; hardware path synthesizes here).
            if (e.isCtrlPressed || ctrlDown) {
                val ctrl = keyCodeToCtrlByte(keyCode)
                if (ctrl != null) {
                    onDirectBytes?.invoke(byteArrayOf(ctrl))
                    return true
                }
            }
            return false
        }
        val codePoint: Int
        if (result and KeyCharacterMap.COMBINING_ACCENT != 0) {
            if (combiningAccent != 0) {
                emitCodePoint(combiningAccent, e)
            }
            combiningAccent = result and KeyCharacterMap.COMBINING_ACCENT_MASK
            return true
        } else {
            codePoint = if (combiningAccent != 0) {
                val combined = KeyCharacterMap.getDeadChar(combiningAccent, result)
                combiningAccent = 0
                if (combined > 0) combined else result
            } else {
                result
            }
        }
        emitCodePoint(codePoint, e)
        return true
    }

    private fun emitCodePoint(codePoint: Int, e: KeyEvent) {
        val ctrl = e.isCtrlPressed || ctrlDown
        val alt = (e.metaState and KeyEvent.META_ALT_LEFT_ON) != 0 || altDown
        // Bytes, not text: NUL (ctrl-space) and C1 controls survive byte
        // transport exactly; the text path is for IME commits only.
        onDirectBytes?.invoke(TerminalKeyEncoder.termuxCodePoint(codePoint, ctrl, alt))
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean {
        // Consume exactly what onKeyDown consumed: recompute the same
        // decision (mapping hit, unicode nonzero, or CTRL fallback).
        if (e.action == KeyEvent.ACTION_MULTIPLE && keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            return !(e.characters.isNullOrEmpty())
        }
        if ((keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == KeyEvent.KEYCODE_PAGE_DOWN) &&
            e.isShiftPressed
        ) {
            return host?.remoteEffective == true
        }
        if (!e.isFunctionPressed &&
            TerminalKeyEncoder.termuxKey(keyCode, keyModeOf(e), cursorApp(), keypadApp()) != null
        ) {
            return true
        }
        if (unicodeChar(e) != 0) return true
        if ((e.isCtrlPressed || ctrlDown) && keyCodeToCtrlByte(keyCode) != null) return true
        return false
    }

    /** Set by the screen: suppresses move-induced long-press (see host). */
    var host: TermuxTerminalHost? = null

    override fun onLongPress(event: MotionEvent): Boolean {
        // Remote mode eats pre-arm MOVEs, so Termux's long-press timer would
        // fire on every slow scroll. Consume it when the finger moved (that
        // touch is a scroll); a truly still finger still selects.
        if (host?.touchMoved == true) return true
        // False = Termux shows its own selection handles.
        return false
    }

    override fun readControlKey(): Boolean = ctrlDown

    override fun readAltKey(): Boolean = altDown

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    /**
     * Termux's inherited `onCreateInputConnection()` handles `commitText()`
     * and dispatches text one code point at a time through this callback
     * (`TerminalView.java:297-390`); the remote client replaces only delivery,
     * not Android's IME/composition contract. `deleteSurroundingText()` is
     * inherited too and becomes KEYCODE_DEL events handled above
     * (`TerminalView.java:359-366`).
     *
     * Native CTRL (some soft keyboards commit control chars directly, e.g.
     * penti; upstream converts to printable + ctrlHeld at 378-426 and the
     * CTRL table lives in `inputCodePoint` at 828-888): route through the
     * same [TerminalKeyEncoder.termuxCodePoint] table as hardware keys so
     * e.g. native Ctrl+C arrives as 0x03, not the letter "c". Sticky-latch
     * CTRL is separate (applied by the VM on the text path) and untouched.
     */
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        if (codePoint <= 0) return true
        if (ctrlDown) {
            // Native-CTRL commit: CTRL table + BT quirks, no ALT prefix (the
            // inherited path passes leftAltDownFromEvent=false here).
            onDirectBytes?.invoke(
                TerminalKeyEncoder.termuxCodePoint(codePoint, ctrlDown = true, altDown = false),
            )
            return true
        }
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

}
