package dev.herdr.mobile.feature.terminal

import dev.herdr.mobile.core.model.SessionSnapshot

/**
 * What the open target should become after a snapshot arrives.
 *
 * The open pane can disappear underneath the app: another client closes the
 * workspace (desktop TUI, `herdr` CLI), a tab is closed from a notification,
 * or the daemon restarts and replays a session that no longer holds it. The
 * screen used to stay attached to the dead id and render "Detached (closed)"
 * with a live workspace list behind it.
 *
 * Kept pure so the policy — keep / retarget / clear — is testable without a
 * device, a daemon or a ViewModel.
 */
sealed interface TargetReconcile {
    /** The target still exists; nothing to do. */
    data object Keep : TargetReconcile

    /** The target is gone and [next] is a live pane to move to. */
    data class Retarget(val next: TerminalTarget) : TargetReconcile

    /** The whole session drained: drop the target and let Home take over. */
    data object Clear : TargetReconcile
}

/**
 * Resolve [target] against [snapshot]. A target is alive only when its pane is
 * still in the same workspace *and* tab — ids are opaque, so a reused id in a
 * different workspace must not be treated as the same session.
 */
fun reconcileTarget(snapshot: SessionSnapshot, target: TerminalTarget): TargetReconcile {
    val pane = snapshot.pane(target.paneId)
    if (pane != null && pane.workspaceId == target.workspaceId && pane.tabId == target.tabId) {
        return TargetReconcile.Keep
    }
    val survivor = snapshot.workspaces.asSequence()
        .mapNotNull { workspace ->
            val tab = workspace.tabs.firstOrNull { it.id == workspace.activeTabId }
                ?: workspace.tabs.firstOrNull()
                ?: return@mapNotNull null
            val pane = tab.activePane ?: return@mapNotNull null
            TerminalTarget(workspace.id, tab.id, pane.id)
        }
        .firstOrNull()
    return if (survivor == null) TargetReconcile.Clear else TargetReconcile.Retarget(survivor)
}
