package app.openradio.domain

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil
import kotlin.math.pow

// ---- Favorites search & sorting ------------------------------------------------------------

enum class FavoriteSort(val label: String) {
    RecentlyAdded("Recently added"),
    Name("Name"),
    Country("Country"),
    RecentlyPlayed("Recently played"),
}

object FavoritesQuery {
    /**
     * Filters [favorites] by [query] (name, location, country code and tags, case-insensitive) and
     * orders them by [sort]. [RecentlyAdded] keeps the incoming order, which is newest-favorited first.
     */
    fun apply(favorites: List<SavedStation>, query: String, sort: FavoriteSort): List<SavedStation> {
        val q = query.trim()
        val matching = if (q.isEmpty()) favorites else favorites.filter { it.station.matches(q) }
        val byName = compareBy<SavedStation, String>(String.CASE_INSENSITIVE_ORDER) { it.station.name.trim() }
        return when (sort) {
            FavoriteSort.RecentlyAdded -> matching
            FavoriteSort.Name -> matching.sortedWith(byName)
            FavoriteSort.Country -> matching.sortedWith(
                // Stations without a country go last.
                compareBy<SavedStation> { it.station.country.isNullOrBlank() }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.station.country.orEmpty() }
                    .then(byName),
            )
            FavoriteSort.RecentlyPlayed -> matching.sortedWith(
                // Never-played stations go last, alphabetically.
                compareByDescending<SavedStation> { it.lastPlayedAt ?: Long.MIN_VALUE }.then(byName),
            )
        }
    }

    private fun Station.matches(q: String): Boolean =
        name.contains(q, ignoreCase = true) ||
            country?.contains(q, ignoreCase = true) == true ||
            state?.contains(q, ignoreCase = true) == true ||
            countryCode?.equals(q, ignoreCase = true) == true ||
            tags.any { it.contains(q, ignoreCase = true) }
}

// ---- Per-station volume trim ---------------------------------------------------------------

object VolumeTrim {
    const val MIN_DB = -12
    const val MAX_DB = 6

    fun clamp(db: Int): Int = db.coerceIn(MIN_DB, MAX_DB)

    /** Linear player-volume factor for cuts; boosts are applied as DSP gain instead. */
    fun attenuationFactor(db: Int): Float =
        if (db >= 0) 1f else 10.0.pow(clamp(db) / 20.0).toFloat()

    /** Extra LoudnessEnhancer gain in millibels for boosts (0 for cuts). */
    fun boostMillibels(db: Int): Int = clamp(db).coerceAtLeast(0) * 100

    fun label(db: Int): String = when {
        db == 0 -> "0 dB"
        db > 0 -> "+$db dB"
        else -> "$db dB"
    }
}

// ---- Sleep options -------------------------------------------------------------------------

object SleepCycle {
    /** Steps the lock-screen / car sleep button walks through, in minutes (0 = off). */
    val STEPS = listOf(0, 15, 30, 60)

    /**
     * Next step after the current state. [remainingMs] <= 0 means no timer. An active timer
     * counts as being on the smallest step that still covers its remaining time, and moves to
     * the step after that; past the longest step it turns off.
     */
    fun next(remainingMs: Long): Int {
        if (remainingMs <= 0) return STEPS[1]
        val remainingMin = ceil(remainingMs / 60_000.0).toInt()
        val current = STEPS.indexOfFirst { it >= remainingMin }
        return if (current < 0) 0 else STEPS.getOrElse(current + 1) { 0 }
    }
}

// ---- Playback error log --------------------------------------------------------------------

data class ErrorLogEntry(
    val timestampMs: Long,
    val event: String,
    val stationName: String?,
    val stationId: String?,
    val detail: String?,
    val url: String? = null,
) {
    /** One tab-separated line; tabs and newlines inside fields are flattened to spaces. */
    fun toLine(): String = listOf(
        timestampMs.toString(), event, stationName, stationId, detail, url,
    ).joinToString("\t") { it.orEmpty().replace(Regex("[\\t\\r\\n]+"), " ") }

    companion object {
        fun parse(line: String): ErrorLogEntry? {
            val parts = line.split('\t')
            if (parts.size < 5) return null
            val ts = parts[0].toLongOrNull() ?: return null
            fun field(i: Int) = parts.getOrNull(i)?.takeIf { it.isNotEmpty() }
            return ErrorLogEntry(ts, parts[1], field(2), field(3), field(4), field(5))
        }
    }
}

object ErrorLogFormat {
    const val MAX_ENTRIES = 300

    /** Keeps the newest [max] lines. */
    fun trim(lines: List<String>, max: Int = MAX_ENTRIES): List<String> =
        if (lines.size <= max) lines else lines.takeLast(max)

    /** Human-readable export, newest first, with UTC timestamps. */
    fun export(entries: List<ErrorLogEntry>, appVersion: String, device: String): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return buildString {
            appendLine("Nipper FM playback error log")
            appendLine("App $appVersion · $device")
            appendLine("${entries.size} entries, newest first")
            appendLine()
            entries.sortedByDescending { it.timestampMs }.forEach { e ->
                append(fmt.format(Date(e.timestampMs))).append("  ").append(e.event)
                e.stationName?.let { append("  ").append(it) }
                e.stationId?.let { append(" [").append(it).append(']') }
                appendLine()
                e.detail?.let { appendLine("    $it") }
                e.url?.let { appendLine("    $it") }
            }
        }
    }
}
