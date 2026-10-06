package dev.herdr.mobile.core.model

/**
 * Condenses absolute paths for display on Home workspace cards.
 *
 * The daemon sends the concrete path plus the home directory it was resolved against, so the
 * client never has to guess where the user's home lives on a remote machine.
 */
object PathDisplay {

    fun condense(path: String?, home: String?): String? {
        if (path.isNullOrBlank()) return null
        val normalizedHome = home?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        if (normalizedHome != null && path.startsWith("$normalizedHome/")) {
            return "~" + path.removePrefix(normalizedHome)
        }
        if (normalizedHome != null && path == normalizedHome) return "~"
        return path
    }

    /**
     * Last two path segments, used when a workspace has no directory at all (for example a
     * scratch workspace whose cwd the server could not resolve).
     */
    fun fallback(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val segments = path.trimEnd('/').split('/').filter { it.isNotEmpty() }
        return when {
            segments.isEmpty() -> null
            segments.size == 1 -> segments[0]
            else -> segments.takeLast(2).joinToString("/")
        }
    }
}