package dev.herdr.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the process (and with it the daemon SSE + notification relay) alive after the
 * UI goes away, so done/blocked transitions alert while the user is elsewhere.
 *
 * There is no push server: without this service a dead process gets no alerts at all.
 * The service itself holds no connection — [AppContainer] owns the client, tunnel, and
 * relay in process scope; the service only pins the process with a persistent
 * notification (Termux-style). Same-process access, no IPC, no duplicate client.
 *
 * Type is specialUse, NOT dataSync: Android 15 caps background dataSync at 6h/24h and
 * kills the service at expiry (RemoteServiceException without onTimeout), which would
 * end overnight monitoring daily. specialUse has no time budget; the Play declaration
 * names agent monitoring. Promotion goes through ServiceCompat so API 26–28 (minSdk)
 * use the 2-arg overload — the 3-arg call crashes there with NoSuchMethodError.
 *
 * Lifecycle: started when "Background monitoring" is on (HerdrApp boot path and the
 * Settings toggle), stopped when it is off. START_STICKY so a memory kill resumes
 * monitoring; BootReceiver restarts it after reboot. The persistent notification
 * mirrors live link state — never a static "watching" claim over a dead link.
 */
class HerdrMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        // startForeground FIRST: Android 14 kills an FGS that doesn't promote
        // within seconds. Container init never blocks (network runs on its own
        // scope), but promotion must not wait on it anyway.
        ServiceCompat.startForeground(
            this,
            MONITOR_ID,
            persistentNotification(this, "Connecting…"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
        // Touch the graph: client.start() + relay already ran in HerdrApp.onCreate
        // (same process). This only guarantees ordering after a sticky restart
        // where the service is recreated before any activity.
        val container = (application as HerdrApp).container
        container.client.kick()
        // Live link state on the persistent notification: CONNECTED shows
        // quiet "watching" text, anything else names the condition. Distinct:
        // snapshot ticks must not re-post the notification every event.
        scope.launch {
            container.client.state
                .map { it.connection to (it.error ?: "") }
                .distinctUntilChanged()
                .collect { (connection, error) ->
                    val text = when (connection) {
                        dev.herdr.mobile.core.model.ConnectionState.CONNECTED ->
                            "Watching for agent updates"
                        dev.herdr.mobile.core.model.ConnectionState.FAILED ->
                            "Needs attention: ${error.ifBlank { "connection failed" }}"
                        else -> "Reconnecting…"
                    }
                    runCatching {
                        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                            .notify(MONITOR_ID, persistentNotification(this@HerdrMonitorService, text))
                    }.onFailure { Log.w(TAG, "persistent notify failed", it) }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-promote defensively: a sticky restart delivers a null intent and
        // onCreate already promoted, but a redundant notify is harmless while
        // a missing promotion is fatal (system kills the service).
        runCatching {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(MONITOR_ID, persistentNotification(this, lastText()))
        }.onFailure { Log.w(TAG, "re-promote notify failed", it) }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Best-effort current text for the re-promote path (no state read races). */
    private fun lastText(): String {
        val state = runCatching { (application as HerdrApp).container.client.state.value }.getOrNull()
        return when (state?.connection) {
            dev.herdr.mobile.core.model.ConnectionState.CONNECTED -> "Watching for agent updates"
            dev.herdr.mobile.core.model.ConnectionState.FAILED ->
                "Needs attention: ${(state.error ?: "").ifBlank { "connection failed" }}"
            dev.herdr.mobile.core.model.ConnectionState.IDLE,
            dev.herdr.mobile.core.model.ConnectionState.CONNECTING,
            null -> "Connecting…"
            else -> "Reconnecting…"
        }
    }

    companion object {
        private const val TAG = "HerdrMonitor"
        const val CHANNEL_MONITOR = "herdr_monitor"
        const val MONITOR_ID = 41

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_MONITOR,
                    "Background monitoring",
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    description = "Persistent status while watching for agent updates"
                },
            )
        }

        fun persistentNotification(context: Context, text: String): android.app.Notification {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val pending = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, CHANNEL_MONITOR)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Herdr monitoring")
                .setContentText(text)
                .setContentIntent(pending)
                .setOngoing(true)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .build()
        }

        fun start(context: Context) {
            val intent = Intent(context, HerdrMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HerdrMonitorService::class.java))
        }
    }
}
