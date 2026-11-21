package dev.herdr.mobile

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.herdr.mobile.notifications.HerdrNotifications

/**
 * Non-exported entry point used by notification PendingIntents. The launcher
 * activity is exported (it must be); this proxy is not, so notification extras
 * cannot be forged by another app. It forwards through an in-process handoff,
 * never through MainActivity's Intent, and finishes immediately.
 */
class NotificationProxyActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val workspaceId = intent.getStringExtra(HerdrNotifications.EXTRA_WORKSPACE_ID)
        if (workspaceId != null) {
            NotificationDeepLink.publish(
                NotificationDeepLink.Target(
                    workspaceId = workspaceId,
                    tabId = intent.getStringExtra(HerdrNotifications.EXTRA_TAB_ID),
                    paneId = intent.getStringExtra(HerdrNotifications.EXTRA_PANE_ID),
                ),
            )
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
                ),
            )
        }
        finish()
    }
}
