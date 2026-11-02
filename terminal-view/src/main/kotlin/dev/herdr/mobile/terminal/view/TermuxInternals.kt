package dev.herdr.mobile.terminal.view

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalRenderer
import com.termux.view.TerminalView
import java.lang.reflect.Field

/**
 * Single door to the Termux internals this app has to reach into.
 *
 * Termux's `terminal-emulator` / `terminal-view` AAR (pinned v0.118.3, see the
 * version catalog) has no public API for a handful of values the forkless
 * remote session needs: the session's emulator, the renderer's
 * line-spacing-plus-ascent, the view's scroll offset, its default selectors, and
 * the DECSET flag word. Reflection was previously spread across four classes,
 * so every AAR bump meant hunting for them — and a renamed field silently
 * degraded instead of failing at one known place.
 *
 * Rules for this file:
 * - one lookup per field, resolved once at class init;
 * - mismatch = either a loud [IllegalStateException] (session emulator, without
 *   which nothing works) or a `null` the caller degrades on (drawing offsets);
 * - keep the R8 keep rules in `proguard-rules.pro` in sync.
 */
object TermuxInternals {

    private fun fieldOf(type: Class<*>, name: String): Field? = runCatching {
        type.getDeclaredField(name).apply { isAccessible = true }
    }.getOrNull()

    private val sessionEmulatorField: Field? =
        fieldOf(TerminalSession::class.java, "mEmulator")
    private val viewTopRowField: Field? =
        fieldOf(TerminalView::class.java, "mTopRow")
    private val viewDefaultSelectorsField: Field? =
        fieldOf(TerminalView::class.java, "mDefaultSelectors")
    private val rendererLineSpacingAndAscentField: Field? =
        fieldOf(TerminalRenderer::class.java, "mFontLineSpacingAndAscent")
    private val viewRendererField: Field? =
        fieldOf(TerminalView::class.java, "mRenderer")

    /** DECSET 2004 (bracketed paste) inside Termux's private flag word. */
    const val DECSET_BIT_BRACKETED_PASTE = 1024

    /**
     * Injects a forkless emulator into a Termux session.
     *
     * Throws when the field moved: without it the view has no emulator and the
     * screen would render blank forever, which is far harder to diagnose than a
     * crash at attach time.
     */
    fun installEmulator(session: TerminalSession, emulator: TerminalEmulator) {
        val field = sessionEmulatorField ?: throw IllegalStateException(
            "Termux TerminalSession.mEmulator gone — pinned v0.118.3 API changed",
        )
        field.set(session, emulator)
    }

    /** View scroll offset in rows (0 = bottom, negative = history). */
    fun topRow(view: TerminalView): Int? =
        viewTopRowField?.let { runCatching { it.getInt(view) }.getOrNull() }

    /** The view's renderer (row pitch, metrics), or null when unavailable. */
    fun renderer(view: TerminalView): TerminalRenderer? =
        viewRendererField?.let { runCatching { it.get(view) as? TerminalRenderer }.getOrNull() }

    /** Exact row pitch Termux draws and scrolls by, in pixels. */
    fun rowPitchPx(view: TerminalView): Float? =
        renderer(view)?.let { r -> r.fontLineSpacing.takeIf { it > 0 }?.toFloat() }

    /** Y offset of the first row inside the renderer's line box. */
    fun lineSpacingAndAscent(renderer: TerminalRenderer): Int? =
        rendererLineSpacingAndAscentField?.let { runCatching { it.getInt(renderer) }.getOrNull() }

    /** Per-column default colour selectors, or null when unavailable. */
    fun defaultSelectors(view: TerminalView): IntArray? =
        viewDefaultSelectorsField?.let { runCatching { it.get(view) as? IntArray }.getOrNull() }

    /** Raw DECSET flags of an emulator, or null when the field is gone. */
    fun decsetFlags(emulator: Any?): Int? {
        if (emulator == null) return null
        val field = fieldOf(emulator.javaClass, "mCurrentDecSetFlags") ?: return null
        return runCatching { field.getInt(emulator) }.getOrNull()
    }

    /** Bracketed-paste mode (DECSET 2004) without Termux's missing accessor. */
    fun isBracketedPasteEnabled(emulator: Any?): Boolean {
        val flags = decsetFlags(emulator) ?: return false
        return flags and DECSET_BIT_BRACKETED_PASTE != 0
    }
}
