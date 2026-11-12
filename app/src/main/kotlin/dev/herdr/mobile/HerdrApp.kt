package dev.herdr.mobile

import android.app.Application
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class HerdrApp : Application() {

    lateinit var container: AppContainer
        private set

    /** Activities in started state (visible). Drives link stop on monitor-OFF. */
    @Volatile var foregroundActivities = 0
        private set

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: android.app.Activity) {
                foregroundActivities++
                dev.herdr.mobile.core.model.UiFocusState.appForeground = true
                // Retry a rejected FGS start on every foreground entry; the
                // service is idempotent while running.
                runCatching {
                    if (container.settingsRepository.settings.value.backgroundMonitoring) {
                        HerdrMonitorService.start(this@HerdrApp)
                    } else {
                        // Monitoring off stops the link when the last activity
                        // leaves; without this, rotation/return left Home on a
                        // dead stale cache forever. kick() is a no-op while
                        // CONNECTED; start() clears the explicit-stop intent.
                        if (container.client.state.value.connection ==
                            dev.herdr.mobile.core.model.ConnectionState.CONNECTED
                        ) {
                            container.client.kick()
                        } else {
                            // Covers IDLE after the explicit stop and a stop
                            // still in flight (CONNECTING/RECONNECTING).
                            container.client.start()
                        }
                    }
                }
            }

            override fun onActivityStopped(a: android.app.Activity) {
                foregroundActivities--
                if (foregroundActivities <= 0) {
                    dev.herdr.mobile.core.model.UiFocusState.appForeground = false
                    // Monitoring OFF must end SSE/tunnel work when the last
                    // activity leaves; the toggle alone only ran while the UI
                    // was visible, so a later Home left the link up forever.
                    runCatching {
                        if (!container.settingsRepository.settings.value.backgroundMonitoring) {
                            container.client.stop()
                        }
                    }
                }
            }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
        container = AppContainer(this)
        // The toggle owns the service: every launch reconciles (covers
        // first install with default-on), every change re-reconciles.
        // Distinct: stop+start churn on unrelated settings edits re-posts
        // the persistent notification for no reason.
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
        ).launch {
            // Await the persisted toggle: the StateFlow emits defaults (ON)
            // instantly, which would flash-start the service on every cold
            // start for users who turned monitoring OFF.
            container.settingsRepository.awaitLoaded()
            container.settingsRepository.settings
                .map { it.backgroundMonitoring }
                .distinctUntilChanged()
                .collect { on ->
                    // Never let a start/stop failure kill this collector: an
                    // uncaught ForegroundServiceStartNotAllowedException (sticky
                    // restart while backgrounded, exhausted quota) would crash
                    // the process into a crash loop AND permanently stop toggle
                    // reconciliation. Class-name check: the exception class only
                    // exists on API 31+; referencing it here would need gating.
                    runCatching {
                        if (on) {
                            HerdrMonitorService.start(this@HerdrApp)
                            container.client.start()
                        } else {
                            HerdrMonitorService.stop(this@HerdrApp)
                            // OFF must actually end background work: stopping
                            // the service alone leaves SSE+SSH+relay running in
                            // the cached process (battery + phantom alerts).
                            // Keep the link only while UI is visible (the user
                            // is looking at live dots); else stop it. The relay
                            // is additionally gated on the toggle (belt +
                            // suspenders for the cached-process window).
                            if (foregroundActivities == 0) container.client.stop()
                        }
                    }.onFailure { e ->
                        android.util.Log.w(
                            "HerdrApp",
                            "monitor ${if (on) "start" else "stop"} failed (${e.javaClass.simpleName}), will retry on next change/foreground",
                            e,
                        )
                    }
                }
        }
    }
}
