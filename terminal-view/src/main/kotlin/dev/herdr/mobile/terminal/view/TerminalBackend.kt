package dev.herdr.mobile.terminal.view

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Remote backend feeding one terminal view, in the plan §9.2 sense.
 *
 * Bytes stay bytes: binary traffic is never decoded above this interface, and control
 * records ([resize], [scroll], [release]) are explicit calls rather than in-band escapes.
 * The OkHttp implementation lives in `:core-network` and is adapted to this interface by
 * the Terminal screen; the view never imports the network module.
 */
interface TerminalBackend {

    val state: StateFlow<BackendState>

    /** Raw ANSI bytes from the remote pane, in arrival order. */
    val bytes: Flow<ByteArray>

    /** Answers to DA/DSR/OSC queries produced by the emulator. */
    suspend fun send(data: ByteArray)

    /** Keystrokes as literal text. */
    suspend fun sendText(text: String)

    suspend fun resize(cols: Int, rows: Int)

    /** Mouse event for mouse-mode apps (vim, less, tmux mouse). */
    suspend fun mouse(action: String, button: String, column: Int, row: Int)

    suspend fun release()
}

sealed interface BackendState {
    data object Idle : BackendState
    data class Attaching(val paneId: String) : BackendState
    data class Attached(val paneId: String, val cols: Int, val rows: Int) : BackendState
    data class Detached(val reason: String) : BackendState
    data class Failed(val code: String, val message: String) : BackendState
}
