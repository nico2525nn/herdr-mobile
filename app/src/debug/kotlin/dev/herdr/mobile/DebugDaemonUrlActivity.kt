package dev.herdr.mobile

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.herdr.mobile.core.model.TransportMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File

/**
 * Debug-only, CI-facing control over the Direct daemon URL.
 *
 * `tools/ci.sh` points the debug app at a throwaway daemon without touching the
 * developer's saved URL. The original transport mode + URL are snapshotted in a
 * debug-private backup; `restore` puts them back and clears the backup. `apply`
 * self-heals first, so a run killed with `kill -9` still leaves the next run
 * with the developer's values before it starts.
 *
 *   adb shell am start -W -n dev.herdr.mobile.debug/dev.herdr.mobile.DebugDaemonUrlActivity \
 *     --es action apply --es url http://127.0.0.1:8766
 *   adb shell am start -W -n dev.herdr.mobile.debug/dev.herdr.mobile.DebugDaemonUrlActivity \
 *     --es action restore
 *
 * The outcome is written to `Android/data/<pkg>/files/herdr-ci-state.json` (the
 * script cannot see an activity result, and `am start` exits 0 even when the
 * work inside failed).
 */
class DebugDaemonUrlActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent.getStringExtra("action") ?: "apply"
        val url = intent.getStringExtra("url")
        val repository = (application as HerdrApp).container.settingsRepository
        val backup = getSharedPreferences(BACKUP_PREFS, MODE_PRIVATE)

        lifecycleScope.launch {
            val result = runCatching {
                withTimeout(30_000) {
                    repository.awaitLoaded()
                    withContext(Dispatchers.IO) {
                        val savedTransport = backup.getString(KEY_TRANSPORT, null)
                        val savedUrl = backup.getString(KEY_URL, null)
                        if (savedTransport != null && savedUrl != null) {
                            // A previous run was killed before its restore step:
                            // put the developer's values back before anything else.
                            repository.update { current ->
                                current.copy(
                                    transportMode = TransportMode.fromWire(savedTransport),
                                    directUrl = savedUrl,
                                )
                            }
                            backup.edit().clear().commit()
                        }

                        when (action) {
                            "apply" -> {
                                require(!url.isNullOrBlank()) { "apply needs --es url" }
                                val current = repository.settings.value
                                check(
                                    backup.edit()
                                        .putString(KEY_TRANSPORT, current.transportMode.wire)
                                        .putString(KEY_URL, current.directUrl)
                                        .commit(),
                                ) { "cannot write the settings backup" }
                                repository.update { settings ->
                                    settings.copy(transportMode = TransportMode.DIRECT, directUrl = url)
                                }
                            }

                            "restore" -> repository.update { current ->
                                if (savedTransport != null && savedUrl != null) {
                                    current.copy(
                                        transportMode = TransportMode.fromWire(savedTransport),
                                        directUrl = savedUrl,
                                    )
                                } else {
                                    current
                                }
                            }

                            else -> error("unknown action $action")
                        }

                        val effective = repository.settings.value
                        writeState(
                            JSONObject()
                                .put("action", action)
                                .put("ok", true)
                                .put("transportMode", effective.transportMode.wire)
                                .put("directUrl", effective.directUrl)
                                .put("backupPresent", backup.contains(KEY_URL)),
                        )
                    }
                }
            }
            result.exceptionOrNull()?.let { error ->
                Log.e(TAG, "$action failed: ${error.message}", error)
                writeState(
                    JSONObject()
                        .put("action", action)
                        .put("ok", false)
                        .put("error", error.message ?: error.javaClass.name),
                )
            }
            finish()
        }
    }

    private fun writeState(json: JSONObject) {
        val file = File(getExternalFilesDir(null) ?: filesDir, STATE_FILE)
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.toString())
        }.onFailure { Log.w(TAG, "cannot write $file", it) }
        Log.i(TAG, json.toString())
    }

    companion object {
        private const val TAG = "HerdrCi"
        private const val STATE_FILE = "herdr-ci-state.json"
        private const val BACKUP_PREFS = "herdr_ci_backup"
        private const val KEY_TRANSPORT = "transport"
        private const val KEY_URL = "url"
    }
}
