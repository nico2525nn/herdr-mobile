package dev.herdr.mobile.feature.terminal

/**
 * Input typed while no backend is attached (the attach/reconnect window).
 *
 * Queued instead of dropped, bounded so a dead stream cannot grow it without
 * limit; the oldest entry is discarded first. All operations are internally
 * synchronised because [send] may arrive from any coroutine while the attach
 * coroutine drains on the main dispatcher.
 */
internal class PendingInput(private val maxSize: Int = MAX_PENDING_INPUT) {

    private val ops = ArrayDeque<suspend (ConnectionTerminalBackend) -> Unit>(maxSize)

    fun enqueue(op: suspend (ConnectionTerminalBackend) -> Unit) {
        synchronized(ops) {
            if (ops.size >= maxSize) ops.removeFirst()
            ops.addLast(op)
        }
    }

    /** Atomically takes everything queued. */
    fun drain(): List<suspend (ConnectionTerminalBackend) -> Unit> = synchronized(ops) {
        val list = ops.toList()
        ops.clear()
        list
    }

    fun clear() {
        synchronized(ops) { ops.clear() }
    }
}

internal const val MAX_PENDING_INPUT = 64
