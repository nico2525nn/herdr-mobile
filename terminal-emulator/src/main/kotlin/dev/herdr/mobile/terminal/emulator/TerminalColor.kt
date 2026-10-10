package dev.herdr.mobile.terminal.emulator

/**
 * An sRGB colour with channels in `0..255`.
 *
 * The default 16-entry ANSI palette and the well-known theme colours live in
 * [TerminalColorScheme] / [TerminalThemes]; resolved colours are stored in
 * the scheme defaults so a renderer never needs palette lookups.
 */
data class TerminalColor(
    val red: Int,
    val green: Int,
    val blue: Int,
    val alpha: Int = 255,
) {
    init {
        require(red in 0..255 && green in 0..255 && blue in 0..255 && alpha in 0..255) {
            "channels must be in 0..255, was ($red, $green, $blue, $alpha)"
        }
    }

    /** Packed `0xAARRGGBB` for convenience when bridging to Android graphics types. */
    fun toArgb(): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue
}

/**
 * Colours that apply when a cell uses the theme default (a null cell color means "theme default").
 *
 * @param ansi Indexed palette, exactly 16 entries: `0..7` normal, `8..15` bright.
 */
data class TerminalColorScheme(
    val background: TerminalColor,
    val foreground: TerminalColor,
    val cursor: TerminalColor,
    val selection: TerminalColor,
    /** Indexed 0..7 normal, 8..15 bright. */
    val ansi: List<TerminalColor>,
) {
    init {
        require(ansi.size == 16) { "ansi palette must hold exactly 16 entries, was ${ansi.size}" }
    }
}
