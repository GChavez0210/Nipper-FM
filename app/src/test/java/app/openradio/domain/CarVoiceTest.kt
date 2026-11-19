package app.openradio.domain

import app.openradio.data.CarBrowseCache
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CarVoiceTest {

    // ---- Query cleanup ---------------------------------------------------------------------

    @Test fun stripsAssistantPhrasing() {
        assertEquals("kexp", VoiceMatch.cleanQuery("Hey Google, play KEXP on Nipper FM"))
        assertEquals("radio paradise", VoiceMatch.cleanQuery("can you put on radio paradise please"))
        assertEquals("", VoiceMatch.cleanQuery("play"))
        assertEquals("", VoiceMatch.cleanQuery("play the radio"))
        assertEquals("kexp", VoiceMatch.cleanQuery("tune in to K E X P"))
    }

    @Test fun spokenNumbersBecomeDigits() {
        assertEquals("kiss 92", VoiceMatch.numberWordsToDigits("kiss ninety two"))
        assertEquals("90 3", VoiceMatch.numberWordsToDigits("ninety point three"))
        assertEquals("101 1", VoiceMatch.numberWordsToDigits("one oh one point one"))
        assertEquals("101", VoiceMatch.numberWordsToDigits("one hundred and one"))
        assertEquals("radio 1", VoiceMatch.numberWordsToDigits("radio one"))
        assertEquals("oh wow", VoiceMatch.numberWordsToDigits("oh wow"))
        assertEquals("kexp", VoiceMatch.joinSpelledLetters("k e x p"))
        assertEquals("bbc radio 2", VoiceMatch.cleanQuery("b b c radio two"))
    }

    // ---- Fuzzy station matching ------------------------------------------------------------

    private val stations = listOf(
        Station("1", "KEXP 90.3 FM", "u"),
        Station("2", "Radio Paradise", "u"),
        Station("3", "BBC Radio 1", "u"),
        Station("4", "Jazz24", "u"),
        Station("5", "Kiss 92", "u"),
    )

    @Test fun matchesSpokenFormsOfNames() {
        assertEquals("1", VoiceMatch.best("play K E X P", stations)?.id)
        assertEquals("1", VoiceMatch.best("ninety point three", stations)?.id)
        assertEquals("3", VoiceMatch.best("BBC radio one", stations)?.id)
        assertEquals("5", VoiceMatch.best("kiss ninety two", stations)?.id)
    }

    @Test fun toleratesMisspellings() {
        assertEquals("2", VoiceMatch.best("radio paradize", stations)?.id)
        assertEquals("4", VoiceMatch.best("jaz 24", stations)?.id)
        assertNull(VoiceMatch.best("completely different", stations))
    }

    @Test fun exactBeatsFuzzyAndKeepsPreferenceOnTies() {
        val list = listOf(Station("a", "Radio Paradise", "u"), Station("b", "Radio Paradise", "u"))
        assertEquals("a", VoiceMatch.best("radio paradise", list)?.id)
        val scored = VoiceMatch.bestScored("radio paradise", stations)!!
        assertTrue(scored.score >= VoiceMatch.STRONG_SCORE)
    }

    // ---- Genre, mood and country requests --------------------------------------------------

    @Test fun recognizesGenresAndMoods() {
        assertEquals("jazz", GenreCatalog.match("some jazz"))
        assertEquals("jazz", GenreCatalog.match("jazz music"))
        assertEquals("ambient", GenreCatalog.match("something relaxing"))
        assertEquals("lofi", GenreCatalog.match("study music"))
        assertEquals("r&b", GenreCatalog.match("R and B"))
        assertEquals("hip hop", GenreCatalog.match("hip-hop"))
        assertEquals("news", GenreCatalog.match("the news"))
        assertNull(GenreCatalog.match("radio paradise"))
        assertNull(GenreCatalog.match("jazz fm"))
    }

    @Test fun resolvesCountryNamesAndAliases() {
        assertEquals("MX", CountryNames.resolve("Mexico"))
        assertEquals("GB", CountryNames.resolve("the UK"))
        assertEquals("US", CountryNames.resolve("america"))
        assertEquals("FR", CountryNames.resolve("france"))
        assertNull(CountryNames.resolve("seattle"))
    }

    @Test fun parsesCategoryAndStationRequests() {
        assertEquals(VoiceIntent(null, "jazz", null), VoiceRequestParser.parse("play some jazz"))
        assertEquals(VoiceIntent(null, "jazz", "FR"), VoiceRequestParser.parse("play jazz from France"))
        assertEquals(VoiceIntent(null, null, "MX"), VoiceRequestParser.parse("play radio from Mexico"))
        assertEquals(VoiceIntent(null, "news", "GB"), VoiceRequestParser.parse("put on news in the UK"))
        assertEquals(VoiceIntent("kexp from seattle", null, null), VoiceRequestParser.parse("play KEXP from Seattle"))
        assertEquals(VoiceIntent("radio paradise", null, null), VoiceRequestParser.parse("play radio paradise"))
        assertTrue(VoiceRequestParser.parse("some relaxing music").isCategory)
    }

    // ---- Car browse cache ------------------------------------------------------------------

    @Test fun cacheServesFreshThenStaleOnFailure() = runTest {
        var now = 0L
        val dir = Files.createTempDirectory("carcache").toFile()
        val cache = CarBrowseCache(dir, clock = { now }, ttlMs = 1_000)
        val jazz = listOf(Station("j1", "Jazz One", "http://j1"))
        var calls = 0

        assertEquals(jazz, cache.stations("cat:jazz:") { calls++; jazz })
        assertEquals(jazz, cache.stations("cat:jazz:") { calls++; emptyList() })
        assertEquals(1, calls) // fresh: no second fetch

        now = 5_000 // stale; directory down
        assertEquals(jazz, cache.stations("cat:jazz:") { calls++; throw IOException("offline") })
        assertEquals(2, calls)

        // A new instance reads the file written by the first one.
        val reopened = CarBrowseCache(dir, clock = { now }, ttlMs = 1_000)
        assertEquals(jazz, reopened.stations("cat:jazz:") { throw IOException("offline") })
        assertEquals("Jazz One", reopened.findStation("j1")?.name)
        assertEquals(jazz, reopened.listContaining("j1"))

        try {
            reopened.stations("cat:rock:") { throw IOException("offline") }
            fail("Expected the error when nothing is cached")
        } catch (_: IOException) {
        }
        dir.deleteRecursively()
    }
}
