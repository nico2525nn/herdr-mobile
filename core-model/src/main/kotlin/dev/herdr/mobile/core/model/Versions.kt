package dev.herdr.mobile.core.model

/**
 * Dotted-version comparison for the Home update pill. Twin of the daemon's
 * `version_is_newer` (daemon/src/model.rs): same strip-`v`, same numeric
 * per-segment compare, same missing-segment-is-zero rule. Both must stay in
 * sync — the daemon decides `upToDate`, the client decides pill visibility.
 */
object Versions {

    /** True when [latest] is a strictly newer dotted version than [current]. */
    fun isNewer(current: String, latest: String): Boolean {
        fun parts(v: String): List<Long> =
            v.trimStart('v').split('.').map {
                it.takeWhile(Char::isDigit).toLongOrNull() ?: 0
            }
        val a = parts(current)
        val b = parts(latest)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return y > x
        }
        return false
    }
}
