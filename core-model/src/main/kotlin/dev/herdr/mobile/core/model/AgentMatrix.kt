package dev.herdr.mobile.core.model

/**
 * Per-agent input behavior matrix: scroll routing + paste bracketing.
 *
 * Each known --kind has an explicit route and an evidence note on its entry.
 * High-confidence choices have direct source or isolated live-pane evidence;
 * lower-confidence defaults are conservative where upstream does not specify
 * a behavior. Unknown kinds retain the safe OMP defaults (page_key scroll,
 * emulator-bit paste gate).
 *
 * Evidence labels in entry comments: `measured` = isolated live-pane result;
 * `source` = upstream implementation; `docs`/`issue` = upstream documentation
 * or report, which may not establish every terminal detail. Key protocols,
 * alt-screen rendering, and mouse tracking are not represented by the routes;
 * entry comments call out material known gaps. Wheel rows/event is explicit
 * only where evidence establishes a non-default stride.
 */
object AgentMatrix {

    /** Scroll transport per agent kind (lowercase match). */
    enum class ScrollRoute {
        /** PgUp/PgDn bytes via page_key; action/step is defined by the app. */
        PAGE_KEY,
        /** SGR wheel events; event stride is in [Entry.wheelRowsPerEvent]. */
        WHEEL,
    }

    /** Paste bracketing per agent kind (lowercase match). */
    enum class PasteRoute {
        /** Trust the live emulator's 2004 bit (Termux paste() parity). */
        EMULATOR_BIT,
        /** Always bracket: TUI renders server-side, local never sees DECSET. */
        ALWAYS_BRACKET,
    }

    private data class Entry(
        val scroll: ScrollRoute,
        val paste: PasteRoute,
        val wheelRowsPerEvent: Int = 1,
    )

