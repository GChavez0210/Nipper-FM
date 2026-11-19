package app.openradio.playback

import androidx.media3.common.PlaybackException
import app.openradio.domain.FailureKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorMappingTest {
    @Test fun mapsKnownCodes() {
        assertEquals(FailureKind.BehindLive, classifyPlaybackError(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW))
        assertEquals(FailureKind.Network, classifyPlaybackError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertEquals(FailureKind.Network, classifyPlaybackError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
        assertEquals(FailureKind.StaleUrl, classifyPlaybackError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        assertEquals(FailureKind.Other, classifyPlaybackError(PlaybackException.ERROR_CODE_DECODING_FAILED))
    }

    @Test fun mediaIdsRoundTripAndRejectUnknownPrefixes() {
        val uuid = "9617a958-0601-11e8-ae97-52543be04c81"
        val expectedMediaId = "station:9617a958-0601-11e8-ae97-52543be04c81"
        val mediaId = MediaIds.station(uuid)
        assertEquals(expectedMediaId, mediaId)
        assertEquals(uuid, MediaIds.stationId(mediaId))
        org.junit.Assert.assertNull(MediaIds.stationId(uuid))
        org.junit.Assert.assertNull(MediaIds.stationId(MediaIds.PRESETS))
    }

    @Test fun seekableLiveWindowExcludesHlsHoldbackBuffer() {
        // Non-DVR 5-segment HLS stream (.norewind.m3u8): 32s total manifest, ~14s behind live edge (< 30s)
        val noRewindWindow = PlaybackConnection.seekableLiveWindowMs(durationMs = 32_000L, defaultPositionMs = 14_000L)
        assertEquals(14_000L, noRewindWindow)
        org.junit.Assert.assertFalse(noRewindWindow >= PlaybackConnection.MIN_LIVE_WINDOW_MS)

        // DVR 50-segment HLS stream (RTL): 288s total manifest, ~268s behind live edge (>= 30s)
        val dvrWindow = PlaybackConnection.seekableLiveWindowMs(durationMs = 288_000L, defaultPositionMs = 268_000L)
        assertEquals(268_000L, dvrWindow)
        org.junit.Assert.assertTrue(dvrWindow >= PlaybackConnection.MIN_LIVE_WINDOW_MS)
    }
}
