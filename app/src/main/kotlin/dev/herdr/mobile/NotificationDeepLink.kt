package dev.herdr.mobile

/**
 * One-shot, in-process handoff from the non-exported notification proxy to
 * MainActivity. Keeping the target out of the exported activity's Intent means
 * another app cannot open a terminal (and trigger the pane read-ack) by
 * starting MainActivity with crafted extras.
 */
internal object NotificationDeepLink {

    data class Target(
        val workspaceId: String,
        val tabId: String?,
        val paneId: String?,
    )

    @Volatile
    private var pending: Target? = null

    fun publish(target: Target) {
        synchronized(this) { pending = target }
    }

    fun consume(): Target? = synchronized(this) {
        pending.also { pending = null }
    }
}
