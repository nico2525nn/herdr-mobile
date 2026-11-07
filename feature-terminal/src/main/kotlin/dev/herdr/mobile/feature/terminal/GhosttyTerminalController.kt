package dev.herdr.mobile.feature.terminal

import android.content.Context
import android.graphics.Typeface
import dev.herdr.mobile.feature.terminal.theme.TerminalColorScheme
import io.github.sagernet.libghostty.GhosttyTerminalSession
import io.github.sagernet.libghostty.GhosttyTerminalView
import io.github.sagernet.libghostty.GhosttyCursorStyle

/**
 * One libghostty terminal plus its view.
 *
 * The engine owns parsing, modes, key encoding, selection and the viewport;
 * the transport only carries bytes to/from the network. Everything here runs
 * on the main thread (the library requires it).
 */
internal class GhosttyTerminalController(
    context: Context,
    scrollbackLimit: Int,
    cursorStyle: GhosttyCursorStyle,
    onInput: (ByteArray) -> Unit,
    onResize: (cols: Int, rows: Int) -> Unit,
    onBell: () -> Unit,
) : AutoCloseable {

    val session: GhosttyTerminalSession = GhosttyTerminalSession(
        context,
        GhosttyTerminalSession.Options(
            columns = 80,
            rows = 24,
            maxScrollbackLines = scrollbackLimit.coerceAtLeast(100).toLong(),
            defaultCursorStyle = cursorStyle,
            defaultCursorBlink = true,
        ),
    )

    val view: GhosttyTerminalView = GhosttyTerminalView(context)

    private var closed = false

    init {
        session.transport = object : GhosttyTerminalSession.Transport {
            override fun sendInput(data: ByteArray) = onInput(data)

            override fun sendResize(columns: Int, rows: Int, widthPixels: Int, heightPixels: Int) {
                onResize(columns, rows)
            }

            override fun close() = Unit
        }
        session.eventListener = object : GhosttyTerminalSession.EventListener {
            override fun onBell(session: GhosttyTerminalSession) = onBell()
        }
        view.session = session
    }

    /** Feed PTY bytes; the view redraws on the next frame. */
    fun feed(data: ByteArray) {
        if (!closed) session.feedOutput(data)
    }

    fun applyTheme(scheme: TerminalColorScheme) {
        session.setTheme(scheme.toGhosttyTheme())
        view.setBackgroundColor(scheme.background.toArgb())
    }

    fun applyFont(typeface: Typeface, sizeSp: Int) {
        view.typeface = typeface
        view.fontSizeSp = sizeSp.toFloat()
    }

    fun applyCursorStyle(style: GhosttyCursorStyle) {
        session.setDefaultCursorStyle(style)
    }

    fun showKeyboard() {
        view.showIme()
    }

    fun scrollToBottom() {
        session.scrollToBottom()
    }

    /** Diagnostics: alt-screen / mouse-tracking bits, and [total, offset, len]. */
    fun viewportState(): Int = session.viewportState()

    fun scrollbar(): LongArray? = session.scrollbar()

    override fun close() {
        if (closed) return
        closed = true
        view.session = null
        session.close()
    }

    companion object {
        const val VIEWPORT_ALTERNATE_SCREEN = 1
        const val VIEWPORT_MOUSE_TRACKING = 2
    }
}
