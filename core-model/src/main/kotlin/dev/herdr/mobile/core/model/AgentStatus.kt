package dev.herdr.mobile.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Semantic state of a coding agent, a tab, or a workspace.
 *
 * Herdr is the source of truth for these values; the daemon forwards them verbatim and the
 * client only rolls them up. [priority] is deliberately an explicit number rather than the
 * enum ordinal so new states (for example a future `failed`) can be inserted into the roll-up
 * without silently changing the ordering of the existing ones.
 */
@Serializable
enum class AgentStatus(val wire: String, val priority: Int) {
    @SerialName("blocked")
    BLOCKED("blocked", 500),

    @SerialName("working")
    WORKING("working", 400),

    @SerialName("done")
    DONE("done", 300),

    @SerialName("idle")
    IDLE("idle", 200),

    @SerialName("unknown")
    UNKNOWN("unknown", 100);

    companion object {
        fun fromWire(value: String?): AgentStatus =
            entries.firstOrNull { it.wire == value } ?: UNKNOWN

        /**
         * Representative status for a parent built from its children.
         * Returns `null` when there is nothing to roll up.
         */
        fun rollUp(statuses: Iterable<AgentStatus>): AgentStatus? =
            statuses.maxByOrNull { it.priority }
    }
}