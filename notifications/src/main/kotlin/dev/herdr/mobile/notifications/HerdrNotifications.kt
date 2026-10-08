package dev.herdr.mobile.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.herdr.mobile.core.model.AgentStatus
import dev.herdr.mobile.core.model.SemanticEvent

/**
 * Semantic notifications: done, blocked/permission-required, and failed — never progress,
 * never per-line output, never heartbeats.
 *
 * The tap target is a deep link the app resolves on foreground: refresh the snapshot, then
 * open the workspace/tab and attach only if needed.
 */
object HerdrNotifications {

    const val CHANNEL_AGENTS = "herdr_agents"
    const val EXTRA_WORKSPACE_ID = "dev.herdr.mobile.extra.WORKSPACE_ID"
    const val EXTRA_TAB_ID = "dev.herdr.mobile.extra.TAB_ID"
    const val EXTRA_PANE_ID = "dev.herdr.mobile.extra.PANE_ID"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_AGENTS,
                "Agent alerts",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Done, blocked and failed agent transitions"
            },
        )
    }

    /**
     * Returns true when [event] passes both the type filter and the user's toggles.
     * DONE and BLOCKED only: Herdr (0.9.1–0.9.3) emits no failure status or
     * failure detail (no message/reason fields on status events), so a FAILED
     * branch would be dead code. FAILED stays in the enum for wire compat.
     */
    fun shouldNotify(
        event: SemanticEvent,
        notifyDone: Boolean,
        notifyBlocked: Boolean,
    ): Boolean {
        if (!event.isAlertWorthy) return false
        return when (event.status) {
            AgentStatus.DONE -> notifyDone
            AgentStatus.BLOCKED -> notifyBlocked
            else -> false
        }
    }

    fun notify(
        context: Context,
        event: SemanticEvent,
        workspaceLabel: String?,
        tabLabel: String?,
    ) {
        val status = event.status ?: return
        val title = when (status) {
            AgentStatus.DONE -> "Done${workspaceLabel?.let { " · $it" } ?: ""}"
            AgentStatus.BLOCKED -> "Blocked${workspaceLabel?.let { " · $it" } ?: ""}"
            else -> return
        }
        val body = tabLabel?.takeIf { it.isNotBlank() } ?: status.name.lowercase()

        val intent = Intent(Intent.ACTION_VIEW).apply {
            // Explicit component: resolves without any intent-filter (the
            // manifest deliberately has no dataless VIEW filter — it would
            // match every implicit VIEW in the system). Package-only explicit
            // intents still need a filter and fail with ACTIVITY_NOT_FOUND.
            // NOTE: literal class package, not context.packageName: the debug
            // build's applicationId (dev.herdr.mobile.debug) differs from the
            // class package (dev.herdr.mobile), and deriving from packageName
            // fails with CLASS_NOT_FOUND on debug builds.
            setClassName(context.packageName, "dev.herdr.mobile.MainActivity")
            putExtra(EXTRA_WORKSPACE_ID, event.workspaceId)
            putExtra(EXTRA_TAB_ID, event.tabId)
            putExtra(EXTRA_PANE_ID, event.paneId)
        }
        val pending = PendingIntent.getActivity(
            context,
            notificationId(event),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_AGENTS)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            // IDs only, never terminal output: private even with the body above.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId(event), notification)
    }

    fun notificationId(event: SemanticEvent): Int {
        val key = "${event.workspaceId}/${event.tabId}/${event.paneId}/${event.status}"
        return key.hashCode()
    }
}
