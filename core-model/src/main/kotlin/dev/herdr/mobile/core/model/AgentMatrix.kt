package dev.herdr.mobile.core.model

/**
 * Per-agent input behavior matrix: scroll routing + paste bracketing.
 *
 * Every entry is MEASURED on a live pane (isolated workspace, resumed
 * session) or cited from the agent's own source/docs — never guessed.
 * Unknown agents fall back to OMP behavior (page_key scroll, emulator-bit
 * paste gate), the safest default: page_key degrades to host offset when
 * the app ignores it, and unbracketed paste never corrupts.
 *
 * Sources:
 * - Pi scroll/paste: measured 2026-10-28 (PgUp=full viewport, wheel=1 row,
 *   raw CR submits line-by-line, bracketed CR lands as one block). Pi source
 *   confirms: pageUp scrolls viewportHeight-4 (tui-alt-screen.ts:763),
 *   wheel defaults 1 line/event (wheelScrollLines ?? 1, :272), paste via
 *   2004 markers re-wrapped by terminal.ts:266.
 * - OMP scroll: measured line-precise page_key (pre-1.3.21). Paste via 2004
 *   (docs/tui-runtime-internals.md:55: "Enables raw mode and bracketed
 *   paste", StdinBuffer emits paste events).
 * - Codex scroll: measured 18-19 rows/PgUp, 1 row/wheel (pre-1.3.21).
 *   Paste: enables ?2004h (binary strings), pager scroll_up/down = 1 row,
 *   page_up/down = full page (pager_overlay.rs:291-303).
 * - Opencode scroll: measured 2026-10-28 (PgUp=full page, wheel=1 row).
 *   Docs: messages_page_up/pageup, messages_line_up/ctrl+alt+y
 *   (keybinds.ts). Paste: 2004 honored (measured bracketed CR/LF land
 *   as one block), raw CR submits immediately.
 */
object AgentMatrix {

    /** Scroll transport per agent kind (lowercase match). */
    enum class ScrollRoute {
        /** PgUp/PgDn bytes via page_key; app scrolls line-precisely. */
        PAGE_KEY,
        /** SGR wheel events, 1 row per event. */
        WHEEL,
    }

    /** Paste bracketing per agent kind (lowercase match). */
    enum class PasteRoute {
        /** Trust the live emulator's 2004 bit (Termux paste() parity). */
        EMULATOR_BIT,
        /** Always bracket: TUI renders server-side, local never sees DECSET. */
        ALWAYS_BRACKET,
    }

    private data class Entry(val scroll: ScrollRoute, val paste: PasteRoute)

    private val MATRIX: Map<String, Entry> = mapOf(
        "codex" to Entry(ScrollRoute.WHEEL, PasteRoute.EMULATOR_BIT),
        "pi" to Entry(ScrollRoute.WHEEL, PasteRoute.ALWAYS_BRACKET),
        "omp" to Entry(ScrollRoute.PAGE_KEY, PasteRoute.EMULATOR_BIT),
        "opencode" to Entry(ScrollRoute.WHEEL, PasteRoute.ALWAYS_BRACKET),
    )

    private val DEFAULT = Entry(ScrollRoute.PAGE_KEY, PasteRoute.EMULATOR_BIT)

    private fun entry(agent: String?): Entry =
        MATRIX[agent?.lowercase()] ?: DEFAULT

    /** True when [agent]'s scroll must go via SGR wheel (not page_key). */
    fun useWheelScroll(agent: String?): Boolean =
        entry(agent).scroll == ScrollRoute.WHEEL

    /** True when [agent]'s paste must always carry 2004 markers. */
    fun alwaysBracket(agent: String?): Boolean =
        entry(agent).paste == PasteRoute.ALWAYS_BRACKET
}
