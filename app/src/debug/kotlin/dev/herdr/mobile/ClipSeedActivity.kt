package dev.herdr.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Debug-only clipboard seeder for E2E paste tests. Launched via adb with
 * `--es text "..."`; writes the text to the system clipboard and finishes.
 * Never shipped in release (debug source set only).
 */
class ClipSeedActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra("text").orEmpty()
        if (text.isNotEmpty()) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("seed", text))
        }
        finish()
    }
}
