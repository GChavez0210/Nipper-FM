package app.openradio.domain

/** What a bare Play (steering wheel, Bluetooth button, or "play Nipper FM") starts. */
sealed interface WheelStart {
    /** The last station played, queued with the list it belongs to. */
    data object LastStation : WheelStart
    /** The Android Auto presets. */
    data object Presets : WheelStart
    /** All favorites. */
    data object AllFavorites : WheelStart
    /** One Collection, by name. */
    data class Collection(val name: String) : WheelStart

    val key: String
        get() = when (this) {
            LastStation -> "last"
            Presets -> "presets"
            AllFavorites -> "favorites"
            is Collection -> COLLECTION_PREFIX + name
        }

    companion object {
        private const val COLLECTION_PREFIX = "collection:"

        fun fromKey(key: String?): WheelStart = when {
            key == null || key == "last" -> LastStation
            key == "presets" -> Presets
            key == "favorites" -> AllFavorites
            key.startsWith(COLLECTION_PREFIX) && key.length > COLLECTION_PREFIX.length ->
                Collection(key.removePrefix(COLLECTION_PREFIX))
            else -> LastStation
        }
    }
}

data class CarPrefs(
    /** Show presets and playlists in Android Auto as artwork tiles instead of rows. */
    val tiles: Boolean = true,
    val wheelStart: WheelStart = WheelStart.LastStation,
)

/** A queue to play: [stations] with playback starting at [startIndex]. */
data class CarQueue(val stations: List<Station>, val startIndex: Int) {
    val current: Station get() = stations[startIndex]
}

object CarPlaylists {
    /** Favorites in a Collection, in Favorites order. */
    fun collection(favorites: List<SavedStation>, name: String): List<Station> {
        val target = name.trim()
        return favorites.filter { it.groupName?.trim().equals(target, ignoreCase = true) }.map { it.station }
    }

    /** Collections that have at least one station, in first-seen order. */
    fun collectionNames(favorites: List<SavedStation>): List<String> =
        favorites.mapNotNull { it.groupName?.trim()?.takeIf(String::isNotEmpty) }.distinctBy { it.lowercase() }

    /**
     * The queue for a bare Play. A playlist resumes at the last station played when that station
     * is in it, so the wheel continues where the driver left off; otherwise it starts at the top.
     * An empty or missing playlist falls back to the last station, then to the presets.
     */
    fun wheelQueue(
        start: WheelStart,
        recents: List<Station>,
        presets: List<Station>,
        favorites: List<SavedStation>,
    ): CarQueue? {
        val last = recents.firstOrNull()
        val playlist = when (start) {
            WheelStart.LastStation -> emptyList()
            WheelStart.Presets -> presets
            WheelStart.AllFavorites -> favorites.map { it.station }
            is WheelStart.Collection -> collection(favorites, start.name)
        }
        if (playlist.isNotEmpty()) {
            val at = last?.let { l -> playlist.indexOfFirst { it.id == l.id } }?.takeIf { it >= 0 } ?: 0
            return CarQueue(playlist, at)
        }
        if (last != null) {
            val all = favorites.map { it.station }
            val list = listOf(presets, all, recents).firstOrNull { l -> l.any { it.id == last.id } } ?: listOf(last)
            return CarQueue(list, list.indexOfFirst { it.id == last.id }.coerceAtLeast(0))
        }
        return presets.takeIf { it.isNotEmpty() }?.let { CarQueue(it, 0) }
    }
}
