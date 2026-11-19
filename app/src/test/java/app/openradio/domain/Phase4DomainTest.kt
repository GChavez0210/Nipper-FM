package app.openradio.domain

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase4DomainTest {
    private val now = 10_000_000_000L
    private val hour = 60 * 60 * 1000L

    // ---- Stream URL preference -------------------------------------------------------------

    @Test fun keepsRequestedUrlWhenUnknownOrHealthy() {
        assertEquals("http://a", StreamUrlPreference.choose("http://a", emptyList(), now))
        val health = listOf(
            UrlHealth("http://a", lastSuccessAt = now - hour, lastFailureAt = null, failureCount = 0),
            UrlHealth("http://b", lastSuccessAt = now - 1, lastFailureAt = null, failureCount = 0),
        )
        assertEquals("http://a", StreamUrlPreference.choose("http://a", health, now))
    }

    @Test fun switchesToMostRecentKnownGoodUrlAfterRecentFailure() {
        val health = listOf(
            UrlHealth("http://a", lastSuccessAt = now - 5 * hour, lastFailureAt = now - hour, failureCount = 2),
            UrlHealth("http://b", lastSuccessAt = now - 3 * hour, lastFailureAt = null, failureCount = 0),
            UrlHealth("http://c", lastSuccessAt = now - 2 * hour, lastFailureAt = null, failureCount = 0),
            UrlHealth("http://d", lastSuccessAt = now - 4 * hour, lastFailureAt = now - 10, failureCount = 1),
        )
        assertEquals("http://c", StreamUrlPreference.choose("HTTP://A ", health, now))
    }

    @Test fun oldOrRecoveredFailuresAreForgotten() {
        val old = UrlHealth("http://a", null, now - StreamUrlPreference.FAILURE_MEMORY_MS - 1, 3)
        val recovered = UrlHealth("http://a", now - 10, now - hour, 0)
        assertFalse(StreamUrlPreference.recentlyFailed(old, now))
        assertFalse(StreamUrlPreference.recentlyFailed(recovered, now))
        assertTrue(StreamUrlPreference.recentlyFailed(UrlHealth("http://a", null, now - hour, 1), now))
    }

    @Test fun keepsRequestedWhenNoAlternativeIsKnownGood() {
        val health = listOf(UrlHealth("http://a", null, now - hour, 1), UrlHealth("http://b", null, null, 0))
        assertEquals("http://a", StreamUrlPreference.choose("http://a", health, now))
    }

    @Test fun ordersFeedsKnownGoodThenUntestedThenFailed() {
        fun feed(url: String) = StreamFeed("s", url, url, "MP3", 128, false)
        val feeds = listOf(feed("u1"), feed("failed"), feed("good-old"), feed("u2"), feed("good-new"))
        val health = listOf(
            UrlHealth("failed", null, now - hour, 1),
            UrlHealth("good-old", now - 3 * hour, null, 0),
            UrlHealth("good-new", now - hour, null, 0),
        )
        assertEquals(
            listOf("good-new", "good-old", "u1", "u2", "failed"),
            StreamUrlPreference.orderFeeds(feeds, health, now).map { it.streamUrl },
        )
    }

    // ---- Data usage ------------------------------------------------------------------------

    @Test fun monthKeysAndRollover() {
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals("1970-01", DataUsageMath.monthKey(0, utc))
        assertEquals("2026-11", DataUsageMath.monthKey(1_795_000_000_000L, utc))
        assertEquals("2025-12", DataUsageMath.previousMonth("2026-01"))
        assertEquals("2026-10", DataUsageMath.previousMonth("2026-11"))
    }

    @Test fun dropsOnlyMonthsBeyondRetention() {
        val keys = (1..12).map { "2026-%02d".format(it) } + listOf("2025-11", "2025-12")
        assertEquals(listOf("2025-11"), DataUsageMath.monthsToDrop(keys, keep = 13))
    }

    @Test fun formatsBytes() {
        assertEquals("512 B", DataUsageMath.format(512))
        assertEquals("2 KB", DataUsageMath.format(2048))
        assertEquals("1.5 MB", DataUsageMath.format(1_572_864))
        assertEquals("1.00 GB", DataUsageMath.format(1L shl 30))
        assertEquals(3L, MonthlyUsage("2026-11", 1, 2).totalBytes)
    }
}
