package dev.herdr.mobile.terminal.view

/**
 * Extra Keys → bytes the remote PTY understands.
 *
 * Semantics follow the Termux default two-row layout named in the plan; only the transport
 * changed (raw bytes into the daemon socket instead of a local PTY).
 */
object TerminalKeyEncoder {

    const val ESC = 0x1B.toByte()

    /** Single extra key: either literal [bytes] or a set of Herdr key-combo [herdrKeys]. */
    data class Key(
        val label: String,
        val bytes: ByteArray? = null,
        val herdrKeys: List<String>? = null,
        /** While held, the next key is sent with this modifier. */
        val modifier: Modifier? = null,
        /** Down-flick action: bytes sent when swiped down instead of tapped. */
        val flickDownBytes: ByteArray? = null,
        /** Label hint for the down-flick action (shown under [label]). */
        val flickDownLabel: String? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Key) return false
            return label == other.label
        }

        override fun hashCode(): Int = label.hashCode()
    }

    enum class Modifier { CTRL, ALT, SHIFT }

    /**
     * Flick direction for dual-action keys: vertical swipe selects which
     * action fires. Consumed by [flickBytes], not by the encoder directly.
     */
    enum class Flick { UP, DOWN }

    private fun esc(seq: String): ByteArray = byteArrayOf(ESC) + seq.toByteArray(Charsets.US_ASCII)

    /** The Termux default layout, two rows of seven. */
    val DEFAULT_LAYOUT: List<List<Key>> = listOf(
        listOf(
            Key("ESC", bytes = byteArrayOf(ESC)),
            Key("/", bytes = "/".toByteArray()),
            Key("-", bytes = "-".toByteArray()),
            Key("HOME", bytes = esc("[H")),
            Key("↑", bytes = esc("[A")),
            Key("END", bytes = esc("[F")),
            // Dual-action: tap = PGUP, flick down = PGDN (frees the lower
            // row's PGDN slot for SHIFT without breaking the 7-key grid).
            Key("PGUP", bytes = esc("[5~"), flickDownBytes = esc("[6~"), flickDownLabel = "PGDN"),
        ),
        listOf(
            Key("TAB", bytes = byteArrayOf(0x09)),
            Key("CTRL", modifier = Modifier.CTRL),
            Key("ALT", modifier = Modifier.ALT),
            Key("←", bytes = esc("[D")),
            Key("↓", bytes = esc("[B")),
            Key("→", bytes = esc("[C")),
            Key("SHIFT", modifier = Modifier.SHIFT),
        ),
    )

    /**
     * Apply a latched [modifier] to a printable [key]: `ctrl+c` → 0x03, `alt+x` → ESC x.
     * Non-printable keys ignore the modifier and are sent as-is.
     *
     * Returns null when the combination is meaningless (e.g. CTRL+TAB): the
     * caller keeps the latch instead of silently swallowing it. Keys carrying
     * only [Key.herdrKeys] also return null — no consumer implements that path
     * yet, so sending empty bytes would be a silent no-op.
     *
     * SHIFT on a single byte uppercases letters (Termux commitText parity:
     * Gboard shift produces the shifted char before we ever see it, so the
     * latch must do the same for Extra Keys). SHIFT on a CSI sequence rewrites
     * it to the `;2` modified form (arrows, HOME/END, PGUP/PGDN, TAB→`ESC[Z`).
     */
    fun withModifier(modifier: Modifier?, key: Key): ByteArray? {
        val base = key.bytes ?: return null
        if (modifier == null) return base
        if (modifier == Modifier.SHIFT) return withShift(base)
        if (base.size != 1) return base
        val byte = base[0].toInt() and 0xFF
        return when (modifier) {
            Modifier.CTRL -> {
                val lower = byte or 0x20
                if (lower in 0x61..0x7A || byte in 0x40..0x5F) {
                    byteArrayOf((byte and 0x1F).toByte())
                } else {
                    // Meaningless combination (CTRL+TAB, CTRL+/ …): signal it so
                    // the UI can keep the latch and/or give feedback.
                    null
                }
            }

            Modifier.ALT -> byteArrayOf(ESC, base[0])
            Modifier.SHIFT -> null // unreachable: handled above
        }
    }

    /**
     * Apply SHIFT to already-encoded [base] bytes: letters uppercase, CSI
     * cursor/paging sequences gain the `;2` modifier (xterm modifyOtherKeys
     * convention, same table as [termuxKey]'s modifier=2). Unrecognized
     * sequences pass through unchanged — SHIFT+unknown must still do
     * something sane rather than swallow the key.
     */
    fun withShift(base: ByteArray): ByteArray {
        if (base.size == 1) {
            val b = base[0].toInt() and 0xFF
            if (b in 0x61..0x7A) return byteArrayOf((b - 0x20).toByte())
            return base
        }
        // CSI sequences this panel emits: ESC [ <params> <final>.
        // Rewrite param-less cursor/paging forms to the ;2 modified form.
        val text = try {
            base.toString(Charsets.US_ASCII)
        } catch (_: Exception) {
            return base
        }
        if (!text.startsWith("\u001B[")) return base
        val shifted = when (text) {
            "\u001B[A" -> "\u001B[1;2A"
            "\u001B[B" -> "\u001B[1;2B"
            "\u001B[C" -> "\u001B[1;2C"
            "\u001B[D" -> "\u001B[1;2D"
            "\u001B[H" -> "\u001B[1;2H"
            "\u001B[F" -> "\u001B[1;2F"
            "\u001B[5~" -> "\u001B[5;2~"
            "\u001B[6~" -> "\u001B[6;2~"
            "\u001B[2~" -> "\u001B[2;2~"
            "\u001B[3~" -> "\u001B[3;2~"
            else -> return base
        }
        return shifted.toByteArray(Charsets.US_ASCII)
    }

    /**
     * Apply [modifiers] to one committed [text] chunk from the soft keyboard.
     * Single ASCII char: SHIFT uppercases, CTRL maps to 0x00–0x1F, ALT prefixes
     * ESC (all stack: Shift+Ctrl+Alt+X → ESC uppercase-then-ctrl). Multi-char
     * or non-ASCII text returns null — an atomic paste must never be
     * half-modified; the caller sends it verbatim and keeps the latch armed.
     */
    fun withModifiers(modifiers: Set<Modifier>, text: String): ByteArray? {
        if (modifiers.isEmpty() || text.length != 1) return null
        val byte = text[0].code
        if (byte !in 0x20..0x7E) return null
        var out = byteArrayOf(byte.toByte())
        // SHIFT first (case shift is input-level), then CTRL, then ALT prefix.
        if (Modifier.SHIFT in modifiers) {
            out = withShift(out)
        }
        if (Modifier.CTRL in modifiers) {
            out = withModifier(Modifier.CTRL, Key("", bytes = out)) ?: return null
        }
        // ALT stacks over whatever CTRL produced (still 1 byte): ESC-prefix it
        // directly — withModifier(ALT) on a control byte is the same operation,
        // but spelling it out keeps the stacking order explicit.
        if (Modifier.ALT in modifiers) {
            out = byteArrayOf(ESC, out[0])
        }
        return out
    }

    /** CTRL-C / interrupt as raw byte, the fastest path that needs no round trip. */
    fun interrupt(): ByteArray = byteArrayOf(0x03)

    // ------------------------------------------------------------------
    // Termux KeyHandler port (terminal-emulator KeyHandler.java v0.118.3 +
    // TerminalView.onKeyDown inputCodePoint). Hardware keys that this returns
    // null for fall through to Termux's own handler — which writes into our
    // dead local session (vanishes). So this must cover everything Termux
    // covers, or keys silently die.
    // ------------------------------------------------------------------

    const val KEYMOD_SHIFT = 1
    const val KEYMOD_CTRL = 2
    const val KEYMOD_ALT = 4
    const val KEYMOD_NUM_LOCK = 8

    /**
     * Termux `KeyHandler.getCode`: keyCode + modifiers to wire bytes, or null
     * when Termux itself falls through to unicode processing (printables,
     * SPACE without CTRL). [cursorApp]/[keypadApp] come from the live emulator
     * (it mirrors the remote PTY's DECCKM/DECKPAM via output replay).
     */
    fun termuxKey(
        keyCode: Int,
        keyMode: Int,
        cursorApp: Boolean,
        keypadApp: Boolean,
    ): String? {
        val numLockOn = keyMode and KEYMOD_NUM_LOCK != 0
        val mode = keyMode and KEYMOD_NUM_LOCK.inv()
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_CENTER -> return "\r"
            android.view.KeyEvent.KEYCODE_DPAD_UP ->
                return if (mode == 0) {
                    if (cursorApp) "\u001BOA" else "\u001B[A"
                } else {
                    transformForModifiers("\u001B[1", mode, 'A')
                }
            android.view.KeyEvent.KEYCODE_DPAD_DOWN ->
                return if (mode == 0) {
                    if (cursorApp) "\u001BOB" else "\u001B[B"
                } else {
                    transformForModifiers("\u001B[1", mode, 'B')
                }
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT ->
                return if (mode == 0) {
                    if (cursorApp) "\u001BOC" else "\u001B[C"
                } else {
                    transformForModifiers("\u001B[1", mode, 'C')
                }
            android.view.KeyEvent.KEYCODE_DPAD_LEFT ->
                return if (mode == 0) {
                    if (cursorApp) "\u001BOD" else "\u001B[D"
                } else {
                    transformForModifiers("\u001B[1", mode, 'D')
                }
            android.view.KeyEvent.KEYCODE_MOVE_HOME ->
                return if (mode == 0) {
                    if (cursorApp) "\u001BOH" else "\u001B[H"
                } else {
                    transformForModifiers("\u001B[1", mode, 'H')
                }
            android.view.KeyEvent.KEYCODE_MOVE_END ->
                return if (mode == 0) {
                    if (cursorApp) "\u001BOF" else "\u001B[F"
                } else {
                    transformForModifiers("\u001B[1", mode, 'F')
                }
            android.view.KeyEvent.KEYCODE_F1 ->
                return if (mode == 0) "\u001BOP" else transformForModifiers("\u001B[1", mode, 'P')
            android.view.KeyEvent.KEYCODE_F2 ->
                return if (mode == 0) "\u001BOQ" else transformForModifiers("\u001B[1", mode, 'Q')
            android.view.KeyEvent.KEYCODE_F3 ->
                return if (mode == 0) "\u001BOR" else transformForModifiers("\u001B[1", mode, 'R')
            android.view.KeyEvent.KEYCODE_F4 ->
                return if (mode == 0) "\u001BOS" else transformForModifiers("\u001B[1", mode, 'S')
            android.view.KeyEvent.KEYCODE_F5 -> return transformForModifiers("\u001B[15", mode, '~')
            android.view.KeyEvent.KEYCODE_F6 -> return transformForModifiers("\u001B[17", mode, '~')
            android.view.KeyEvent.KEYCODE_F7 -> return transformForModifiers("\u001B[18", mode, '~')
            android.view.KeyEvent.KEYCODE_F8 -> return transformForModifiers("\u001B[19", mode, '~')
            android.view.KeyEvent.KEYCODE_F9 -> return transformForModifiers("\u001B[20", mode, '~')
            android.view.KeyEvent.KEYCODE_F10 -> return transformForModifiers("\u001B[21", mode, '~')
            android.view.KeyEvent.KEYCODE_F11 -> return transformForModifiers("\u001B[23", mode, '~')
            android.view.KeyEvent.KEYCODE_F12 -> return transformForModifiers("\u001B[24", mode, '~')
            android.view.KeyEvent.KEYCODE_SYSRQ -> return "\u001B[32~"
            android.view.KeyEvent.KEYCODE_BREAK -> return "\u001B[34~"
            android.view.KeyEvent.KEYCODE_ESCAPE, android.view.KeyEvent.KEYCODE_BACK -> return "\u001B"
            android.view.KeyEvent.KEYCODE_INSERT -> return transformForModifiers("\u001B[2", mode, '~')
            android.view.KeyEvent.KEYCODE_FORWARD_DEL -> return transformForModifiers("\u001B[3", mode, '~')
            android.view.KeyEvent.KEYCODE_PAGE_UP -> return "\u001B[5~"
            android.view.KeyEvent.KEYCODE_PAGE_DOWN -> return "\u001B[6~"
            android.view.KeyEvent.KEYCODE_DEL -> {
                val prefix = if (mode and KEYMOD_ALT == 0) "" else "\u001B"
                return prefix + if (mode and KEYMOD_CTRL == 0) "\u007F" else "\u0008"
            }
            android.view.KeyEvent.KEYCODE_SPACE ->
                return if (mode and KEYMOD_CTRL == 0) null else "\u0000"
            android.view.KeyEvent.KEYCODE_TAB ->
                return if (mode and KEYMOD_SHIFT == 0) "\u0009" else "\u001B[Z"
            android.view.KeyEvent.KEYCODE_ENTER ->
                return if (mode and KEYMOD_ALT == 0) "\r" else "\u001B\r"
            android.view.KeyEvent.KEYCODE_NUMPAD_ENTER ->
                return if (keypadApp) transformForModifiers("\u001BO", mode, 'M') else "\n"
            android.view.KeyEvent.KEYCODE_NUMPAD_MULTIPLY ->
                return if (keypadApp) transformForModifiers("\u001BO", mode, 'j') else "*"
            android.view.KeyEvent.KEYCODE_NUMPAD_ADD ->
                return if (keypadApp) transformForModifiers("\u001BO", mode, 'k') else "+"
            android.view.KeyEvent.KEYCODE_NUMPAD_COMMA -> return ","
            android.view.KeyEvent.KEYCODE_NUMPAD_DOT ->
                return if (numLockOn) {
                    if (keypadApp) "\u001BOn" else "."
                } else {
                    transformForModifiers("\u001B[3", mode, '~')
                }
            android.view.KeyEvent.KEYCODE_NUMPAD_SUBTRACT ->
                return if (keypadApp) transformForModifiers("\u001BO", mode, 'm') else "-"
            android.view.KeyEvent.KEYCODE_NUMPAD_DIVIDE ->
                return if (keypadApp) transformForModifiers("\u001BO", mode, 'o') else "/"
            android.view.KeyEvent.KEYCODE_NUMPAD_0 ->
                return if (numLockOn) {
                    if (keypadApp) transformForModifiers("\u001BO", mode, 'p') else "0"
                } else {
                    transformForModifiers("\u001B[2", mode, '~')
                }
            android.view.KeyEvent.KEYCODE_NUMPAD_1 ->
                return if (numLockOn) {
                    if (keypadApp) transformForModifiers("\u001BO", mode, 'q') else "1"
                } else {
                    if (mode == 0) {
                        if (cursorApp) "\u001BOF" else "\u001B[F"
                    } else {
                        transformForModifiers("\u001B[1", mode, 'F')
                    }
                }
            android.view.KeyEvent.KEYCODE_NUMPAD_2 ->
                return if (numLockOn) {
                    if (keypadApp) transformForModifiers("\u001BO", mode, 'r') else "2"
                } else {
                    if (mode == 0) {
                        if (cursorApp) "\u001BOB" else "\u001B[B"
                    } else {
                        transformForModifiers("\u001B[1", mode, 'B')
                    }
                }
            android.view.KeyEvent.KEYCODE_NUMPAD_3 ->
                return if (numLockOn) {
                    if (keypadApp) transformForModifiers("\u001BO", mode, 's') else "3"
                } else {
                    "\u001B[6~"
                }
            android.view.KeyEvent.KEYCODE_NUMPAD_4 ->
                return if (numLockOn) {
                    if (keypadApp) transformForModifiers("\u001BO", mode, 't') else "4"
                } else {
                    if (mode == 0) {
                        if (cursorApp) "\u001BOD" else "\u001B[D"
                    } else {
                        transformForModifiers("\u001B[1", mode, 'D')
                    }
                }
            android.view.KeyEvent.KEYCODE_NUMPAD_5 ->
                return if (keypadApp) transformForModifiers("\u001BO", mode, 'u') else "5"
            android.view.KeyEvent.KEYCODE_NUMPAD_6 ->
                return if (numLockOn) {
                    if (keypadApp) transformForModifiers("\u001BO", mode, 'v') else "6"
                } else {
                    if (mode == 0) {
                        if (cursorApp) "\u001BOC" else "\u001B[C"
                    } else {
                        transformForModifiers("\u001B[1", mode, 'C')
                    }
                }
            android.view.KeyEvent.KEYCODE_NUMPAD_7 ->
                return if (numLockOn) {
                    if (keypadApp) transformForModifiers("\u001BO", mode, 'w') else "7"
                } else {
                    if (mode == 0) {
                        if (cursorApp) "\u001BOH" else "\u001B[H"
                    } else {
                        transformForModifiers("\u001B[1", mode, 'H')
                    }
                }
            android.view.KeyEvent.KEYCODE_NUMPAD_8 ->
                return if (numLockOn) {
                    if (keypadApp) transformForModifiers("\u001BO", mode, 'x') else "8"
                } else {
                    if (mode == 0) {
                        if (cursorApp) "\u001BOA" else "\u001B[A"
                    } else {
                        transformForModifiers("\u001B[1", mode, 'A')
                    }
                }
            android.view.KeyEvent.KEYCODE_NUMPAD_9 ->
                return if (numLockOn) {
                    if (keypadApp) transformForModifiers("\u001BO", mode, 'y') else "9"
                } else {
                    "\u001B[5~"
                }
            android.view.KeyEvent.KEYCODE_NUMPAD_EQUALS ->
                return if (keypadApp) transformForModifiers("\u001BO", mode, 'X') else "="
        }
        return null
    }

    private fun transformForModifiers(start: String, keymod: Int, lastChar: Char): String {
        val modifier = when (keymod) {
            KEYMOD_SHIFT -> 2
            KEYMOD_ALT -> 3
            KEYMOD_SHIFT or KEYMOD_ALT -> 4
            KEYMOD_CTRL -> 5
            KEYMOD_SHIFT or KEYMOD_CTRL -> 6
            KEYMOD_ALT or KEYMOD_CTRL -> 7
            KEYMOD_SHIFT or KEYMOD_ALT or KEYMOD_CTRL -> 8
            else -> return start + lastChar
        }
        return start + (";" + modifier) + lastChar
    }

    /**
     * Termux `inputCodePoint` transform for hardware-key unicode: full CTRL
     * table, bluetooth-keyboard quirks, left-ALT ESC prefix. Soft commits
     * never reach here (the VM applies the sticky latch to those instead —
     * routing both through here would double-apply).
     */
    fun termuxCodePoint(codePoint: Int, ctrlDown: Boolean, altDown: Boolean): ByteArray {
        var cp = codePoint
        if (ctrlDown) {
            cp = when {
                cp in 'a'.code..'z'.code -> cp - 'a'.code + 1
                cp in 'A'.code..'Z'.code -> cp - 'A'.code + 1
                cp == ' '.code || cp == '2'.code -> 0
                cp == '['.code || cp == '3'.code -> 27
                cp == '\\'.code || cp == '4'.code -> 28
                cp == ']'.code || cp == '5'.code -> 29
                cp == '^'.code || cp == '6'.code -> 30
                cp == '_'.code || cp == '7'.code || cp == '/'.code -> 31
                cp == '8'.code -> 127
                else -> cp
            }
        }
        // Bluetooth keyboards sending modifier-letter variants instead of ASCII.
        when (cp) {
            0x02DC -> cp = 0x007E
            0x02CB -> cp = 0x0060
            0x02C6 -> cp = 0x005E
        }
        val text = String(Character.toChars(cp))
        val bytes = text.toByteArray(Charsets.UTF_8)
        return if (altDown) byteArrayOf(ESC) + bytes else bytes
    }

    /**
     * Termux `paste()` normalization for atomic multi-char commits (CJK panel):
     * strip ESC + C1 controls (escape injection), map every newline to CR
     * (what Enter sends; LF would Ctrl-J in TUIs instead of submitting).
     *
     * Deliberately NOT the full Termux paste: no 2004 bracket wrap. The commit
     * field is single-line and the direct path is per-char (Termux typing,
     * never bracketed there either), so the remote's 2004 state is untracked
     * by design — bracketing would need pump-output parsing for a case that
     * barely exists. Shells treat the result identically to before (CR and LF
     * both accept-line); TUIs get the safer CR.
     */
    fun pasteNormalize(text: String): String {
        val stripped = text.filter { c -> c != '\u001B' && (c.code < 0x80 || c.code > 0x9F) }
        return stripped.replace(Regex("\r?\n"), "\r")
    }

    const val BRACKETED_PASTE_START = "\u001B[200~"
    const val BRACKETED_PASTE_END = "\u001B[201~"

    /**
     * Wrap [normalized] (already [pasteNormalize]d) in the 2004 bracketed-paste
     * markers, exactly like Termux `TerminalEmulator.paste()` when the app set
     * the mode. Agents (OMP/codex) and TUIs use the markers to accept the
     * block as one paste (multiline lands in the input box); plain shells
     * ignore unknown CSI so the markers degrade to nothing visible... except
     * when the app did NOT request the mode and echoes raw — then a stray
     * `m` is impossible (these markers end in `~`, not `m`), but callers must
     * only bracket when the app opted in. See [isBracketedPasteMode].
     */
    fun bracketedPaste(normalized: String): String =
        BRACKETED_PASTE_START + normalized + BRACKETED_PASTE_END

    /**
     * True when the attached emulator has DECSET 2004 (bracketed paste) active.
     * Mirrors Termux's own `paste()` gate (`isDecsetInternalBitSet(1024)`):
     * there is no public accessor, so this reads `mCurrentDecSetFlags`
     * reflectively (same pattern as the mEmulator injection in
     * RemoteTermuxSession; pinned to terminal-emulator v0.118.3).
     * Falls back to false (plain paste) on any reflection failure.
     */
    fun isBracketedPasteMode(emulator: Any?): Boolean {
        if (emulator == null) return false
        return try {
            val field = emulator.javaClass.getDeclaredField("mCurrentDecSetFlags").apply {
                isAccessible = true
            }
            field.getInt(emulator) and 1024 != 0
        } catch (_: Exception) {
            false
        }
    }
}
