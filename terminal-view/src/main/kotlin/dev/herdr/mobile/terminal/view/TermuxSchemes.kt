package dev.herdr.mobile.terminal.view

import com.termux.terminal.TerminalColorScheme
import com.termux.terminal.TerminalColors
import dev.herdr.mobile.terminal.emulator.TerminalColor
import dev.herdr.mobile.terminal.emulator.TerminalThemes

/**
 * Our 8 bundled themes → Termux's global color scheme.
 *
 * Termux's [TerminalColors.COLOR_SCHEME] is process-wide static: [apply] mutates it
 * and the session's palette must then [reset] from it (the session copies on reset,
 * OSC 4/10/11 recolors after that are per-session until the next reset). Encapsulate
 * every scheme change here; two differently-themed views cannot coexist (we have one).
 *
 * Deltas vs our renderer (accepted): selection renders as inverse-video (Termux has
 * no selection index — our `selection` color is dropped), bold-is-bright is hardcoded
 * on in Termux's renderer (our default; `false` cannot be honored).
 */
object TermuxSchemes {

    fun apply(scheme: dev.herdr.mobile.terminal.emulator.TerminalColorScheme) {
        val props = java.util.Properties().apply {
            setProperty("foreground", scheme.foreground.hex())
            setProperty("background", scheme.background.hex())
            setProperty("cursor", scheme.cursor.hex())
            scheme.ansi.forEachIndexed { index, color ->
                setProperty("color$index", color.hex())
            }
        }
        TerminalColors.COLOR_SCHEME.updateWith(props)
    }

    /** Android background for the view: Termux's renderer never fills the default bg. */
    fun backgroundAndroid(scheme: dev.herdr.mobile.terminal.emulator.TerminalColorScheme): Int =
        android.graphics.Color.rgb(
            scheme.background.red,
            scheme.background.green,
            scheme.background.blue,
        )

    fun applyTheme(theme: TerminalThemes, id: dev.herdr.mobile.core.model.TerminalColorScheme) {
        apply(
            when (id) {
                dev.herdr.mobile.core.model.TerminalColorScheme.DARK -> TerminalThemes.dark()
                dev.herdr.mobile.core.model.TerminalColorScheme.LIGHT -> TerminalThemes.light()
                dev.herdr.mobile.core.model.TerminalColorScheme.SOLARIZED_DARK -> TerminalThemes.solarizedDark()
                dev.herdr.mobile.core.model.TerminalColorScheme.SOLARIZED_LIGHT -> TerminalThemes.solarizedLight()
                dev.herdr.mobile.core.model.TerminalColorScheme.TOKYO_NIGHT -> TerminalThemes.tokyoNight()
                dev.herdr.mobile.core.model.TerminalColorScheme.DRACULA -> TerminalThemes.dracula()
                dev.herdr.mobile.core.model.TerminalColorScheme.GRUVBOX_DARK -> TerminalThemes.gruvboxDark()
                dev.herdr.mobile.core.model.TerminalColorScheme.CAMPBELL -> TerminalThemes.campbell()
            },
        )
    }

    private fun TerminalColor.hex(): String = "#%02X%02X%02X".format(red, green, blue)
}
