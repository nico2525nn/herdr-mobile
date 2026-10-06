package dev.herdr.mobile.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /v1/health` — cheap reachability probe used by Settings and the reconnect loop. */
@Serializable
data class HealthReport(
    val ok: Boolean = false,
    val revision: Long = 0,
    val seq: Long = 0,
    val uptimeMs: Long = 0,
    val protocol: Int = PROTOCOL,
    val herdr: HerdrInfo = HerdrInfo(),
    val herdrConnected: Boolean = false,
    val error: String? = null,
) {
    companion object {
        /** Wire protocol generation implemented by this client. */
        const val PROTOCOL = 1
    }
}

/**
 * Semantic hook receiver: `POST /v1/agent/report`.
 *
 * Agents that are not yet a Herdr integration can push their own state through this endpoint
 * instead of implementing `pane.report_agent` against the Herdr socket directly.
 */
@Serializable
data class AgentReport(
    @SerialName("paneId") val paneId: String,
    val status: AgentStatus = AgentStatus.UNKNOWN,
    val agent: String? = null,
    val message: String? = null,
    val source: String? = null,
)

/** `POST /v1/pane/{id}/input` — REST escape hatch for text input and interrupt. */
@Serializable
data class InputRequest(
    val encoding: String = "utf-8",
    val text: String,
)

/** `POST /v1/pane/{id}/resize` — set the pane geometry the terminal stream will report. */
@Serializable
data class ResizeRequest(
    val cols: Int,
    val rows: Int,
)