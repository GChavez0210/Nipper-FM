package app.openradio.playback

import android.os.Bundle
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import app.openradio.domain.Station

/** Stable media IDs shared by the phone UI, the media session and Android Auto. */
object MediaIds {
    const val ROOT = "root"
    const val PRESETS = "presets"
    const val RECENTS = "recents"
    const val RECENT_ROOT = "recent_root"
    const val COUNTRIES = "countries"
    const val GENRES = "genres"
    private const val STATION_PREFIX = "station:"
    private const val COUNTRY_PREFIX = "country:"
    private const val GENRE_PREFIX = "genre:"

    fun station(uuid: String) = STATION_PREFIX + uuid
    fun stationId(mediaId: String): String? = mediaId.takeIf { it.startsWith(STATION_PREFIX) }?.removePrefix(STATION_PREFIX)

    fun country(code: String) = COUNTRY_PREFIX + code.uppercase()
    fun countryCode(mediaId: String): String? = mediaId.takeIf { it.startsWith(COUNTRY_PREFIX) }?.removePrefix(COUNTRY_PREFIX)

    fun genre(tag: String) = GENRE_PREFIX + tag
    fun genreTag(mediaId: String): String? = mediaId.takeIf { it.startsWith(GENRE_PREFIX) }?.removePrefix(GENRE_PREFIX)
}

/** Keys for session extras (service -> controllers) and custom commands (controllers -> service). */
object SessionKeys {
    const val CMD_SLEEP = "app.openradio.SLEEP_TIMER"
    const val CMD_SLEEP_ADD = "app.openradio.SLEEP_ADD_MINUTES"
    const val CMD_MOBILE_OVERRIDE = "app.openradio.MOBILE_DATA_OVERRIDE"
    const val CMD_SELECT_FEED = "app.openradio.SELECT_STREAM_FEED"
    /** Toggles favorite for the current station (notification, lock screen, Android Auto). */
    const val CMD_TOGGLE_FAVORITE = "app.openradio.TOGGLE_FAVORITE"
    /** Steps the sleep timer Off → 15 → 30 → 60 → Off (notification, lock screen, Android Auto). */
    const val CMD_SLEEP_CYCLE = "app.openradio.SLEEP_CYCLE"
    const val CMD_SLEEP_END_OF_TRACK = "app.openradio.SLEEP_END_OF_TRACK"
    const val CMD_SET_VOLUME_TRIM = "app.openradio.SET_VOLUME_TRIM"
    const val ARG_TRIM_DB = "trim_db"
    const val ARG_MINUTES = "minutes"
    const val ARG_FEED_URL = "feed_url"
    const val ARG_FEED_CODEC = "feed_codec"
    const val ARG_FEED_BITRATE = "feed_bitrate"
    const val ARG_FEED_HLS = "feed_hls"

    const val EXTRA_RECOVERY = "recovery"          // RecoveryPhase name
    const val EXTRA_ATTEMPT = "attempt"
    const val EXTRA_MAX_ATTEMPTS = "max_attempts"
    const val EXTRA_MESSAGE = "message"
    const val EXTRA_SLEEP_ENDS_AT = "sleep_ends_at" // wall-clock millis, 0 = none
    const val EXTRA_FAILURE_KIND = "failure_kind"   // FailureKind name
    const val EXTRA_DEVICE_ONLINE = "device_online"
    const val EXTRA_WIFI_BLOCKED = "wifi_blocked"
    const val EXTRA_AUDIO_SESSION_ID = "audio_session_id"
    const val EXTRA_SLEEP_END_OF_TRACK = "sleep_end_of_track"
    const val EXTRA_VOLUME_TRIM_DB = "volume_trim_db"
    /** Network bytes streamed since the current station started. */
    const val EXTRA_SESSION_BYTES = "session_bytes"
}

private const val X_URL = "x_stream_url"
private const val X_COUNTRY = "x_country"
private const val X_CC = "x_cc"
private const val X_STATE = "x_state"
private const val X_TAGS = "x_tags"
private const val X_CODEC = "x_codec"
private const val X_BITRATE = "x_bitrate"
private const val X_HLS = "x_hls"
private const val X_CUSTOM = "x_custom"
private const val X_LAT = "x_lat"
private const val X_LON = "x_lon"

/** Builds a media item. [playable] adds the stream URI so a player can load it; browse items omit it. */
fun Station.toMediaItem(playable: Boolean): MediaItem {
    val extras = bundleOf(
        X_URL to streamUrl, X_COUNTRY to country, X_CC to countryCode, X_STATE to state,
        X_TAGS to tags.joinToString(","), X_CODEC to codec, X_BITRATE to bitrate, X_HLS to isHls,
        X_CUSTOM to isCustom,
    ).apply {
        latitude?.let { putDouble(X_LAT, it) }
        longitude?.let { putDouble(X_LON, it) }
    }
    val metadata = MediaMetadata.Builder()
        // Playable items leave title unset so in-stream ICY track titles can populate it;
        // displayTitle and station keep the station name for notifications, car and UI.
        .apply { if (!playable) setTitle(name) }
        .setDisplayTitle(name)
        .setSubtitle(location.ifEmpty { null })
        .setStation(name)
        .setArtworkUri(faviconUrl?.toUri())
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
        .setExtras(extras)
        .build()
    val builder = MediaItem.Builder().setMediaId(MediaIds.station(id)).setMediaMetadata(metadata)
    if (playable) {
        builder.setUri(streamUrl)
        if (isHls || streamUrl.contains(".m3u8", ignoreCase = true)) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
    }
    return builder.build()
}

/** Reconstructs the station carried by a media item (works for items created by [toMediaItem]). */
fun MediaItem.toStation(): Station? {
    val id = MediaIds.stationId(mediaId) ?: return null
    val x: Bundle? = mediaMetadata.extras
    val url = localConfiguration?.uri?.toString() ?: x?.getString(X_URL) ?: return null
    return Station(
        id = id,
        name = mediaMetadata.station?.toString() ?: mediaMetadata.displayTitle?.toString()
            ?: mediaMetadata.title?.toString() ?: "Station",
        streamUrl = url,
        faviconUrl = mediaMetadata.artworkUri?.toString(),
        country = x?.getString(X_COUNTRY),
        countryCode = x?.getString(X_CC),
        state = x?.getString(X_STATE),
        tags = x?.getString(X_TAGS).orEmpty().split(',').filter { it.isNotBlank() },
        codec = x?.getString(X_CODEC),
        bitrate = x?.getInt(X_BITRATE) ?: 0,
        isHls = x?.getBoolean(X_HLS) ?: false,
        latitude = if (x?.containsKey(X_LAT) == true) x.getDouble(X_LAT) else null,
        longitude = if (x?.containsKey(X_LON) == true) x.getDouble(X_LON) else null,
        isCustom = x?.getBoolean(X_CUSTOM) ?: id.startsWith("custom:"),
    )
}
