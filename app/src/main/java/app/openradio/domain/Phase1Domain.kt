package app.openradio.domain

import app.openradio.ui.theme.Accent
import app.openradio.ui.theme.AppearancePrefs
import app.openradio.ui.theme.DarkStyle
import app.openradio.ui.theme.ThemeMode
import java.util.UUID
import kotlin.random.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Starter favorite group suggestions shown alongside user-created groups. */
object FavoriteGroupDefaults {
    val STARTER_GROUPS = listOf("Commute", "Late Night", "Work", "Weekend")
}

/** Mood and curated collection quick-filters for Explore. */
enum class MoodCollection(val label: String, val tag: String) {
    Chill("Chill & Ambient", "ambient"),
    Focus("Focus & Lo-Fi", "lofi"),
    LateNight("Late Night", "downtempo"),
    Jazz("Jazz & Soul", "jazz"),
    Classical("Classical", "classical"),
    Rock("Indie & Rock", "indie"),
    Electronic("Synth & Electronic", "electronic"),
    News("News & Talk", "news"),
}

/** A track logged when a broadcaster sends ICY stream metadata. */
data class OnAirEntry(
    val id: Long = 0,
    val stationId: String,
    val stationName: String,
    val trackTitle: String,
    val timestampMs: Long,
    val isBookmarked: Boolean = false,
) {
    val copyText: String get() = "$trackTitle — $stationName"
}

/**
 * Picks a station from [candidates] for "Surprise Me", favoring stations the user hasn't played
 * recently ([recentIds]) and avoiding the currently active station ([currentId]) when possible.
 */
object SurprisePicker {
    fun pick(
        candidates: List<Station>,
        recentIds: Collection<String>,
        currentId: String? = null,
        random: Random = Random.Default,
    ): Station? {
        if (candidates.isEmpty()) return null
        val pool = candidates.filter { it.id != currentId }.ifEmpty { candidates }
        val recentSet = recentIds.toSet()
        val unplayed = pool.filter { it.id !in recentSet }
        val targetPool = unplayed.ifEmpty { pool }
        return targetPool[random.nextInt(targetPool.size)]
    }
}

/** Validates and builds custom HLS/M3U8, Icecast, and Shoutcast station entries. */
object CustomStreamBuilder {
    fun isValidUrl(rawUrl: String): Boolean {
        val trimmed = rawUrl.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return false
        }
        val afterScheme = trimmed.substringAfter("://")
        return afterScheme.contains('.') || afterScheme.startsWith("localhost") || afterScheme.contains(':')
    }

    fun isHlsUrl(url: String): Boolean {
        val clean = url.trim().lowercase().substringBefore('?')
        return clean.endsWith(".m3u8") || clean.contains(".m3u8/") || clean.contains("/hls/")
    }

    fun build(
        name: String,
        rawUrl: String,
        id: String = "custom:${UUID.randomUUID()}",
    ): Station? {
        val cleanName = name.trim()
        val cleanUrl = rawUrl.trim()
        if (cleanName.isEmpty() || !isValidUrl(cleanUrl)) return null
        val hls = isHlsUrl(cleanUrl)
        return Station(
            id = id,
            name = cleanName,
            streamUrl = cleanUrl,
            country = "Custom Stream",
            tags = if (hls) listOf("HLS", "Custom") else listOf("Icecast/Shoutcast", "Custom"),
            codec = if (hls) "HLS" else null,
            isHls = hls,
            isCustom = true,
        )
    }
}

/**
 * Classifies and scores expanded search queries across station name, city/state, callsign, and
 * frequency (e.g. "90.3", "KEXP", "Seattle").
 */
object ExpandedSearch {
    private val FREQUENCY_REGEX = Regex("""^\d{2,3}(\.\d)?(\s*(fm|am|mhz|khz))?$""", RegexOption.IGNORE_CASE)
    private val CALLSIGN_REGEX = Regex("""^[KWCV][A-Z]{2,3}(-FM|-AM|\s+FM|\s+AM)?$""", RegexOption.IGNORE_CASE)

    fun isFrequencyQuery(query: String): Boolean = FREQUENCY_REGEX.matches(query.trim())

    fun isCallsignQuery(query: String): Boolean = CALLSIGN_REGEX.matches(query.trim())

