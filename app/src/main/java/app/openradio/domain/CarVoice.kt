package app.openradio.domain

import java.util.Locale

/** A genre or mood the directory can filter by, plus phrases people say for it. */
data class Genre(val label: String, val tag: String, val synonyms: List<String> = emptyList())

/** Single source for Explore's genre picker, Android Auto's Genres folder, and voice requests. */
object GenreCatalog {
    /** Moods shown first in the car; tags match [MoodCollection]. */
    val MOODS: List<Genre> = listOf(
        Genre(MoodCollection.Chill.label, MoodCollection.Chill.tag, listOf("chill", "chillout", "relaxing", "relax", "calm", "sleep", "sleepy", "ambient")),
        Genre(MoodCollection.Focus.label, MoodCollection.Focus.tag, listOf("focus", "study", "studying", "concentration", "lo fi", "lofi", "work")),
        Genre(MoodCollection.LateNight.label, MoodCollection.LateNight.tag, listOf("late night", "downtempo", "night")),
        Genre(MoodCollection.Jazz.label, MoodCollection.Jazz.tag, listOf("jazz and soul", "smooth jazz")),
        Genre(MoodCollection.Classical.label, MoodCollection.Classical.tag, listOf("classical", "orchestral", "symphony", "opera")),
        Genre(MoodCollection.Rock.label, MoodCollection.Rock.tag, listOf("indie rock", "indie and rock")),
        Genre(MoodCollection.Electronic.label, MoodCollection.Electronic.tag, listOf("synth", "synthwave", "edm")),
        Genre(MoodCollection.News.label, MoodCollection.News.tag, listOf("news and talk", "the news")),
    )

    val GENRES: List<Genre> = listOf(
        Genre("Pop", "pop", listOf("pop hits", "hits")),
        Genre("Rock", "rock", listOf("classic rock")),
        Genre("Hip Hop", "hip hop", listOf("hiphop", "hip-hop")),
        Genre("Top 40", "top 40", listOf("top forty", "chart", "charts")),
        Genre("Dance", "dance", listOf("party", "workout", "club")),
        Genre("Electronic", "electronic", listOf("electronica")),
        Genre("R&B", "r&b", listOf("r and b", "rnb", "r b", "rhythm and blues")),
        Genre("Rap", "rap"),
        Genre("Country", "country", listOf("country music", "country western")),
        Genre("Jazz", "jazz"),
        Genre("Classical", "classical"),
        Genre("K-Pop", "k-pop", listOf("kpop", "k pop", "korean pop")),
        Genre("Latin", "latin", listOf("latino", "latin music", "musica latina")),
        Genre("Reggaeton", "reggaeton"),
        Genre("Salsa", "salsa"),
        Genre("Afrobeats", "afrobeats", listOf("afrobeat", "afro beats")),
        Genre("Reggae", "reggae"),
        Genre("Soul", "soul"),
        Genre("Gospel", "gospel", listOf("christian", "worship")),
        Genre("Blues", "blues"),
        Genre("Folk", "folk"),
        Genre("Indie", "indie"),
        Genre("Alternative", "alternative", listOf("alt rock", "alt")),
        Genre("Metal", "metal", listOf("heavy metal")),
        Genre("Punk", "punk"),
        Genre("House", "house", listOf("house music", "deep house")),
        Genre("Techno", "techno"),
        Genre("Trance", "trance"),
        Genre("Ambient", "ambient"),
        Genre("Lo-Fi", "lofi", listOf("lo fi", "lo-fi", "lofi hip hop")),
        Genre("Disco", "disco"),
        Genre("Funk", "funk"),
        Genre("Oldies", "oldies", listOf("old school", "golden oldies", "classic hits")),
        Genre("J-Pop", "j-pop", listOf("jpop", "j pop", "japanese pop")),
        Genre("Bollywood", "bollywood", listOf("hindi", "indian")),
        Genre("News", "news"),
        Genre("Talk", "talk", listOf("talk radio", "podcasts")),
        Genre("Sports", "sports", listOf("sport", "football", "soccer", "baseball")),
    )

    fun labelFor(tag: String): String? =
        (MOODS + GENRES).firstOrNull { it.tag.equals(tag, ignoreCase = true) }?.label

