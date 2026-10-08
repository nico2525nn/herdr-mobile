package dev.herdr.mobile.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A single agent-bearing pane inside a Herdr tab.
 *
 * [terminalId] is the handle the daemon passes to `herdr terminal session control`; it is
 * the only stable way to attach a live byte stream to a pane.
 */
@Serializable
data class Pane(
    val id: String,
    val tabId: String,
    val workspaceId: String,
    val label: String? = null,
    val title: String? = null,
    val agent: String? = null,
    val status: AgentStatus = AgentStatus.UNKNOWN,
    val cwd: String? = null,
    val terminalId: String? = null,
    val focused: Boolean = false,
    val revision: Long = 0,
    val tokens: Map<String, String> = emptyMap(),
) {
    /** Best available human label: explicit label, then agent, then short id. */
    val displayLabel: String
        get() = label?.takeIf { it.isNotBlank() }
            ?: agent?.takeIf { it.isNotBlank() }
            ?: title?.takeIf { it.isNotBlank() }
            ?: id
}

/** A Herdr tab. Owns one or more [Pane]s. */
@Serializable
data class Tab(
    val id: String,
    val workspaceId: String,
    val label: String = "",
    val number: Int = 0,
    val status: AgentStatus = AgentStatus.UNKNOWN,
    val focused: Boolean = false,
    val panes: List<Pane> = emptyList(),
) {
    val displayLabel: String
        get() = label.takeIf { it.isNotBlank() } ?: "#$number"

    /**
     * Distinct harness names across panes (codex, omp, …), for the "label ·
     * harness" chip subtitle. Empty when no pane has a detected agent.
     */
    val harnessNames: List<String>
        get() = panes.mapNotNull { it.agent?.takeIf { a -> a.isNotBlank() } }.distinct()

    /** The pane that should receive keystrokes when this tab is opened. */
    val activePane: Pane?
        get() = panes.firstOrNull { it.focused } ?: panes.firstOrNull()
}

/**
 * A Herdr workspace: the unit shown as one card on Home.
 *
 * [path] is the concrete working directory and [home] the home directory the daemon used to
 * condense it, so the client can render `~/project` without guessing the user's home layout.
 */
@Serializable
data class Workspace(
    val id: String,
    val label: String,
    val path: String? = null,
    val home: String? = null,
    val number: Int = 0,
    val status: AgentStatus = AgentStatus.UNKNOWN,
    val focused: Boolean = false,
    val activeTabId: String? = null,
    val tabs: List<Tab> = emptyList(),
    val tokens: Map<String, String> = emptyMap(),
) {
    val displayPath: String?
        get() = PathDisplay.condense(path, home)
}

/** Where the Herdr client currently is, so a cold start can open the right terminal. */
@Serializable
data class Focus(
    @SerialName("workspaceId") val workspaceId: String? = null,
    @SerialName("tabId") val tabId: String? = null,
    @SerialName("paneId") val paneId: String? = null,
)

/** Herdr build metadata, surfaced in Settings so a protocol mismatch is diagnosable. */
@Serializable
data class HerdrInfo(
    val version: String? = null,
    val protocol: Int? = null,
    val binary: String? = null,
)

/**
 * Authoritative state for the whole session at a point in time.
 *
 * [seq] is the semantic event sequence number at the moment the snapshot was taken. A client
 * that reconnects and sees the first event with `seq > snapshot.seq + 1` has a gap and must
 * refetch; a client that receives `snapshot.seq + 1` onward can apply events directly.
 */
@Serializable
data class SessionSnapshot(
    val revision: Long,
    val seq: Long,
    val capturedAt: String? = null,
    val herdr: HerdrInfo = HerdrInfo(),
    val focus: Focus = Focus(),
    val workspaces: List<Workspace> = emptyList(),
) {
    val tabCount: Int get() = workspaces.sumOf { it.tabs.size }

    val paneCount: Int get() = workspaces.sumOf { w -> w.tabs.sumOf { it.panes.size } }

    fun workspace(id: String?): Workspace? = workspaces.firstOrNull { it.id == id }

    fun tab(id: String?): Tab? {
        if (id == null) return null
        return workspaces.firstNotNullOfOrNull { w -> w.tabs.firstOrNull { it.id == id } }
    }

    fun pane(id: String?): Pane? {
        if (id == null) return null
        return workspaces.firstNotNullOfOrNull { w ->
            w.tabs.firstNotNullOfOrNull { t -> t.panes.firstOrNull { it.id == id } }
        }
    }
}