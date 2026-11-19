package app.openradio.data

import app.openradio.domain.Station
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Directory lists for Android Auto browsing and voice requests (stations per country or genre,
 * popular country codes), kept in memory and in app cache storage.
 *
 * Fresh entries (younger than [ttlMs]) are served without a request. Stale entries are
 * refreshed, but are still returned when the directory can't be reached, so the car shows the
 * last known list instead of an empty folder while driving through poor coverage.
 */
class CarBrowseCache(
    cacheDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {
    @Serializable
    private data class Entry(val fetchedAt: Long, val stations: List<Station> = emptyList(), val codes: List<String> = emptyList())

    @Serializable
    private data class Snapshot(val entries: Map<String, Entry> = emptyMap())

    private val file = File(cacheDir, "car_browse.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var entries: MutableMap<String, Entry>? = null

    suspend fun stations(key: String, fetch: suspend () -> List<Station>): List<Station> =
        load(key, { it.stations }, { Entry(clock(), stations = it) }, fetch)

    suspend fun codes(key: String, fetch: suspend () -> List<String>): List<String> =
        load(key, { it.codes }, { Entry(clock(), codes = it) }, fetch)

    /** A station from any cached list, so a car selection resolves without a network lookup. */
    suspend fun findStation(id: String): Station? = mutex.withLock {
        loaded().values.asSequence().flatMap { it.stations.asSequence() }.firstOrNull { it.id == id }
    }

    /** The most recently fetched cached list containing [id], used to queue next/previous. */
    suspend fun listContaining(id: String): List<Station>? = mutex.withLock {
        loaded().values.sortedByDescending { it.fetchedAt }
            .firstOrNull { e -> e.stations.any { it.id == id } }?.stations
    }

    private suspend fun <T> load(
        key: String,
        read: (Entry) -> List<T>,
        build: (List<T>) -> Entry,
        fetch: suspend () -> List<T>,
    ): List<T> {
        val cached = mutex.withLock { loaded()[key] }
        if (cached != null && clock() - cached.fetchedAt < ttlMs) return read(cached)
        val fresh = try {
            fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (cached != null) return read(cached)
            throw e
        }
        // An empty answer is not worth caching over a list that used to have stations.
        if (fresh.isEmpty() && cached != null) return read(cached)
        mutex.withLock {
            val map = loaded()
            map[key] = build(fresh)
            // Bound the file: keep the most recently fetched lists.
            if (map.size > MAX_ENTRIES) {
                map.entries.sortedBy { it.value.fetchedAt }.take(map.size - MAX_ENTRIES).forEach { map.remove(it.key) }
            }
            persist(map)
        }
        return fresh
    }

    private suspend fun loaded(): MutableMap<String, Entry> {
        entries?.let { return it }
        val read = withContext(Dispatchers.IO) {
            runCatching { json.decodeFromString<Snapshot>(file.readText()).entries }.getOrNull()
        }
        return (read?.toMutableMap() ?: mutableMapOf()).also { entries = it }
    }

    private suspend fun persist(map: Map<String, Entry>) = withContext(Dispatchers.IO) {
        runCatching { file.writeText(json.encodeToString(Snapshot.serializer(), Snapshot(map))) }
    }

    companion object {
        const val DEFAULT_TTL_MS = 6 * 60 * 60 * 1000L
        private const val MAX_ENTRIES = 60
    }
}
