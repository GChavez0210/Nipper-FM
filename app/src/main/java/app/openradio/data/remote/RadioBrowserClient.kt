package app.openradio.data.remote

import app.openradio.domain.Country
import app.openradio.domain.Station
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.HttpException
import retrofit2.Retrofit

/** The seam used by ViewModels and the service; faked in tests. */
interface StationDirectory {
    suspend fun search(
        query: String?,
        countryCode: String?,
        tag: String?,
        offset: Int,
        limit: Int,
    ): List<Station>

    suspend fun byUuid(uuid: String): Station?
    suspend fun countries(): List<Country>
    suspend fun tags(): List<String>
    suspend fun registerClick(uuid: String)
}

/**
 * Radio Browser client following the project's API guidance:
 *  - discovers mirrors from all.api.radio-browser.info/json/servers and shuffles them,
 *  - falls back to a built-in list when discovery fails,
 *  - fails over to the next mirror on IO errors and 5xx,
 *  - always sends a descriptive User-Agent.
 */
class RadioBrowserClient(
    private val httpClient: OkHttpClient,
    private val discoveryUrl: String = DISCOVERY_URL,
    private val fallbackHosts: List<String> = FALLBACK_HOSTS,
    private val scheme: String = "https",
    private val userAgent: String = USER_AGENT,
    private val shuffle: (List<String>) -> List<String> = { it.shuffled() },
) : StationDirectory {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val client = httpClient.newBuilder()
        .addInterceptor(Interceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
        })
        .build()

    private val apis = HashMap<String, RadioBrowserApi>()
    @Volatile private var hosts: List<String>? = null
    @Volatile private var preferred: String? = null

    private suspend fun hostList(): List<String> {
        hosts?.let { return it }
        val discovered = runCatching { discover() }.getOrNull().orEmpty()
        val list = shuffle(discovered.ifEmpty { fallbackHosts })
        hosts = list
        return list
    }

    private suspend fun discover(): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(discoveryUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("discovery HTTP ${response.code}")
            json.decodeFromString<List<ServerDto>>(response.body?.string().orEmpty())
                .map { it.name }.filter { it.isNotBlank() }.distinct()
        }
    }

    private fun apiFor(host: String): RadioBrowserApi = synchronized(apis) {
        apis.getOrPut(host) {
            Retrofit.Builder()
                .baseUrl("$scheme://$host/")
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(RadioBrowserApi::class.java)
        }
    }

    /** Runs [block] against mirrors in order, moving on after IO errors and 5xx responses. */
    internal suspend fun <T> withFailover(block: suspend (RadioBrowserApi) -> T): T {
        val all = hostList()
        val ordered = preferred?.let { p -> listOf(p) + all.filter { it != p } } ?: all
        var last: Throwable? = null
        for (host in ordered) {
            try {
                return block(apiFor(host)).also { preferred = host }
            } catch (e: HttpException) {
                if (e.code() < 500) throw e
                last = e
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("No Radio Browser servers available")
    }

    override suspend fun search(query: String?, countryCode: String?, tag: String?, offset: Int, limit: Int) =
        withFailover {
            it.searchStations(
                name = query?.takeIf { q -> q.isNotBlank() },
                countryCode = countryCode,
                tag = tag,
                tagExact = if (tag != null) true else null,
                offset = offset,
                limit = limit,
            )
        }.mapNotNull { it.toStation() }

    override suspend fun byUuid(uuid: String): Station? =
        withFailover { it.stationByUuid(uuid) }.firstNotNullOfOrNull { it.toStation() }

    override suspend fun countries(): List<Country> =
        withFailover { it.countries() }.mapNotNull { it.toCountry() }.sortedBy { it.name }

    override suspend fun tags(): List<String> =
        withFailover { it.tags() }.map { it.name }.filter { it.isNotBlank() }

    override suspend fun registerClick(uuid: String) {
        runCatching { withFailover { it.registerClick(uuid) } }
    }

    companion object {
        const val DISCOVERY_URL = "https://all.api.radio-browser.info/json/servers"
        const val USER_AGENT = "OpenRadio/0.1 (Android)"
        val FALLBACK_HOSTS = listOf(
            "de1.api.radio-browser.info",
            "de2.api.radio-browser.info",
            "fi1.api.radio-browser.info",
            "at1.api.radio-browser.info",
        )
    }
}
