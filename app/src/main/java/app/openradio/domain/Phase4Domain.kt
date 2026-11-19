package app.openradio.domain

import java.util.Calendar
import java.util.TimeZone

// ---- Stream URL health ---------------------------------------------------------------------

/** What the device has observed about one stream URL of a station. */
data class UrlHealth(
    val url: String,
    val lastSuccessAt: Long?,
    val lastFailureAt: Long?,
    val failureCount: Int,
)

object StreamUrlPreference {
    /** A failure is "recent" for this long, unless the URL has worked since. */
    const val FAILURE_MEMORY_MS = 6 * 60 * 60 * 1000L

    private fun norm(url: String) = url.trim().lowercase()

    fun recentlyFailed(h: UrlHealth, now: Long): Boolean {
        val failedAt = h.lastFailureAt ?: return false
        if (now - failedAt >= FAILURE_MEMORY_MS) return false
        return h.lastSuccessAt == null || failedAt > h.lastSuccessAt
    }

    private fun knownGood(h: UrlHealth, now: Long) = h.lastSuccessAt != null && !recentlyFailed(h, now)

    /**
     * URL to start playback with. Keeps [requested] unless it failed recently, in which case the
     * most recently working other URL for the station wins. Unknown URLs are never second-guessed.
     */
    fun choose(requested: String, health: List<UrlHealth>, now: Long): String {
        val req = health.firstOrNull { norm(it.url) == norm(requested) }
        if (req == null || !recentlyFailed(req, now)) return requested
        return health
            .filter { norm(it.url) != norm(requested) && knownGood(it, now) }
            .maxByOrNull { it.lastSuccessAt ?: 0L }
            ?.url ?: requested
    }

    /**
     * Reorders failover candidates: known-good feeds first (most recent success first), then
     * untested ones in their original quality order, then recently failed ones.
     */
    fun orderFeeds(feeds: List<StreamFeed>, health: List<UrlHealth>, now: Long): List<StreamFeed> {
        val byUrl = health.associateBy { norm(it.url) }
        fun rank(f: StreamFeed): Int {
            val h = byUrl[norm(f.streamUrl)] ?: return 1
            return when {
                recentlyFailed(h, now) -> 2
                h.lastSuccessAt != null -> 0
                else -> 1
            }
        }
        fun successAt(f: StreamFeed): Long =
            if (rank(f) == 0) byUrl[norm(f.streamUrl)]?.lastSuccessAt ?: 0L else 0L
        return feeds.withIndex()
            .sortedWith(
                compareBy<IndexedValue<StreamFeed>> { rank(it.value) }
                    .thenByDescending { successAt(it.value) }
                    .thenBy { it.index },
            )
            .map { it.value }
    }
}

// ---- Data usage metering -------------------------------------------------------------------

/** Network bytes streamed in one calendar month, split by metered (mobile) vs unmetered. */
data class MonthlyUsage(val month: String, val mobileBytes: Long, val wifiBytes: Long) {
    val totalBytes: Long get() = mobileBytes + wifiBytes
}

object DataUsageMath {
    /** "yyyy-MM" in the device's time zone. */
    fun monthKey(epochMs: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val c = Calendar.getInstance(zone).apply { timeInMillis = epochMs }
        return "%04d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
    }

    /** The month before [key] ("2026-01" -> "2025-12"). */
    fun previousMonth(key: String): String {
        val (y, m) = key.split('-').map { it.toInt() }
        return if (m == 1) "%04d-12".format(y - 1) else "%04d-%02d".format(y, m - 1)
    }

    /** Keeps only the newest [keep] month keys. */
    fun monthsToDrop(keys: Collection<String>, keep: Int = 13): List<String> =
        keys.sortedDescending().drop(keep)

    fun format(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1 -> "%.2f GB".format(gb)
            mb >= 1 -> "%.1f MB".format(mb)
            kb >= 1 -> "%.0f KB".format(kb)
            else -> "$bytes B"
        }
    }
}
