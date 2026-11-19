package app.openradio.data.remote

import app.openradio.domain.Country
import app.openradio.domain.Station
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

@Serializable
data class StationDto(
    val stationuuid: String,
    val name: String = "",
    val url: String = "",
    @SerialName("url_resolved") val urlResolved: String = "",
    val favicon: String = "",
    val tags: String = "",
    val country: String = "",
    val countrycode: String = "",
    val state: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
    val hls: Int = 0,
    val clicktrend: Int = 0,
) {
    fun toStation(): Station? {
        val playable = urlResolved.ifBlank { url }
        if (stationuuid.isBlank() || playable.isBlank()) return null
        return Station(
            id = stationuuid,
            name = name.trim().ifEmpty { "Unnamed station" },
            streamUrl = playable,
            faviconUrl = favicon.takeIf { it.startsWith("http") },
            country = country.takeIf { it.isNotBlank() },
            countryCode = countrycode.takeIf { it.isNotBlank() },
            state = state.takeIf { it.isNotBlank() },
            tags = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            codec = codec.takeIf { it.isNotBlank() },
            bitrate = bitrate,
            isHls = hls == 1,
        )
    }
}

@Serializable
data class CountryDto(
    val name: String = "",
    @SerialName("iso_3166_1") val code: String = "",
    val stationcount: Int = 0,
)

@Serializable
data class TagDto(val name: String = "", val stationcount: Int = 0)

@Serializable
data class ServerDto(val name: String = "")

interface RadioBrowserApi {
    @GET("json/stations/search")
    suspend fun searchStations(
        @Query("name") name: String? = null,
        @Query("countrycode") countryCode: String? = null,
        @Query("tag") tag: String? = null,
        @Query("tagExact") tagExact: Boolean? = null,
        @Query("order") order: String = "clicktrend",
        @Query("reverse") reverse: Boolean = true,
        @Query("hidebroken") hideBroken: Boolean = true,
        @Query("limit") limit: Int = 30,
        @Query("offset") offset: Int = 0,
    ): List<StationDto>

    @GET("json/stations/byuuid/{uuid}")
    suspend fun stationByUuid(@Path("uuid") uuid: String): List<StationDto>

    @GET("json/countries")
    suspend fun countries(@Query("hidebroken") hideBroken: Boolean = true): List<CountryDto>

    @GET("json/tags")
    suspend fun tags(
        @Query("order") order: String = "stationcount",
        @Query("reverse") reverse: Boolean = true,
        @Query("hidebroken") hideBroken: Boolean = true,
        @Query("limit") limit: Int = 60,
    ): List<TagDto>

    /** Registers a listen (click) for the station; recommended by the API when playback starts. */
    @GET("json/url/{uuid}")
    suspend fun registerClick(@Path("uuid") uuid: String): kotlinx.serialization.json.JsonElement
}

internal fun CountryDto.toCountry(): Country? =
    if (name.isBlank() || code.isBlank()) null else Country(name, code, stationcount)
