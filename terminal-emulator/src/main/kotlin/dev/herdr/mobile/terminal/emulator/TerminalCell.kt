package dev.herdr.mobile.terminal.emulator

/**
 * One screen cell.
 *
 * @param codepoint Unicode code point displayed in this cell (`32` = blank space).
 * @param wideContinuation `true` for the second half of a double-width (CJK/emoji) glyph;
 *   renderers should skip these cells and let the leading cell draw across both.
 */
data class TerminalCell(
    val codepoint: Int,
    val wideContinuation: Boolean = false,
    val attrs: TextAttributes = TextAttributes(),
) {
    val isBlank: Boolean get() = codepoint == 32 && attrs.background == null && !wideContinuation
}
