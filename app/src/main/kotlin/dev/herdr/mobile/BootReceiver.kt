package dev.herdr.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Restarts background monitoring after reboot when the toggle is on. Reads the toggle
 * straight from the repository (no activity needed); process death before the read
 * completes just skips one boot — the next app launch starts the service anyway.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as HerdrApp
                val on = app.container.settingsRepository.settings.first().backgroundMonitoring
                if (on) HerdrMonitorService.start(context.applicationContext)
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }
}
