package dev.herdr.mobile.terminal.emulator

/**
 * Rendering attributes for a single cell.
 *
 * `foreground` / `background` of `null` mean "use the theme default"
 * ([TerminalColorScheme.foreground] / [TerminalColorScheme.background]).
 * Instances are immutable; the emulator holds one "current" copy while parsing SGR.
 */
data class TextAttributes(
    val foreground: TerminalColor? = null,
    val background: TerminalColor? = null,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val blink: Boolean = false,
    val inverse: Boolean = false,
    val hidden: Boolean = false,
    val strike: Boolean = false,
)
