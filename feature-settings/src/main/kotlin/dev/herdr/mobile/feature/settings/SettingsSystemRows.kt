package dev.herdr.mobile.feature.settings

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.herdr.mobile.core.designsystem.SettingsRow

/**
 * System grant state (M7): the Done/Blocked toggles are meaningless when the OS
 * denies notifications — say so with a link to system settings instead of
 * silently dropping every alert. Re-checked on every resume (the user may have
 * just flipped the system switch and come back).
 */
@Composable
internal fun NotificationGrantRow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var granted by remember { mutableStateOf(true) }
    fun refresh() {
        granted = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Initial read (before any resume fires for this composition).
    androidx.compose.runtime.LaunchedEffect(Unit) { refresh() }
    if (!granted) {
        SettingsRow(
            headline = "System notifications off",
            supporting = "Alerts are blocked by Android — tap to open system settings",
            showDivider = true,
            trailing = {
                TextButton(onClick = {
                    val intent = android.content.Intent().apply {
                        action = android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
                        putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                    }
                    context.startActivity(intent)
                }) {
                    Text("Open")
                }
            },
        )
    }
}

/**
 * Doze guidance (B2): Android suspends SSE/SSH network in Doze unless the user
 * exempts the app — there is no FCM fallback (self-hosted daemon), so without
 * this overnight alerts silently stop. States the current exemption with a
 * one-tap system dialog. Re-checked on resume like the grant row.
 */
@Composable
internal fun BatteryExemptionRow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var exempt by remember { mutableStateOf(true) }
    fun refresh() {
        val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        exempt = pm.isIgnoringBatteryOptimizations(context.packageName)
    }
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { refresh() }
    if (!exempt) {
        SettingsRow(
            headline = "Battery optimization on",
            supporting = "Doze pauses monitoring overnight — tap to exempt",
            showDivider = true,
            trailing = {
                TextButton(onClick = {
                    runCatching {
                        val intent = android.content.Intent().apply {
                            action = android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                            data = android.net.Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    }
                }) {
                    Text("Exempt")
                }
            },
        )
    }
}
