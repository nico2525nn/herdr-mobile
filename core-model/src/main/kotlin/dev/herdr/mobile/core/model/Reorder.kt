package dev.herdr.mobile.core.model

/**
 * Herdr list-move index math, shared by tab and workspace reorder.
 *
 * `insert_index` counts against the list WITH the item still in it, and the
 * item is extracted before inserting: moving DOWN (from < to) therefore lands
 * one slot above the requested index. Verified live against the sandbox:
 * `[w3,w1,w2]` + move w3 to 2 → `[w1,w3,w2]` (position 1, not 2); moving UP
 * lands exactly. To place an item at visual position [to], request [to]+1 when
 * moving down, [to] otherwise. Past-the-end ([size]) is accepted and means end.
 */
object Reorder {
    fun insertIndex(from: Int, to: Int, size: Int): Long {
        val clampedTo = to.coerceIn(0, (size - 1).coerceAtLeast(0))
        val fromClamped = from.coerceIn(0, (size - 1).coerceAtLeast(0))
        if (fromClamped == clampedTo) return clampedTo.toLong()
        return if (fromClamped < clampedTo) (clampedTo + 1).toLong() else clampedTo.toLong()
    }
}
