package dev.herdr.mobile.core.model

/**
 * Where the UI is, for suppressing alerts about the pane the user is already
 * looking at. Written by the app/terminal screen, read by the notification
 * relay; no Android types so both modules can share it.
 */
object UiFocusState {
    @Volatile var appForeground: Boolean = false
    @Volatile var terminalVisible: Boolean = false
    @Volatile var paneId: String? = null

    fun isShowing(paneId: String?): Boolean =
        appForeground && terminalVisible && paneId != null && this.paneId == paneId
}
