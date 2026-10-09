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
import androidx.core.app.NotificationCompat

/**
 * Keeps the process (and with it the daemon SSE + notification relay) alive after the
 * UI goes away, so done/blocked transitions alert while the user is elsewhere.
 *
 * There is no push server: without this service a dead process gets no alerts at all
 * (the relay's documented limitation, and the reported "通知来てない"). The service
 * itself holds no connection — [AppContainer] owns the client, tunnel, and relay in
 * process scope; the service only pins the process with a low-importance persistent
 * notification (Termux-style). Same-process access, no IPC, no duplicate client.
 *
 * Lifecycle: started when "Background monitoring" is on (HerdrApp boot path and the
 * Settings toggle), stopped when it is off. START_STICKY so a memory kill resumes
 * monitoring; BootReceiver restarts it after reboot.
 */
class HerdrMonitorService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        // startForeground FIRST: Android 14 kills a dataSync FGS that doesn't
        // promote within seconds. Container init never blocks (network runs
        // on its own scope), but promotion must not wait on it anyway.
        startForeground(
            MONITOR_ID,
            persistentNotification(this),
            // FOREGROUND_SERVICE_TYPE_DATA_SYNC on 29+: omitted on older APIs
            // where the constant doesn't exist.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
        // Touch the graph: client.start() + relay already ran in HerdrApp.onCreate
        // (same process). This only guarantees ordering after a sticky restart
        // where the service is recreated before any activity.
        (application as HerdrApp).container.client.kick()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-promote defensively: a sticky restart delivers a null intent and
        // onCreate already promoted, but a redundant notify is harmless while
        // a missing promotion is fatal (system kills the service).
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(MONITOR_ID, persistentNotification(this))
        return START_STICKY
    }

    companion object {
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

        fun persistentNotification(context: Context): android.app.Notification {
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
                .setContentText("Watching for agent updates")
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