    private val MATRIX: Map<String, Entry> = mapOf(
        // OpenAI Codex, openai/codex@d807d44a: codex-rs/tui/src/pager_overlay.rs
        // (Up/Down one row; PgUp/PgDn page); existing live measurement also
        // records 1-row wheel and ?2004h. 2004 not verified from source here.
        "codex" to Entry(ScrollRoute.WHEEL, PasteRoute.EMULATOR_BIT),
        // Pi, earendil-works/pi@209bc7b9, packages/tui/src/tui-alt-screen.ts
        // (:272, :763) and packages/tui/src/terminal.ts:266; live measured
        // full-page PgUp, 1-row wheel, and accepted bracketed paste.
        "pi" to Entry(ScrollRoute.WHEEL, PasteRoute.ALWAYS_BRACKET),
        // OMP, can1357/oh-my-pi@403931b9, docs/tui-runtime-internals.md
        // (terminal lifecycle/stdin normalization); scroll/paste measured.
        "omp" to Entry(ScrollRoute.PAGE_KEY, PasteRoute.EMULATOR_BIT),
        // OpenCode, anomalyco/opencode@51e310c9,
        // packages/opencode/src/config/keybinds.ts; scroll/paste measured
        // (full-page PgUp, one-row wheel, bracketed payload honored).
        "opencode" to Entry(ScrollRoute.WHEEL, PasteRoute.ALWAYS_BRACKET),
        // Claude Code: code.claude.com/docs/en/terminal-config, “Enter
        // multiline prompts” (Ctrl+J / Shift+Enter only; no scroll/paste spec).
        // Routes remain conservative defaults; low-confidence for omitted modes.
        "claude" to Entry(ScrollRoute.PAGE_KEY, PasteRoute.EMULATOR_BIT),
        // Gemini CLI, google-gemini/gemini-cli@caa04664: packages/cli/src/ui/
        // utils/terminalCapabilityManager.ts enables ?2004h (and Kitty or
        // modifyOtherKeys when supported); core/src/utils/terminal.ts defines
        // SGR mouse and conditional alt-screen modes.
        // docs/reference/keyboard-shortcuts.md lists PgUp/PgDn page scroll;
        // both selected routes are source-confirmed (high confidence).
        "gemini" to Entry(ScrollRoute.PAGE_KEY, PasteRoute.EMULATOR_BIT),
        // Cursor CLI: cursor.com/docs/cli/reference/terminal-setup documents
        // newline shortcuts, not scroll/paste: low-confidence defaults.
        "cursor" to Entry(ScrollRoute.PAGE_KEY, PasteRoute.EMULATOR_BIT),
        // Devin: docs.devin.ai/cli/reference/keyboard-shortcuts#input-shortcuts
        // documents Enter/Shift+Enter; scroll and 2004 are unspecified (low).
        "devin" to Entry(ScrollRoute.PAGE_KEY, PasteRoute.EMULATOR_BIT),
        // Antigravity (agy): antigravity.google/docs/cli/prompting (prompt box,
        // multiline composition); 2004 and transcript scrolling unspecified.
        "agy" to Entry(ScrollRoute.PAGE_KEY, PasteRoute.EMULATOR_BIT),
        // Cline 3.0.62/3.0.24: github.com/cline/cline/issues/14168,
        // /11531 reports PgUp/PgDn transcript scroll + bracket markers; wheel
        // captured without scrolling. Issue/binary evidence; low confidence.
        "cline" to Entry(ScrollRoute.PAGE_KEY, PasteRoute.EMULATOR_BIT),
        // MastraCode, mastra-ai/mastra@a7bbe773,
        // mastracode/tui/src/tui/components/custom-editor.ts:24-25, 424-483,
        // 601-609: parses/wraps bracket markers; does not prove 2004 is enabled.
        "mastracode" to Entry(ScrollRoute.PAGE_KEY, PasteRoute.EMULATOR_BIT),
        // GitHub Copilot CLI issue #2162 confirms PageUp/PageDown scroll
        // session history and mouse wheel works unless mouse=false — but
        // neither wheel stride (1 row? 3 rows?) nor 2004 behavior is
        // established. PAGE_KEY is the conservative route: PgUp is proven
        // to scroll, and page_key degrades to host offset when ignored.
        "copilot" to Entry(ScrollRoute.PAGE_KEY, PasteRoute.EMULATOR_BIT),
        // Kimi: github.com/MoonshotAI/kimi-cli/docs/en/reference/keyboard.md
        // documents Ctrl-J/Alt-Enter and Ctrl-V, not scroll/2004 protocols.
        "kimi" to Entry(ScrollRoute.PAGE_KEY, PasteRoute.EMULATOR_BIT),
        // Kiro CLI docs: kiro.dev/docs/cli/terminal-ui/ (“arrow keys to scroll
        // long responses”, paste long text); issue #10775 reports ESC[200~ input
        // handling. No pinned source or ?2004 enable evidence; low confidence.
        "kiro" to Entry(ScrollRoute.PAGE_KEY, PasteRoute.EMULATOR_BIT),
        // Factory Droid: docs.factory.com/droid-cli/cli-reference, interactive
        // shortcuts document Alt+Up/Down transcript navigation; wheel/2004
        // source behavior unavailable (proprietary; low confidence).
        "droid" to Entry(ScrollRoute.PAGE_KEY, PasteRoute.EMULATOR_BIT),
        // Amp: ampcode.com/docs/cli/keybindings documents Enter/Shift+Enter,
        // Ctrl+J and image paste; no transcript scroll or 2004 spec (proprietary).
        "amp" to Entry(ScrollRoute.PAGE_KEY, PasteRoute.EMULATOR_BIT),
        // xAI Grok CLI: keyboard docs claim PgUp/PgDn page scrolling, but
        // the cited commit/path could not be re-verified (integration
        // review). Kept as conservative PAGE_KEY; low confidence.
        "grok" to Entry(ScrollRoute.PAGE_KEY, PasteRoute.EMULATOR_BIT),
        // Hermes, NousResearch/hermes-agent (main ui-tui/src/app/
        // useMainApp.ts:87-88 enables ?2004h): paste markers honored, but
        // no source establishes wheel stride or PgUp behavior. PAGE_KEY is
        // the conservative route until wheel is measured.
        "hermes" to Entry(ScrollRoute.PAGE_KEY, PasteRoute.EMULATOR_BIT),
        // Kilo-Org/kilocode@be0ea199, packages/opencode/src/config/keybinds.ts
        // defines messages_page_up/down as PgUp/PgDn page actions; Kilo's
        // Session uses OpenTUI ScrollBox. Paste is Ctrl+V; 2004 enable/handling
        // is not established by these sources (scroll high, paste low).
        "kilo" to Entry(ScrollRoute.PAGE_KEY, PasteRoute.EMULATOR_BIT),
        // Qoder CLI: docs.qoder.com/cli/interface#keyboard-shortcuts documents
        // Shift+Up/Down scroll keys, Ctrl+V; scroll target and 2004 unspecified.
        "qodercli" to Entry(ScrollRoute.PAGE_KEY, PasteRoute.EMULATOR_BIT),
        // Qwen Code, QwenLM/qwen-code@5581424b, docs/users/reference/
        // keyboard-shortcuts.md#history-scrollback: wheel scrolls 3 lines/tick;
        // the stride is represented explicitly to preserve that behavior.
        "qwen" to Entry(ScrollRoute.WHEEL, PasteRoute.EMULATOR_BIT, wheelRowsPerEvent = 3),
        // Letta: docs.letta.com/platform/cli/reference/#keyboard-shortcuts
        // documents Ctrl+V and Shift+Enter, but not scroll/2004 behavior.
        "letta" to Entry(ScrollRoute.PAGE_KEY, PasteRoute.EMULATOR_BIT),
        // Maki: maki.sh/docs/keybindings/#editing documents PgUp/PgDn and
        // Ctrl+U/D half-page; bracketed paste/alt-screen are unspecified.
        "maki" to Entry(ScrollRoute.PAGE_KEY, PasteRoute.EMULATOR_BIT),
        // Muse: dev.meta.ai/docs/muse-code/interactive documents session
        // steering and image paste, not scroll or 2004 terminal protocols.
        "muse" to Entry(ScrollRoute.PAGE_KEY, PasteRoute.EMULATOR_BIT),
    )

    private val DEFAULT = Entry(ScrollRoute.PAGE_KEY, PasteRoute.EMULATOR_BIT)

    private fun entry(agent: String?): Entry =
        MATRIX[agent?.lowercase()] ?: DEFAULT

    /** Whether [agent] has a behavior entry; unknown explicit app identities use [DEFAULT]. */
    fun isKnownApp(agent: String?): Boolean =
        agent != null && MATRIX.containsKey(agent.lowercase())

    /**
     * Page-key scrolling is permitted only for an explicit app identity or an
     * active alternate screen. The identity must come from core status.app,
     * never the foreground process/command name (for example `sleep`).
     */
    fun allowPageKey(appIdentity: String?, altActive: Boolean): Boolean =
        appIdentity != null || altActive

    /** True when [agent]'s scroll must go via SGR wheel (not page_key). */
    fun useWheelScroll(agent: String?): Boolean =
        entry(agent).scroll == ScrollRoute.WHEEL

    /** Number of transcript rows advanced by one SGR wheel event. */
    fun wheelRowsPerEvent(agent: String?): Int =
        entry(agent).wheelRowsPerEvent

    /** True when [agent]'s paste must always carry 2004 markers. */
    fun alwaysBracket(agent: String?): Boolean =
        entry(agent).paste == PasteRoute.ALWAYS_BRACKET
}
