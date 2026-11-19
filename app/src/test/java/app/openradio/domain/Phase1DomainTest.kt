package app.openradio.domain

import app.openradio.ui.theme.Accent
import app.openradio.ui.theme.DarkStyle
import app.openradio.ui.theme.ThemeMode
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase1DomainTest {

    @Test fun surprisePickerFavorsUnplayedAndSkipsCurrent() {
        val s1 = Station("1", "Played One", "https://a.example/stream")
        val s2 = Station("2", "Played Two", "https://b.example/stream")
        val s3 = Station("3", "Fresh Three", "https://c.example/stream")
        val candidates = listOf(s1, s2, s3)

        val picked = SurprisePicker.pick(
            candidates = candidates,
            recentIds = listOf("1", "2"),
            currentId = "1",
            random = Random(42),
        )
        assertEquals("3", picked?.id)
    }

    @Test fun surprisePickerFallsBackWhenAllWerePlayedRecently() {
        val s1 = Station("1", "One", "https://a.example/stream")
        val s2 = Station("2", "Two", "https://b.example/stream")
        val picked = SurprisePicker.pick(
            candidates = listOf(s1, s2),
            recentIds = listOf("1", "2"),
            currentId = "1",
            random = Random(0),
        )
        assertEquals("2", picked?.id)
    }

    @Test fun customStreamBuilderValidatesAndDetectsHls() {
        assertFalse(CustomStreamBuilder.isValidUrl("ftp://bad.example/stream"))
        assertFalse(CustomStreamBuilder.isValidUrl("not a url"))
        assertTrue(CustomStreamBuilder.isValidUrl("https://icecast.walmradio.com:8443/classic"))
        assertTrue(CustomStreamBuilder.isHlsUrl("https://stream.example.com/live/master.m3u8?token=1"))
        assertFalse(CustomStreamBuilder.isHlsUrl("https://stream.example.com/live.mp3"))

        val hlsStation = CustomStreamBuilder.build("My HLS", "https://stream.example.com/live.m3u8", id = "custom:1")
        assertNotNull(hlsStation)
        assertTrue(hlsStation!!.isCustom)
        assertTrue(hlsStation.isHls)

        val icecastStation = CustomStreamBuilder.build("My Icecast", "http://stream.example.com:8000/radio", id = "custom:2")
        assertNotNull(icecastStation)
        assertTrue(icecastStation!!.isCustom)
        assertFalse(icecastStation.isHls)
    }

    @Test fun expandedSearchRecognizesFrequencyCallsignAndMergesByCity() {
        assertTrue(ExpandedSearch.isFrequencyQuery("90.3"))
        assertTrue(ExpandedSearch.isFrequencyQuery("88.5 FM"))
        assertFalse(ExpandedSearch.isFrequencyQuery("Seattle"))
        assertTrue(ExpandedSearch.isCallsignQuery("KEXP"))
        assertTrue(ExpandedSearch.isCallsignQuery("WQXR-FM"))

        val byName = listOf(Station("1", "Radio Something", "u", state = "Oregon"))
        val byCity = listOf(Station("2", "KEXP 90.3", "u", state = "Seattle"), Station("1", "Radio Something", "u"))
        val byTag = listOf(Station("3", "Seattle Jazz", "u", tags = listOf("seattle", "jazz")))

        val merged = ExpandedSearch.mergeAndRank("Seattle", byName, byCity, byTag)
        assertEquals(3, merged.size)
        // Exact state ("Seattle") and name containing "Seattle" rank ahead of unrelated Oregon match
        assertTrue(merged.first().id in setOf("2", "3"))
    }

    @Test fun backupCodecRoundTripsAndSummarizesFavoritesGroupsPresetsCustomAndAppearance() {
        val fav1 = SavedStation(
            station = Station("s1", "KEXP", "https://kexp.example/stream", latitude = 47.62, longitude = -122.35),
            isFavorite = true,
            pinnedOrder = 0,
            lastPlayedAt = 1000L,
            groupName = "Commute",
        )
        val customFav = SavedStation(
            station = Station("custom:abc", "Late Synth", "https://synth.example/live.m3u8", isHls = true, isCustom = true),
            isFavorite = true,
            pinnedOrder = 1,
            lastPlayedAt = null,
            groupName = "Late Night",
        )
        val payload = BackupPayload(
            version = 1,
            exportedAtMs = 123456L,
            themeMode = ThemeMode.Dark.name,
            darkStyle = DarkStyle.Obsidian.name,
            accent = Accent.OffWhite.name,
            groups = listOf("Commute", "Late Night", "Weekend"),
            stations = listOf(BackupStationRecord.fromSaved(fav1), BackupStationRecord.fromSaved(customFav)),
        )

        val encoded = BackupCodec.encode(payload)
        val decoded = BackupCodec.decode(encoded)
        val summary = BackupCodec.summarize(decoded)

        assertEquals(2, summary.favoriteCount)
        assertEquals(3, summary.groupCount)
        assertEquals(2, summary.presetCount)
        assertEquals(1, summary.customStreamCount)
        assertEquals(Accent.OffWhite, decoded.toAppearancePrefs().accent)
        assertEquals("Commute", decoded.stations.first().toSavedStation().groupName)
    }

    @Test fun coordinatesValidationAndOnAirCopyFormat() {
        assertFalse(Station("1", "No Geo", "u").hasCoordinates)
        assertFalse(Station("2", "Null Island", "u", latitude = 0.0, longitude = 0.0).hasCoordinates)
        assertTrue(Station("3", "Valid Geo", "u", latitude = 47.6062, longitude = -122.3321).hasCoordinates)

        val entry = OnAirEntry(1L, "s1", "KEXP 90.3", "Boards of Canada - Roygbiv", 1000L, isBookmarked = true)
        assertEquals("Boards of Canada - Roygbiv — KEXP 90.3", entry.copyText)
    }
}
