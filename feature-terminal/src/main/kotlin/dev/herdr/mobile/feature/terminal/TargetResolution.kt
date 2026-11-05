package dev.herdr.mobile.feature.terminal

import dev.herdr.mobile.core.designsystem.UiMessage
import dev.herdr.mobile.core.model.SessionSnapshot

/**
 * Pick the pane a deep link (or a fresh screen) should open.
 *
 * Every fallback step is deliberate: a missing workspace opens the first
 * workspace, a tab id from another workspace is ignored, and the pane must
 * still belong to the resolved tab. Deep links that land somewhere else are
 * reported by [fallbackNotice] instead of silently opening a neighbour.
 */
internal fun resolveTarget(
    snapshot: SessionSnapshot,
    workspaceId: String?,
    tabId: String?,
    paneId: String?,
): TerminalTarget? {
    val workspace = snapshot.workspace(workspaceId)
        ?: snapshot.workspaces.firstOrNull()
        ?: return null
    val tab = snapshot.tab(tabId?.takeIf { snapshot.tab(it)?.workspaceId == workspace.id })
        ?: workspace.tabs.firstOrNull { it.id == workspace.activeTabId }
        ?: workspace.tabs.firstOrNull()
        ?: return null
    val pane = paneId?.let { id -> tab.panes.firstOrNull { it.id == id } }
        ?: tab.activePane ?: return null
    return TerminalTarget(workspace.id, tab.id, pane.id)
}

/**
 * The warning to post when a deep link opened something other than what it
 * named. In-app navigation has no expectation to violate, so a null
 * [initialPaneId] (and matching ids) yield null.
 */
internal fun fallbackNotice(
    initialWorkspaceId: String?,
    initialTabId: String?,
    initialPaneId: String?,
    snapshot: SessionSnapshot,
    resolved: TerminalTarget,
): UiMessage.OpenedInstead? {
    if (initialPaneId == null) return null
    return when {
        resolved.paneId != initialPaneId -> {
            val pane = snapshot.pane(resolved.paneId)
            UiMessage.OpenedInstead(
                UiMessage.OpenedInstead.TargetKind.PANE,
                pane?.displayLabel ?: resolved.paneId,
            )
        }
        initialTabId != null && resolved.tabId != initialTabId ->
            UiMessage.OpenedInstead(UiMessage.OpenedInstead.TargetKind.TAB, null)
        initialWorkspaceId != null && resolved.workspaceId != initialWorkspaceId ->
            UiMessage.OpenedInstead(UiMessage.OpenedInstead.TargetKind.WORKSPACE, null)
        else -> null
    }
}
