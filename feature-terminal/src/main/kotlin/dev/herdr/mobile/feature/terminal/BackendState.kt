package dev.herdr.mobile.feature.terminal

/**
 * Attachment lifecycle as the terminal screen sees it.
 *
 * Owned here (not by the emulator library): the screen gates rendering and the
 * retry rules on these states, and the engine is just one producer.
 */
sealed interface BackendState {
    data object Idle : BackendState
    data class Attaching(val paneId: String) : BackendState
    data class Attached(
        val paneId: String,
        val cols: Int,
        val rows: Int,
        val historyRows: Int = 0,
        val historyTruncated: Boolean = false,
        val historyError: String? = null,
        val visibleOk: Boolean = true,
    ) : BackendState
    data class Detached(val reason: String) : BackendState
    data class Failed(val code: String, val message: String) : BackendState
}
