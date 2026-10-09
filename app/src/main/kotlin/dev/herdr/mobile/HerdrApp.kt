package dev.herdr.mobile

import android.app.Application
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class HerdrApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // The toggle owns the service: every launch reconciles (covers
        // first install with default-on), every change re-reconciles.
        // Distinct: stop+start churn on unrelated settings edits re-posts
        // the persistent notification for no reason.
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
        ).launch {
            container.settingsRepository.settings
                .map { it.backgroundMonitoring }
                .distinctUntilChanged()
                .collect { on ->
                    if (on) HerdrMonitorService.start(this@HerdrApp)
                    else HerdrMonitorService.stop(this@HerdrApp)
                }
        }
    }
}
