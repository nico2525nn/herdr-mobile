package dev.herdr.mobile

import android.content.Context
import dev.herdr.mobile.core.model.AgentStatus
import dev.herdr.mobile.core.model.AppSettings
import dev.herdr.mobile.core.model.SemanticEvent
import dev.herdr.mobile.notifications.HerdrNotifications
import kotlinx.coroutines.flow.StateFlow

/** The settings surface the notification relay needs; fakeable in JVM tests. */
internal interface NotificationSettings {
    val settings: StateFlow<AppSettings>
    val storageHealthy: Boolean
    suspend fun loadNotified(): Map<String, String>
    suspend fun saveNotified(map: Map<String, String>)
}

/** Notification side effects, separated from the ordering state machine. */
internal interface AlertNotifier {
    fun notify(event: SemanticEvent, workspaceLabel: String?, tabLabel: String?)
    fun dismissPane(workspaceId: String?, paneId: String?)
    fun dismissPaneStatus(workspaceId: String?, paneId: String?, status: AgentStatus)
}

internal class AndroidAlertNotifier(private val context: Context) : AlertNotifier {
    override fun notify(
        event: SemanticEvent,
        workspaceLabel: String?,
        tabLabel: String?,
    ) = HerdrNotifications.notify(context, event, workspaceLabel, tabLabel)

    override fun dismissPane(workspaceId: String?, paneId: String?) =
        HerdrNotifications.dismissPane(context, workspaceId, paneId)

    override fun dismissPaneStatus(
        workspaceId: String?,
        paneId: String?,
        status: AgentStatus,
    ) = HerdrNotifications.dismissPaneStatus(context, workspaceId, paneId, status)
}
