package app.openradio.domain

/** Artist and song title read from in-stream metadata. [artist] is null when the stream sends only a title. */
data class TrackInfo(
    val title: String,
    val artist: String? = null,
) {
    /** The single-line form used for Recently On Air, saved tracks, and copying ("Artist - Title"). */
    val display: String get() = if (artist.isNullOrBlank()) title else "$artist - $title"

    /** Artwork lookups need both parts; a bare title is too often a slogan, jingle, or show name. */
    val isSong: Boolean get() = !artist.isNullOrBlank() && title.isNotBlank()
}

object TrackInfoParser {
    private val separators = listOf(" - ", " – ", " — ", " ~ ", " / ")

    /**
     * Splits stream metadata into artist and title.
     *
     * ID3 (HLS) streams send separate [artistField] and [titleField] values. ICY (Icecast and
     * Shoutcast) streams send one `StreamTitle`, usually "Artist - Title". Returns null when the
     * title is blank or only repeats the station name.
     */
    fun parse(titleField: String?, artistField: String?, stationName: String?): TrackInfo? {
        val title = titleField?.let(::clean).orEmpty()
        if (title.isEmpty() || isStationName(title, stationName)) return null
        val artist = artistField?.let(::clean)?.takeIf { it.isNotEmpty() && !isStationName(it, stationName) }
        if (artist != null) return TrackInfo(title, artist)
        for (sep in separators) {
            val at = title.indexOf(sep)
            if (at <= 0) continue
            val a = clean(title.substring(0, at))
            val t = clean(title.substring(at + sep.length))
            if (a.isNotEmpty() && t.isNotEmpty()) return TrackInfo(t, a)
        }
        return TrackInfo(title)
    }

    private fun clean(s: String): String = s.replace(Regex("\\s+"), " ").trim().trim('-', '–', '—', '|').trim()

    private fun isStationName(s: String, stationName: String?): Boolean =
        stationName != null && s.equals(stationName.trim(), ignoreCase = true)
}

/** A cover-art search hit (for example, one iTunes Search API result). */
data class ArtworkCandidate(val artist: String, val title: String, val artworkUrl: String)

object ArtworkMatcher {
    /**
     * Picks the first candidate whose artist and title both match the track loosely, so a
     * search for a little-known song doesn't show an unrelated album cover.
     */
    fun pick(track: TrackInfo, candidates: List<ArtworkCandidate>): ArtworkCandidate? {
        val artist = VoiceMatch.normalize(track.artist ?: return null)
        val title = VoiceMatch.normalize(stripDecorations(track.title))
        if (artist.isEmpty() || title.isEmpty()) return null
        return candidates.firstOrNull { c ->
            looselyEqual(artist, VoiceMatch.normalize(c.artist)) &&
                looselyEqual(title, VoiceMatch.normalize(stripDecorations(c.title)))
        }
    }

    /** iTunes returns 100 px artwork; the same URL serves larger sizes. */
    fun upscale(url: String, px: Int = 600): String = url.replace(Regex("/\\d+x\\d+bb\\."), "/${px}x${px}bb.")

    // "Song (Radio Edit)" and "Song [feat. X]" should still match "Song".
    private fun stripDecorations(s: String): String = s.replace(Regex("[(\\[].*?[)\\]]"), " ").trim()

    private fun looselyEqual(a: String, b: String): Boolean =
        a.isNotEmpty() && b.isNotEmpty() && (a == b || a.contains(b) || b.contains(a))
}
