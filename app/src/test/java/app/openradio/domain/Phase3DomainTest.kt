package app.openradio.domain

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase3DomainTest {

    private fun saved(name: String, country: String?, playedAt: Long?, tags: List<String> = emptyList()) =
        SavedStation(
            Station(id = name, name = name, streamUrl = "http://x/$name", country = country, tags = tags),
            isFavorite = true,
            pinnedOrder = null,
            lastPlayedAt = playedAt,
        )

    private val favs = listOf(
        saved("kexp", "United States", 300, listOf("indie")),
        saved("Radio Paradise", "United States", null, listOf("eclectic")),
        saved("FIP", "France", 500, listOf("jazz")),
        saved("Custom", null, 100),
    )

    // ---- Favorites search & sorting --------------------------------------------------------

    @Test fun recentlyAddedKeepsIncomingOrder() {
        assertEquals(favs, FavoritesQuery.apply(favs, "", FavoriteSort.RecentlyAdded))
    }

    @Test fun sortsByNameCaseInsensitively() {
        assertEquals(
            listOf("Custom", "FIP", "kexp", "Radio Paradise"),
            FavoritesQuery.apply(favs, "", FavoriteSort.Name).map { it.station.name },
        )
    }

    @Test fun sortsByCountryThenNameWithMissingCountryLast() {
        assertEquals(
            listOf("FIP", "kexp", "Radio Paradise", "Custom"),
            FavoritesQuery.apply(favs, "", FavoriteSort.Country).map { it.station.name },
        )
    }

    @Test fun sortsByRecentlyPlayedWithNeverPlayedLast() {
        assertEquals(
            listOf("FIP", "kexp", "Custom", "Radio Paradise"),
            FavoritesQuery.apply(favs, "", FavoriteSort.RecentlyPlayed).map { it.station.name },
        )
    }

    @Test fun searchMatchesNameCountryAndTags() {
        assertEquals(listOf("Radio Paradise"), FavoritesQuery.apply(favs, " paradise ", FavoriteSort.Name).map { it.station.name })
        assertEquals(listOf("FIP"), FavoritesQuery.apply(favs, "france", FavoriteSort.Name).map { it.station.name })
        assertEquals(listOf("FIP"), FavoritesQuery.apply(favs, "JAZZ", FavoriteSort.Name).map { it.station.name })
        assertTrue(FavoritesQuery.apply(favs, "nothing-like-this", FavoriteSort.Name).isEmpty())
    }

    // ---- Volume trim -----------------------------------------------------------------------

    @Test fun volumeTrimSplitsCutsAndBoosts() {
        assertEquals(1f, VolumeTrim.attenuationFactor(0), 0.0001f)
        assertEquals(1f, VolumeTrim.attenuationFactor(4), 0.0001f)
        assertEquals(0.501f, VolumeTrim.attenuationFactor(-6), 0.001f)
        assertEquals(0, VolumeTrim.boostMillibels(-6))
        assertEquals(400, VolumeTrim.boostMillibels(4))
        assertEquals(VolumeTrim.MAX_DB * 100, VolumeTrim.boostMillibels(40))
        assertEquals(VolumeTrim.MIN_DB, VolumeTrim.clamp(-99))
        assertEquals("+3 dB", VolumeTrim.label(3))
        assertEquals("-3 dB", VolumeTrim.label(-3))
    }

    // ---- Sleep cycle -----------------------------------------------------------------------

    @Test fun sleepCycleStepsUpAndWrapsToOff() {
        assertEquals(15, SleepCycle.next(0))
        assertEquals(30, SleepCycle.next(15 * 60_000L - 500)) // just started at 15
        assertEquals(30, SleepCycle.next(14 * 60_000L))
        assertEquals(30, SleepCycle.next(15 * 60_000L))
        assertEquals(60, SleepCycle.next(29 * 60_000L + 40_000))
        assertEquals(60, SleepCycle.next(20 * 60_000L)) // after +15 from the phone
        assertEquals(0, SleepCycle.next(60 * 60_000L))
        assertEquals(0, SleepCycle.next(90 * 60_000L))
    }

    // ---- Sleep at end of track -------------------------------------------------------------

    @Test fun endOfTrackStopsOnTitleChangeOnly() = runTest {
        var expirations = 0
        val timer = SleepTimer(this, { testScheduler.currentTime }, { expirations++ }, {})
        timer.stopAfterTrack("Artist - Song A")
        assertTrue(timer.endOfTrack)
        timer.onTrackTitle("artist - song a ")
        timer.onTrackTitle("")
        timer.onTrackTitle(null)
        assertEquals(0, expirations)
        timer.onTrackTitle("Artist - Song B")
        assertEquals(1, expirations)
        assertFalse(timer.endOfTrack)
        assertNull(timer.endsAtMs)
        advanceTimeBy(SleepTimer.END_OF_TRACK_CAP_MINUTES * 60_000L + 1_000)
        assertEquals(1, expirations)
    }

    @Test fun endOfTrackFallsBackToCap() = runTest {
        var expirations = 0
        val timer = SleepTimer(this, { testScheduler.currentTime }, { expirations++ }, {})
        timer.stopAfterTrack("Long DJ set", maxWaitMinutes = 1)
        runCurrent()
        advanceTimeBy(61_000)
        assertEquals(1, expirations)
        assertFalse(timer.endOfTrack)
    }

    @Test fun endOfTrackRearmsOnStationChange() = runTest {
        var expirations = 0
        val timer = SleepTimer(this, { testScheduler.currentTime }, { expirations++ }, {})
        timer.stopAfterTrack("Song A")
        timer.onStationChanged()
        timer.onTrackTitle("Other station song")
        assertEquals(0, expirations)
        timer.onTrackTitle("Next song")
        assertEquals(1, expirations)
    }

    @Test fun regularTimerClearsEndOfTrack() = runTest {
        val timer = SleepTimer(this, { testScheduler.currentTime }, {}, {})
        timer.stopAfterTrack("Song A")
        timer.start(15)
        assertFalse(timer.endOfTrack)
        timer.cancel()
    }

    // ---- Error log -------------------------------------------------------------------------

    @Test fun errorLogLineRoundTripsAndFlattensControlChars() {
        val entry = ErrorLogEntry(1_700_000_000_000, "Playback error", "KEXP\t90.3", "abc", "line1\nline2", "http://x")
        val parsed = ErrorLogEntry.parse(entry.toLine())!!
        assertEquals("KEXP 90.3", parsed.stationName)
        assertEquals("line1 line2", parsed.detail)
        assertEquals("http://x", parsed.url)
        assertNull(ErrorLogEntry.parse("garbage"))
    }

    @Test fun errorLogKeepsNewestAndExportsNewestFirst() {
        assertEquals(listOf("3", "4"), ErrorLogFormat.trim(listOf("1", "2", "3", "4"), max = 2))
        val text = ErrorLogFormat.export(
            listOf(
                ErrorLogEntry(1_000, "Old", null, null, null),
                ErrorLogEntry(2_000, "New", "FIP", "id", "detail"),
            ),
            appVersion = "0.1.0",
            device = "Test",
        )
        assertTrue(text.indexOf("New") < text.indexOf("Old"))
        assertTrue(text.contains("1970-01-01 00:00:02Z"))
    }
}
