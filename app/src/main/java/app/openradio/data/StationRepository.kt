package app.openradio.data

import androidx.room.withTransaction
import app.openradio.data.local.AppDatabase
import app.openradio.data.local.toEntity
import app.openradio.data.local.toSaved
import app.openradio.data.local.toStation
import app.openradio.data.remote.StationDirectory
import app.openradio.domain.PresetOrdering
import app.openradio.domain.SavedStation
import app.openradio.domain.Station
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Local station state (favorites, pins, recents, cached URLs) on top of the Room database. */
class StationRepository(
    private val db: AppDatabase,
    private val directory: StationDirectory,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.stations()

    val favorites: Flow<List<SavedStation>> = dao.observeFavorites().map { rows -> rows.map { it.toSaved() } }
    val recents: Flow<List<SavedStation>> = dao.observeRecents(MAX_RECENTS).map { rows -> rows.map { it.toSaved() } }
    val favoriteIds: Flow<Set<String>> = dao.observeFavoriteIds().map { it.toSet() }

    suspend fun favoritesNow(): List<SavedStation> = dao.favorites().map { it.toSaved() }
    suspend fun recentsNow(): List<Station> = dao.recents(MAX_RECENTS).map { it.toStation() }
    suspend fun carPresetsNow(): List<Station> = PresetOrdering.carPresets(favoritesNow())
    suspend fun localStation(id: String): Station? = dao.get(id)?.toStation()
    suspend fun searchFavorites(query: String): List<Station> = dao.searchFavorites(query).map { it.toStation() }

    /** Inserts or refreshes the directory fields of [station] while keeping the user's flags. */
    private suspend fun upsertKeepingFlags(station: Station) {
        dao.upsert(station.toEntity(dao.get(station.id)))
    }

    suspend fun setFavorite(station: Station, favorite: Boolean) = db.withTransaction {
        if (favorite) {
            upsertKeepingFlags(station)
            dao.markFavorite(station.id, clock())
        } else {
            dao.clearFavorite(station.id)
            writePins(PresetOrdering.retainFavorites(currentPins(), dao.favorites().map { it.id }.toSet()))
            dao.deleteOrphans()
        }
    }

    suspend fun toggleFavorite(station: Station) {
        val isFav = dao.get(station.id)?.isFavorite == true
        setFavorite(station, !isFav)
    }

    private suspend fun currentPins(): List<String> =
        dao.favorites().filter { it.pinnedOrder != null }.sortedBy { it.pinnedOrder }.map { it.id }

    private suspend fun writePins(pins: List<String>) {
        dao.clearPins()
        pins.forEachIndexed { index, id -> dao.setPin(id, index) }
    }

    /** Pins [stationId] (must be a favorite). Returns false when the six slots are full. */
    suspend fun pin(stationId: String): Boolean = db.withTransaction {
        val pins = currentPins()
        val updated = PresetOrdering.pin(pins, stationId)
        if (updated == pins && stationId !in pins) return@withTransaction false
        writePins(updated)
        true
    }

    suspend fun unpin(stationId: String) = db.withTransaction {
        writePins(PresetOrdering.unpin(currentPins(), stationId))
    }

    suspend fun movePin(from: Int, to: Int) = db.withTransaction {
        writePins(PresetOrdering.move(currentPins(), from, to))
    }

    /** Called once playback has actually started. Keeps a deduplicated, bounded recents list. */
    suspend fun recordPlayed(station: Station) = db.withTransaction {
        upsertKeepingFlags(station)
        dao.markPlayed(station.id, clock())
        dao.trimRecents(MAX_RECENTS)
        dao.deleteOrphans()
    }

    suspend fun rememberStreamUrl(stationId: String, url: String) {
        dao.updateStreamUrl(stationId, url)
    }

    /** Looks the station up by UUID and returns a fresh playable URL, or null when unavailable. */
    suspend fun fetchFreshUrl(stationId: String): String? {
        val fresh = runCatching { directory.byUuid(stationId) }.getOrNull() ?: return null
        dao.get(stationId)?.let { dao.updateStreamUrl(stationId, fresh.streamUrl) }
        return fresh.streamUrl
    }

    /** Local cache first (works offline), then the directory. */
    suspend fun resolveForPlayback(stationId: String): Station? =
        localStation(stationId) ?: runCatching { directory.byUuid(stationId) }.getOrNull()

    suspend fun registerClick(stationId: String) = directory.registerClick(stationId)

    suspend fun searchDirectory(query: String): List<Station> =
        runCatching { directory.search(query, null, null, 0, 5) }.getOrDefault(emptyList())

    companion object {
        const val MAX_RECENTS = 20
    }
}
