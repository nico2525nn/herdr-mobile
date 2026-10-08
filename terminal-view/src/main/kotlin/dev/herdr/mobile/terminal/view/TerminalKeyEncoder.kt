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
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Key) return false
            return label == other.label
        }

        override fun hashCode(): Int = label.hashCode()
    }

    enum class Modifier { CTRL, ALT }

    private fun esc(seq: String): ByteArray = byteArrayOf(ESC) + seq.toByteArray(Charsets.US_ASCII)

    /** The Termux default layout, two rows (8 + 7). */
    val DEFAULT_LAYOUT: List<List<Key>> = listOf(
        listOf(
            Key("ESC", bytes = byteArrayOf(ESC)),
            Key("⏎", bytes = byteArrayOf(0x0D)),
            Key("/", bytes = "/".toByteArray()),
            Key("-", bytes = "-".toByteArray()),
            Key("HOME", bytes = esc("[H")),
            Key("↑", bytes = esc("[A")),
            Key("END", bytes = esc("[F")),
            Key("PGUP", bytes = esc("[5~")),
        ),
        listOf(
            Key("TAB", bytes = byteArrayOf(0x09)),
            Key("CTRL", modifier = Modifier.CTRL),
            Key("ALT", modifier = Modifier.ALT),
            Key("←", bytes = esc("[D")),
            Key("↓", bytes = esc("[B")),
            Key("→", bytes = esc("[C")),
            Key("PGDN", bytes = esc("[6~")),
        ),
    )

    /**
     * Letter row for Ctrl chords (Ctrl-C / Ctrl-D / …). The two-row panel has no
     * letters, which made the CTRL latch a dead end; hosts that want Ctrl chords
     * append this row. Letters send as-is; with a latched CTRL they become 0x01…
     * via [withModifier], with ALT they become ESC-prefixed.
     */
    val LETTER_ROW: List<Key> = listOf(
        Key("A", bytes = "a".toByteArray()),
        Key("C", bytes = "c".toByteArray()),
        Key("D", bytes = "d".toByteArray()),
        Key("G", bytes = "g".toByteArray()),
        Key("L", bytes = "l".toByteArray()),
        Key("R", bytes = "r".toByteArray()),
        Key("Z", bytes = "z".toByteArray()),
    )

    /**
     * Apply a latched [modifier] to a printable [key]: `ctrl+c` → 0x03, `alt+x` → ESC x.
     * Non-printable keys ignore the modifier and are sent as-is.
     *
     * Returns null when the combination is meaningless (e.g. CTRL+TAB): the
     * caller keeps the latch instead of silently swallowing it. Keys carrying
     * only [Key.herdrKeys] also return null — no consumer implements that path
     * yet, so sending empty bytes would be a silent no-op.
     */
    fun withModifier(modifier: Modifier?, key: Key): ByteArray? {
        val base = key.bytes ?: return null
        if (modifier == null || base.size != 1) return base
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
        }
    }

    /**
     * Apply [modifiers] to one committed [text] chunk from the soft keyboard.
     * Single ASCII char: CTRL maps to 0x00–0x1F, ALT prefixes ESC (both stack:
     * Ctrl+Alt+X → ESC 0x18). Multi-char or non-ASCII text returns null — an
     * atomic paste must never be half-modified; the caller sends it verbatim
     * and keeps the latch armed.
     */
    fun withModifiers(modifiers: Set<Modifier>, text: String): ByteArray? {
        if (modifiers.isEmpty() || text.length != 1) return null
        val byte = text[0].code
        if (byte !in 0x20..0x7E) return null
        var out = byteArrayOf(byte.toByte())
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
}
