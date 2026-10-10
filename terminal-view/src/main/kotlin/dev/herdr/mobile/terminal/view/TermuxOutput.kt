package dev.herdr.mobile.terminal.view

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Routes emulator output to the remote backend instead of a local PTY.
 *
 * Termux's [TerminalEmulator] answers DA/DSR/OSC queries by writing into the
 * [TerminalOutput] given to its constructor. With a stock session that sink is the
 * session itself (bytes vanish into the local PTY); here every response goes to
 * Herdr over the terminal socket. Fire-and-forget on [scope]: query answers must
 * never block the main-thread pump that feeds the emulator.
 */
class TermuxOutput(
    private val send: suspend (ByteArray) -> Unit,
    private val scope: CoroutineScope,
    /** BEL counter sink (haptics live in the screen). */
    var onBell: (() -> Unit)? = null,
    /** OSC-52 copy text (screen puts it on the clipboard). */
    var onCopyText: ((String) -> Unit)? = null,
    /** OSC 4/10/11 recolored the palette: the view must repaint. */
    var onColorsChanged: (() -> Unit)? = null,
    /** OSC title change. */
    var onTitleChanged: ((String) -> Unit)? = null,
) : TerminalOutput() {

    override fun write(data: ByteArray, offset: Int, count: Int) {
        if (count <= 0) return
        val copy = data.copyOfRange(offset, offset + count)
        scope.launch { runCatching { send(copy) } }
    }

    override fun titleChanged(oldTitle: String, newTitle: String) {
        onTitleChanged?.invoke(newTitle)
    }

    override fun onCopyTextToClipboard(text: String) {
        if (text.isNotEmpty()) onCopyText?.invoke(text)
    }

    override fun onPasteTextFromClipboard() {
        // No-op: pasting goes through the CJK panel (explicit user action with
        // composition), never through an app-initiated bracketed-paste request.
    }

    override fun onBell() {
        onBell?.invoke()
    }

    override fun onColorsChanged() {
        onColorsChanged?.invoke()
    }
}

/** Current emulator grid size, for the resize watcher. */
internal fun TerminalEmulator.grid(): Pair<Int, Int> = mColumns to mRows