    private val leading = Regex("^(?:(?:some|something|a|an|the|me|good|great|nice|more|any)\\s+)+")
    private val trailing = Regex("(?:\\s+(?:music|radio|stations?|songs|tunes|channel|channels|hits|please))+$")

    /** Strips "some … music"-style padding: "some good jazz music" -> "jazz". */
    fun core(phrase: String): String {
        val p = VoiceMatch.normalize(phrase).replace(leading, "").replace(trailing, "").trim()
        // A lone filler ("radio" in "radio from Mexico") names no genre or station.
        return if (p in fillerOnly) "" else p
    }

    private val fillerOnly = setOf("radio", "music", "station", "stations", "songs", "tunes", "something", "anything")

    /** Directory tag for a spoken genre or mood ("some relaxing music" -> "ambient"), or null. */
    fun match(phrase: String): String? {
        val p = core(phrase)
        if (p.isEmpty()) return null
        // Genres before moods so "jazz" maps to the jazz tag, not the "Jazz & Soul" mood label.
        for (g in GENRES + MOODS) {
            val names = listOf(g.label, g.tag) + g.synonyms
            if (names.any { VoiceMatch.normalize(it) == p }) return g.tag
        }
        return null
    }
}

/** Resolves spoken or written country names ("Mexico", "the UK", "USA") to ISO codes. */
object CountryNames {
    private val aliases = mapOf(
        "usa" to "US", "us" to "US", "u s" to "US", "u s a" to "US", "america" to "US",
        "united states of america" to "US", "the states" to "US",
        "uk" to "GB", "u k" to "GB", "england" to "GB", "britain" to "GB", "great britain" to "GB",
        "scotland" to "GB", "wales" to "GB", "northern ireland" to "GB",
        "holland" to "NL", "korea" to "KR", "south korea" to "KR", "russia" to "RU",
        "czech republic" to "CZ", "czechia" to "CZ", "ivory coast" to "CI", "vietnam" to "VN",
        "uae" to "AE", "emirates" to "AE",
    )

    private val byName: Map<String, String> by lazy {
        val locales = listOf(Locale.ENGLISH, Locale.getDefault()).distinct()
        val names = HashMap<String, String>()
        for (code in Locale.getISOCountries()) {
            val region = Locale.Builder().setRegion(code).build()
            for (l in locales) {
                val n = VoiceMatch.normalize(region.getDisplayCountry(l))
                if (n.isNotEmpty()) names.putIfAbsent(n, code)
            }
        }
        names + aliases
    }

    fun resolve(phrase: String): String? {
        val p = VoiceMatch.normalize(phrase).removePrefix("the ").trim()
        return if (p.isEmpty()) null else byName[p]
    }

    fun displayName(code: String, locale: Locale = Locale.getDefault()): String =
        runCatching { Locale.Builder().setRegion(code).build().getDisplayCountry(locale) }.getOrNull()
            ?.ifBlank { null } ?: code.uppercase(Locale.ROOT)
}

/**
 * What a spoken request asks for. Exactly one shape applies:
 * a named station ([stationQuery]), a genre/mood ([tag]), and/or a country ([countryCode]).
 */
data class VoiceIntent(val stationQuery: String?, val tag: String?, val countryCode: String?) {
    val isCategory: Boolean get() = stationQuery == null && (tag != null || countryCode != null)
}

object VoiceRequestParser {
    private val place = Regex("^(.*?)\\s+(?:in|from)\\s+(.+)$")

    fun parse(raw: String, resolveCountry: (String) -> String? = CountryNames::resolve): VoiceIntent {
        var rest = VoiceMatch.cleanQuery(raw)
        var country: String? = null
        place.find(rest)?.let { m ->
            resolveCountry(m.groupValues[2])?.let { code ->
                country = code
                rest = m.groupValues[1].trim()
            }
        }
        // "radio from Mexico" -> only a country; "jazz in France" -> genre + country.
        val core = GenreCatalog.core(rest)
        val tag = GenreCatalog.match(rest)
        return when {
            tag != null -> VoiceIntent(null, tag, country)
            core.isEmpty() && country != null -> VoiceIntent(null, null, country)
            else -> VoiceIntent(rest.ifEmpty { null }, null, country)
        }
    }
}
