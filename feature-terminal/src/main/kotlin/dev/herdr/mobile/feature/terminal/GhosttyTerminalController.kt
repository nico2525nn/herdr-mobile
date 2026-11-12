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
    typeface: Typeface,
    fontSizeSp: Int,
    scheme: TerminalColorScheme,
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
        // First frame already carries the user's font/theme: attaching the
        // session first rendered the library default (14sp, default palette)
        // and then resized, which read as a font-size change.
        view.typeface = typeface
        view.fontSizeSp = fontSizeSp.toFloat()
        // Bell feedback is the app's policy (settings-aware); the library's own
        // haptic would double-buzz.
        view.bellHapticEnabled = false
        session.setTheme(scheme.toGhosttyTheme())
        session.setColorScheme(scheme.isDark())
        applySelectionColors(scheme)
        view.setBackgroundColor(scheme.background.toArgb())
        view.uiHandler = object : io.github.sagernet.libghostty.GhosttyUiHandler {
            override fun confirmUnsafePaste(context: Context, paste: () -> Unit) {
                val night = (context.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
                val theme = if (night) {
                    android.R.style.Theme_Material_Dialog_Alert
                } else {
                    android.R.style.Theme_Material_Light_Dialog_Alert
                }
                android.app.AlertDialog.Builder(context, theme)
                    .setTitle("Paste control characters?")
                    .setMessage("The clipboard contains characters that can drive the terminal. Paste anyway?")
                    .setPositiveButton("Paste") { _, _ -> paste() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        view.session = session
    }

    /** Selection uses the theme colour and picks a readable foreground. */
    private fun applySelectionColors(scheme: TerminalColorScheme) {
        val background = scheme.selection.toArgb()
        session.selectionBackground = background
        session.selectionForeground = if (scheme.selection.isLight()) {
            android.graphics.Color.BLACK
        } else {
            android.graphics.Color.WHITE
        }
    }

    /** Feed PTY bytes; the view redraws on the next frame. */
    fun feed(data: ByteArray) {
        if (!closed) session.feedOutput(data)
    }

    fun applyTheme(scheme: TerminalColorScheme) {
        session.setTheme(scheme.toGhosttyTheme())
        session.setColorScheme(scheme.isDark())
        applySelectionColors(scheme)
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
        view.requestFocus()
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

/** Relative luminance below 0.5 counts as a dark theme (DEC 2031 report). */
internal fun dev.herdr.mobile.feature.terminal.theme.TerminalColor.isLight(): Boolean {
    val r = red / 255f
    val g = green / 255f
    val b = blue / 255f
    return 0.2126f * r + 0.7152f * g + 0.0722f * b > 0.5f
}

internal fun TerminalColorScheme.isDark(): Boolean {
    val r = background.red / 255f
    val g = background.green / 255f
    val b = background.blue / 255f
    return 0.2126f * r + 0.7152f * g + 0.0722f * b < 0.5f
}