    /**
     * Merges primary name matches with secondary state/city and tag matches, deduplicating by ID
     * and ranking exact callsign/frequency/city matches first.
     */
    fun mergeAndRank(
        query: String,
        byName: List<Station>,
        byCityOrState: List<Station> = emptyList(),
        byTag: List<Station> = emptyList(),
    ): List<Station> {
        val q = query.trim().lowercase()
        val combined = (byName + byCityOrState + byTag).distinctBy { it.id }
        if (q.isEmpty()) return combined
        return combined.sortedByDescending { station ->
            var score = 0
            val nameLower = station.name.lowercase()
            val stateLower = station.state?.lowercase().orEmpty()
            val countryLower = station.country?.lowercase().orEmpty()
            if (nameLower == q) score += 100
            if (nameLower.startsWith(q)) score += 50
            if (nameLower.contains(q)) score += 25
            if (stateLower == q || stateLower.startsWith(q)) score += 40
            else if (stateLower.contains(q)) score += 20
            if (countryLower == q) score += 15
            if (station.tags.any { it.equals(q, ignoreCase = true) }) score += 30
            else if (station.tags.any { it.contains(q, ignoreCase = true) }) score += 10
            score
        }
    }
}

@Serializable
data class BackupStationRecord(
    val id: String,
    val name: String,
    val streamUrl: String,
    val faviconUrl: String? = null,
    val country: String? = null,
    val countryCode: String? = null,
    val state: String? = null,
    val tags: List<String> = emptyList(),
    val codec: String? = null,
    val bitrate: Int = 0,
    val isHls: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val isCustom: Boolean = false,
    val isFavorite: Boolean = true,
    val pinnedOrder: Int? = null,
    val lastPlayedAt: Long? = null,
    val groupName: String? = null,
) {
    fun toSavedStation(): SavedStation = SavedStation(
        station = Station(
            id = id,
            name = name,
            streamUrl = streamUrl,
            faviconUrl = faviconUrl,
            country = country,
            countryCode = countryCode,
            state = state,
            tags = tags,
            codec = codec,
            bitrate = bitrate,
            isHls = isHls,
            latitude = latitude,
            longitude = longitude,
            isCustom = isCustom,
        ),
        isFavorite = isFavorite,
        pinnedOrder = pinnedOrder,
        lastPlayedAt = lastPlayedAt,
        groupName = groupName,
    )

    companion object {
        fun fromSaved(saved: SavedStation) = BackupStationRecord(
            id = saved.station.id,
            name = saved.station.name,
            streamUrl = saved.station.streamUrl,
            faviconUrl = saved.station.faviconUrl,
            country = saved.station.country,
            countryCode = saved.station.countryCode,
            state = saved.station.state,
            tags = saved.station.tags,
            codec = saved.station.codec,
            bitrate = saved.station.bitrate,
            isHls = saved.station.isHls,
            latitude = saved.station.latitude,
            longitude = saved.station.longitude,
            isCustom = saved.station.isCustom,
            isFavorite = saved.isFavorite,
            pinnedOrder = saved.pinnedOrder,
            lastPlayedAt = saved.lastPlayedAt,
            groupName = saved.groupName,
        )
    }
}

@Serializable
data class BackupPayload(
    val version: Int = 1,
    val exportedAtMs: Long = 0L,
    val themeMode: String = ThemeMode.System.name,
    val darkStyle: String = DarkStyle.Obsidian.name,
    val accent: String = Accent.Amber.name,
    val groups: List<String> = emptyList(),
    val stations: List<BackupStationRecord> = emptyList(),
) {
    fun toAppearancePrefs(): AppearancePrefs = AppearancePrefs(
        themeMode = ThemeMode.entries.firstOrNull { it.name == themeMode } ?: ThemeMode.System,
        darkStyle = DarkStyle.entries.firstOrNull { it.name == darkStyle } ?: DarkStyle.Obsidian,
        accent = Accent.entries.firstOrNull { it.name == accent } ?: Accent.Amber,
    )
}

data class BackupPreviewSummary(
    val favoriteCount: Int,
    val groupCount: Int,
    val presetCount: Int,
    val customStreamCount: Int,
    val appearanceSummary: String,
)

object BackupCodec {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(payload: BackupPayload): String = json.encodeToString(payload)

    fun decode(rawJson: String): BackupPayload = json.decodeFromString(rawJson)

    fun summarize(payload: BackupPayload): BackupPreviewSummary {
        val favs = payload.stations.count { it.isFavorite }
        val allGroups = (
            payload.groups +
                payload.stations.mapNotNull { it.groupName?.takeIf { g -> g.isNotBlank() } }
            ).distinct()
        val presets = payload.stations.count { it.pinnedOrder != null }
        val custom = payload.stations.count { it.isCustom }
        val prefs = payload.toAppearancePrefs()
        return BackupPreviewSummary(
            favoriteCount = favs,
            groupCount = allGroups.size,
            presetCount = presets,
            customStreamCount = custom,
            appearanceSummary = "${prefs.themeMode.label} • ${prefs.darkStyle.label} • ${prefs.accent.label}",
        )
    }
}
