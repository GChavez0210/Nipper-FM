package app.openradio.data.remote

import app.openradio.domain.ArtworkCandidate
import app.openradio.domain.ArtworkMatcher
import app.openradio.domain.TrackInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Looks up cover art for a track through the iTunes Search API, which needs no key. Results,
 * including misses, are cached in memory so a song repeated on air costs one request.
 */
class TrackArtworkClient(
    private val http: OkHttpClient,
    private val baseUrl: String = "https://itunes.apple.com/search",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > CACHE_SIZE
    }

    /** Returns a 600 px artwork URL, or null when the search finds no matching song. */
    suspend fun artworkFor(track: TrackInfo): String? {
        if (!track.isSong) return null
        val key = track.display.lowercase()
        mutex.withLock { cache[key] }?.let { return it.ifEmpty { null } }
        val found = runCatching { search(track) }.getOrElse { return null } // network errors are not cached
        mutex.withLock { cache[key] = found.orEmpty() }
        return found
    }

    private suspend fun search(track: TrackInfo): String? = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addQueryParameter("term", "${track.artist} ${track.title}")
            .addQueryParameter("media", "music")
            .addQueryParameter("entity", "song")
            .addQueryParameter("limit", "10")
            .build()
        val request = Request.Builder().url(url).header("User-Agent", RadioBrowserClient.USER_AGENT).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            val results = json.decodeFromString(SearchResponse.serializer(), body).results
            val candidates = results.mapNotNull { r ->
                val art = r.artworkUrl100 ?: return@mapNotNull null
                ArtworkCandidate(r.artistName.orEmpty(), r.trackName.orEmpty(), art)
            }
            ArtworkMatcher.pick(track, candidates)?.let { ArtworkMatcher.upscale(it.artworkUrl) }
        }
    }

    @Serializable
    private data class SearchResponse(val results: List<Result> = emptyList())

    @Serializable
    private data class Result(
        val artistName: String? = null,
        val trackName: String? = null,
        val artworkUrl100: String? = null,
    )

    private companion object {
        const val CACHE_SIZE = 200
    }
}
