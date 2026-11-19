package app.openradio.domain

/** A radio station as stored locally and passed to the player. [id] is the Radio Browser stationuuid. */
data class Station(
    val id: String,
    val name: String,
    /** Playback URL: Radio Browser's url_resolved when present, otherwise url. */
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
) {
    /** "Seattle region, United States" style label; empty when the directory has no location. */
    val location: String
        get() = listOfNotNull(state?.takeIf { it.isNotBlank() }, country?.takeIf { it.isNotBlank() })
            .joinToString(", ")

    val monogram: String
        get() = name.trim().split(' ', '-', '.').filter { it.isNotEmpty() }
            .take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }

    val hasCoordinates: Boolean
        get() {
            val lat = latitude ?: return false
            val lon = longitude ?: return false
            return lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)
        }
}

/** A station plus the user's local state for it. */
data class SavedStation(
    val station: Station,
    val isFavorite: Boolean,
    /** Position among car presets (0-based) or null when not pinned. */
    val pinnedOrder: Int?,
    val lastPlayedAt: Long?,
    val groupName: String? = null,
    val failedLastAttempt: Boolean = false,
)

data class Country(val name: String, val code: String, val stationCount: Int)
