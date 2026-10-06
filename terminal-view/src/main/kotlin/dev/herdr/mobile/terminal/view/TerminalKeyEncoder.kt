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

    /** The Termux default layout, two rows of seven. */
    val DEFAULT_LAYOUT: List<List<Key>> = listOf(
        listOf(
            Key("ESC", bytes = byteArrayOf(ESC)),
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
     * Apply a latched [modifier] to a printable [key]: `ctrl+c` → 0x03, `alt+x` → ESC x.
     * Non-printable keys ignore the modifier and are sent as-is.
     */
    fun withModifier(modifier: Modifier?, key: Key): ByteArray {
        val base = key.bytes ?: return byteArrayOf()
        if (modifier == null || base.size != 1) return base
        val byte = base[0].toInt() and 0xFF
        return when (modifier) {
            Modifier.CTRL -> {
                val lower = byte or 0x20
                if (lower in 0x61..0x7A || byte in 0x40..0x5F) {
                    byteArrayOf((byte and 0x1F).toByte())
                } else {
                    base
                }
            }

            Modifier.ALT -> byteArrayOf(ESC, base[0])
        }
    }

    /** CTRL-C / interrupt as raw byte, the fastest path that needs no round trip. */
    fun interrupt(): ByteArray = byteArrayOf(0x03)
}
