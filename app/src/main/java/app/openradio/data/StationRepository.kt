package app.openradio.data

import androidx.room.withTransaction
import app.openradio.data.local.AppDatabase
import app.openradio.data.local.FavoriteGroupEntity
import app.openradio.data.local.OnAirEntity
import app.openradio.data.local.StreamHealthEntity
import app.openradio.data.local.toDomain
import app.openradio.data.local.toEntity
import app.openradio.data.local.toSaved
import app.openradio.data.local.toStation
import app.openradio.data.remote.StationDirectory
import app.openradio.domain.AlternateFeedSelector
import app.openradio.domain.BackupPayload
import app.openradio.domain.BackupStationRecord
import app.openradio.domain.CustomStreamBuilder
import app.openradio.domain.FavoriteGroupDefaults
import app.openradio.domain.OnAirEntry
import app.openradio.domain.PresetOrdering
import app.openradio.domain.SavedStation
import app.openradio.domain.Station
import app.openradio.domain.StreamFeed
import app.openradio.domain.UrlHealth
import app.openradio.domain.VolumeTrim
import app.openradio.ui.theme.AppearancePrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** Local station state (favorites, groups, pins, recents, custom streams, on-air history) on top of Room. */
class StationRepository(
    private val db: AppDatabase,
    private val directory: StationDirectory,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.stations()

    val favorites: Flow<List<SavedStation>> = dao.observeFavorites().map { rows -> rows.map { it.toSaved() } }
    val recents: Flow<List<SavedStation>> = dao.observeRecents(MAX_RECENTS).map { rows -> rows.map { it.toSaved() } }
    val favoriteIds: Flow<Set<String>> = dao.observeFavoriteIds().map { it.toSet() }
    val failedIds: Flow<Set<String>> = dao.observeFailedIds().map { it.toSet() }
    val onAirHistory: Flow<List<OnAirEntry>> = dao.observeOnAir().map { rows -> rows.map { it.toDomain() } }

    /** Combines starter groups, user-created groups, and any group names assigned to saved favorites. */
    val favoriteGroups: Flow<List<String>> = combine(
        dao.observeGroupNames(),
        dao.observeFavorites(),
    ) { explicit, favs ->
        val fromFavs = favs.mapNotNull { it.groupName?.takeIf { g -> g.isNotBlank() } }
        (FavoriteGroupDefaults.STARTER_GROUPS + explicit + fromFavs).distinct()
    }

    suspend fun favoritesNow(): List<SavedStation> = dao.favorites().map { it.toSaved() }
    suspend fun recentsNow(): List<Station> = dao.recents(MAX_RECENTS).map { it.toStation() }
    suspend fun carPresetsNow(): List<Station> = PresetOrdering.carPresets(favoritesNow())
    suspend fun localStation(id: String): Station? = dao.get(id)?.toStation()
    suspend fun searchFavorites(query: String): List<Station> = dao.searchFavorites(query).map { it.toStation() }

    /** Inserts or refreshes the directory fields of [station] while keeping the user's flags. */
    private suspend fun upsertKeepingFlags(station: Station) {
        dao.upsert(station.toEntity(dao.get(station.id)))
    }

    suspend fun setFavorite(station: Station, favorite: Boolean, groupName: String? = null) = db.withTransaction {
        if (favorite) {
            upsertKeepingFlags(station)
            dao.markFavorite(station.id, clock())
            if (groupName != null) {
                val cleanGroup = groupName.trim().takeIf { it.isNotEmpty() }
                if (cleanGroup != null) {
                    dao.insertGroup(FavoriteGroupEntity(cleanGroup, clock()))
                    dao.setGroup(station.id, cleanGroup)
                }
            }
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

    // ---- Favorite Groups (independent of Android Auto presets) ------------------------------

    suspend fun createGroup(name: String) {
        val clean = name.trim()
        if (clean.isNotEmpty()) {
            dao.insertGroup(FavoriteGroupEntity(clean, clock()))
        }
    }

    suspend fun deleteGroup(name: String) = db.withTransaction {
        dao.clearGroupFromStations(name)
        dao.deleteGroup(name)
    }

    suspend fun setStationGroup(stationId: String, groupName: String?) = db.withTransaction {
        val clean = groupName?.trim()?.takeIf { it.isNotEmpty() }
        if (clean != null) {
            dao.insertGroup(FavoriteGroupEntity(clean, clock()))
        }
        dao.setGroup(stationId, clean)
    }

    // ---- Custom Stream URLs -----------------------------------------------------------------

    suspend fun saveCustomStream(name: String, url: String, groupName: String? = null): Station? {
        val station = CustomStreamBuilder.build(name, url) ?: return null
        setFavorite(station, favorite = true, groupName = groupName)
        return station
    }

    // ---- Car Presets ------------------------------------------------------------------------

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

    // ---- Recents & Stream URLs --------------------------------------------------------------

    /** Called once playback has actually started. Keeps a deduplicated, bounded recents list and clears any failed flag. */
    suspend fun recordPlayed(station: Station) = db.withTransaction {
        upsertKeepingFlags(station)
        dao.markPlayed(station.id, clock())
        dao.setFailedLastAttempt(station.id, false)
        dao.trimRecents(MAX_RECENTS)
        dao.deleteOrphans()
    }

    /** Records or clears the per-station "Failed last attempt" flag on the device. */
    suspend fun markFailedAttempt(station: Station, failed: Boolean) = db.withTransaction {
        upsertKeepingFlags(station)
        dao.setFailedLastAttempt(station.id, failed)
        if (!failed) dao.deleteOrphans()
    }

    /** Queries the directory for alternate verified feeds (including FLAC / different bitrates) for [station]. */
    suspend fun alternateFeedsFor(station: Station): List<StreamFeed> {
        if (station.isCustom) {
            return AlternateFeedSelector.rankFeeds(station, emptyList())
        }
        val cleanName = station.name.trim()
        val matches = runCatching {
            directory.search(
                query = cleanName,
                countryCode = station.countryCode,
                tag = null,
                offset = 0,
                limit = 15,
            )
        }.getOrDefault(emptyList()).filter { candidate ->
            candidate.name.equals(cleanName, ignoreCase = true) ||
                candidate.name.startsWith(cleanName, ignoreCase = true) ||
                cleanName.startsWith(candidate.name, ignoreCase = true)
        }
        return AlternateFeedSelector.rankFeeds(station, matches)
    }

    // ---- Per-station volume trim ------------------------------------------------------------

    suspend fun volumeTrim(stationId: String): Int = dao.volumeTrim(stationId) ?: 0

    /** Stores [db] for [station], creating its cached row if needed so the trim survives restarts. */
    suspend fun setVolumeTrim(station: Station, trimDb: Int) = db.withTransaction {
        upsertKeepingFlags(station)
        dao.setVolumeTrim(station.id, VolumeTrim.clamp(trimDb))
        dao.deleteOrphans()
    }

    // ---- Stream URL health ------------------------------------------------------------------

    suspend fun streamHealth(stationId: String): List<UrlHealth> =
        dao.streamHealth(stationId).map { it.toDomain() }

    /** Audio actually started from [url]: it is now the station's known-good URL. */
    suspend fun recordUrlSuccess(stationId: String, url: String) = db.withTransaction {
        val now = clock()
        val existing = dao.streamHealth(stationId, url)
        dao.upsertStreamHealth(
            (existing ?: StreamHealthEntity(stationId, url)).copy(lastSuccessAt = now, failureCount = 0),
        )
        dao.pruneStreamHealth(now - HEALTH_RETENTION_MS)
    }

    suspend fun recordUrlFailure(stationId: String, url: String) {
        val existing = dao.streamHealth(stationId, url)
        dao.upsertStreamHealth(
            (existing ?: StreamHealthEntity(stationId, url)).copy(
                lastFailureAt = clock(),
                failureCount = (existing?.failureCount ?: 0) + 1,
            ),
        )
    }

    suspend fun rememberStreamUrl(stationId: String, url: String) {
        dao.updateStreamUrl(stationId, url)
    }

    /** Looks the station up by UUID and returns a fresh playable URL, or null when unavailable. */
    suspend fun fetchFreshUrl(stationId: String): String? {
        if (stationId.startsWith("custom:")) return dao.get(stationId)?.streamUrl
        val fresh = runCatching { directory.byUuid(stationId) }.getOrNull() ?: return null
        dao.get(stationId)?.let { dao.updateStreamUrl(stationId, fresh.streamUrl) }
        return fresh.streamUrl
    }

    /** Local cache first (works offline), then the directory. */
    suspend fun resolveForPlayback(stationId: String): Station? =
        localStation(stationId) ?: runCatching { directory.byUuid(stationId) }.getOrNull()

    suspend fun registerClick(stationId: String) {
        if (!stationId.startsWith("custom:")) directory.registerClick(stationId)
    }

    suspend fun searchDirectory(query: String, countryCode: String? = null, limit: Int = 5): List<Station> =
        runCatching { directory.searchExpanded(query, countryCode, null, 0, limit) }.getOrDefault(emptyList())

    /** Directory stations for a genre tag and/or country, most popular first. Throws on network errors. */
    suspend fun directoryCategory(tag: String?, countryCode: String?, limit: Int): List<Station> =
        directory.search(null, countryCode, tag, 0, limit)

    suspend fun popularCountryCodes(): List<String> = directory.popularCountryCodes()

    // ---- Recently On Air (Track Metadata History & Bookmarks) -------------------------------

    suspend fun recordOnAir(station: Station, rawTitle: String) = db.withTransaction {
        val clean = rawTitle.trim()
        if (clean.isEmpty() || clean.equals(station.name, ignoreCase = true)) return@withTransaction
        val latest = dao.latestOnAir()
        if (latest != null && latest.stationId == station.id && latest.trackTitle.equals(clean, ignoreCase = true)) {
            return@withTransaction
        }
        dao.insertOnAir(
            OnAirEntity(
                stationId = station.id,
                stationName = station.name,
                trackTitle = clean,
                timestampMs = clock(),
            ),
        )
        dao.trimOnAir()
    }

    suspend fun bookmarkTrack(station: Station, title: String): Boolean = db.withTransaction {
        recordOnAir(station, title)
        val entry = dao.latestOnAir()?.takeIf {
            it.stationId == station.id && it.trackTitle.equals(title.trim(), ignoreCase = true)
        } ?: return@withTransaction false
        dao.setOnAirBookmark(entry.id, true)
        true
    }

    suspend fun toggleOnAirBookmark(id: Long) = db.withTransaction {
        val current = dao.getOnAir(id) ?: return@withTransaction
        dao.setOnAirBookmark(id, !current.isBookmarked)
    }

    // ---- Backup & Restore -------------------------------------------------------------------

    suspend fun buildBackupPayload(appearance: AppearancePrefs): BackupPayload {
        val allSaved = dao.allStations()
            .filter { it.isFavorite || it.isCustom || it.pinnedOrder != null }
            .map { BackupStationRecord.fromSaved(it.toSaved()) }
        val explicitGroups = dao.groupNames()
        return BackupPayload(
            version = 1,
            exportedAtMs = clock(),
            themeMode = appearance.themeMode.name,
            darkStyle = appearance.darkStyle.name,
            accent = appearance.accent.name,
            groups = explicitGroups,
            stations = allSaved,
        )
    }

    suspend fun restoreBackup(payload: BackupPayload, replace: Boolean) = db.withTransaction {
        val now = clock()
        if (replace) {
            dao.deleteAllStations()
            dao.deleteAllGroups()
        }
        payload.groups.map { it.trim() }.filter { it.isNotEmpty() }.forEach { g ->
            dao.insertGroup(FavoriteGroupEntity(g, now))
        }
        val incomingPins = mutableListOf<Pair<Int, String>>()
        payload.stations.forEachIndexed { idx, rec ->
            val saved = rec.toSavedStation()
            val existing = if (replace) null else dao.get(saved.station.id)
            val isFav = saved.isFavorite || (existing?.isFavorite == true)
            val group = saved.groupName?.trim()?.takeIf { it.isNotEmpty() } ?: existing?.groupName
            if (group != null) {
                dao.insertGroup(FavoriteGroupEntity(group, now))
            }
            val entity = saved.station.toEntity(existing).copy(
                isFavorite = isFav,
                favoritedAt = if (isFav) (existing?.favoritedAt ?: (now - idx)) else null,
                groupName = if (isFav) group else null,
                lastPlayedAt = saved.lastPlayedAt ?: existing?.lastPlayedAt,
            )
            dao.upsert(entity)
            if (isFav && saved.pinnedOrder != null) {
                incomingPins += saved.pinnedOrder to saved.station.id
            }
        }
        val orderedIncomingPinIds = incomingPins.sortedBy { it.first }.map { it.second }
        val mergedPins = if (replace) {
            orderedIncomingPinIds.distinct().take(PresetOrdering.MAX_PINS)
        } else {
            var pins = currentPins()
            for (id in orderedIncomingPinIds) {
                pins = PresetOrdering.pin(pins, id)
            }
            pins
        }
        writePins(mergedPins)
    }

    companion object {
        const val MAX_RECENTS = 20
        private const val HEALTH_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }
}
