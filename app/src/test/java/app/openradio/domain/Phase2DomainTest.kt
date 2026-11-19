package app.openradio.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Phase2DomainTest {
    @Test fun dataUsageRoundsAndFormatsKnownBitrates() {
        assertEquals(56, DataUsageEstimator.estimateMbPerHour(128))
        assertEquals(113, DataUsageEstimator.estimateMbPerHour(256_000))
        assertEquals(1, DataUsageEstimator.estimateMbPerHour(1))
        assertEquals("~141 MB/hr", DataUsageEstimator.formatHourly(320))
        assertNull(DataUsageEstimator.estimateMbPerHour(-1))
        assertNull(DataUsageEstimator.formatHourly(0))
    }

    @Test fun onlyOnlineTerminalFailuresFlagStations() {
        for (kind in FailureKind.entries) {
            assertFalse(PlaybackErrorClassifier.shouldFlagStationFailed(kind, false))
            assertEquals(kind != FailureKind.BehindLive, PlaybackErrorClassifier.shouldFlagStationFailed(kind, true))
        }
    }

    @Test fun errorsExplainPolicyConnectivityAndStreamFailures() {
        val blocked = PlaybackErrorClassifier.describe(FailureKind.Other, false, true, 128)
        assertEquals(PlaybackErrorKind.WifiOnlyBlocked, blocked.kind)
        assertEquals("Use mobile data once", blocked.actionLabel)
        assertTrue(blocked.message.contains("~56 MB/hr"))
        assertFalse(PlaybackErrorClassifier.describe(null, true, true).message.contains("Estimated"))
        assertEquals(PlaybackErrorKind.LostConnectivity, PlaybackErrorClassifier.describe(FailureKind.StaleUrl, false).kind)
        for (kind in listOf(FailureKind.Network, FailureKind.StaleUrl)) {
            val error = PlaybackErrorClassifier.describe(kind, true)
            assertEquals(PlaybackErrorKind.StationUnavailable, error.kind)
            assertEquals("Retry stream", error.actionLabel)
        }
        val unsupported = PlaybackErrorClassifier.describe(FailureKind.Other, true)
        assertEquals(PlaybackErrorKind.UnsupportedStream, unsupported.kind)
        assertEquals("Try alternate feed", unsupported.actionLabel)
        for (kind in listOf(FailureKind.BehindLive, null)) {
            assertEquals("Retry", PlaybackErrorClassifier.describe(kind, true).actionLabel)
        }
    }

    @Test fun feedsKeepPrimaryAndRankUniqueAlternates() {
        val primary = Station("main", "Radio", "https://radio.test/main", bitrate = 64)
        val high = primary.copy(id = "high", streamUrl = "https://radio.test/high", bitrate = 320_000)
        val flac = primary.copy(id = "lossless", streamUrl = "https://radio.test/lossless", tags = listOf("FLAC"))
        val hls = primary.copy(id = "hls", streamUrl = "https://radio.test/live", isHls = true)
        val feeds = AlternateFeedSelector.rankFeeds(
            primary, listOf(high, flac, hls, primary.copy(streamUrl = " HTTPS://RADIO.TEST/MAIN "), primary.copy(streamUrl = " ")),
        )
        assertEquals(listOf("main", "lossless", "high", "hls"), feeds.map { it.stationId })
        assertTrue(feeds[1].isFlac)
        assertEquals(320, feeds[2].bitrateKbps)
        assertEquals("HLS", feeds[3].codec)
        assertEquals(feeds[2], AlternateFeedSelector.nextFailoverFeed(
            " HTTPS://RADIO.TEST/MAIN ", feeds, setOf(" HTTPS://RADIO.TEST/LOSSLESS "),
        ))
        assertEquals(feeds.first(), AlternateFeedSelector.nextFailoverFeed(null, feeds, emptySet()))
        assertNull(AlternateFeedSelector.nextFailoverFeed(null, feeds, feeds.map { it.streamUrl }.toSet()))
    }

    @Test fun equalizerUsesActualDeviceBandFrequencies() {
        val preset = EqPreset.WarmBroadcast
        assertEquals(300, preset.gainForFrequencyHz(40.0))
        assertEquals(250, preset.gainForFrequencyHz(kotlin.math.sqrt(60.0 * 230)))
        assertEquals(200, preset.gainForFrequencyHz(230.0))
        assertEquals(0, preset.gainForFrequencyHz(910.0))
        assertEquals(-100, preset.gainForFrequencyHz(3_600.0))
        assertEquals(150, preset.gainForFrequencyHz(20_000.0))
    }

    @Test fun equalizerPreservesEndpointsAndInterpolatesDeviceBands() {
        val preset = EqPreset.WarmBroadcast
        assertEquals(0, preset.gainForBand(0, 0))
        assertEquals(preset.bandGainsMb[2], preset.gainForBand(0, 1))
        assertEquals(listOf(300, 150), (0..1).map { preset.gainForBand(it, 2) })
        assertArrayEquals(preset.bandGainsMb, IntArray(5) { preset.gainForBand(it, 5) })
        assertEquals(listOf(300, 250, 200, 100, 0, -50, -100, 25, 150), List(9) { preset.gainForBand(it, 9) })
        assertTrue(List(10) { EqPreset.Flat.gainForBand(it, 10) }.all { it == 0 })
    }

    @Test fun waveformHandlesSilencePhaseBalanceAndFft() {
        assertEquals(AudioVisSnapshot(), AudioVisualizerMath.computeFromWaveform(byteArrayOf()))
        val silent = AudioVisualizerMath.computeFromWaveform(ByteArray(17) { 128.toByte() })
        assertFalse(silent.isActive)
        assertEquals(-60f, silent.lufs, 0f)
        assertEquals(List(16) { 0.06f }, silent.spectrum)

        val inPhase = AudioVisualizerMath.computeFromWaveform(byteArrayOf(64, 64))
        assertTrue(inPhase.isActive)
        assertEquals(1f, inPhase.stereoPhase, 0.001f)
        assertEquals(-6.712f, inPhase.lufs, 0.001f)
        val outOfPhase = AudioVisualizerMath.computeFromWaveform(byteArrayOf(64, 192.toByte()))
        assertEquals(-1f, outOfPhase.stereoPhase, 0.001f)
        assertEquals(0f, outOfPhase.stereoBalance, 0.001f)
        assertEquals(-1f, AudioVisualizerMath.computeFromWaveform(byteArrayOf(64, 128.toByte())).stereoBalance, 0.001f)

        val fft = ByteArray(64).apply { this[2] = 96 }
        val withFft = AudioVisualizerMath.computeFromWaveform(byteArrayOf(64), fft)
        assertEquals(1f, withFft.spectrum.first(), 0.001f)
        assertEquals(0.06f, withFft.spectrum.last(), 0.001f)
    }

    @Test fun fallbackMovesWithinMeterBoundsAndUsesBitrate() {
        val start = AudioVisualizerMath.animatedFallback(0, 128)
        assertEquals(start, AudioVisualizerMath.animatedFallback(0, 128))
        assertNotEquals(start.spectrum, AudioVisualizerMath.animatedFallback(10, 128).spectrum)
        assertTrue(AudioVisualizerMath.animatedFallback(0, 256).lufs > start.lufs)
        for (tick in 0..120) {
            val frame = AudioVisualizerMath.animatedFallback(tick, 128)
            assertTrue(frame.isActive)
            assertEquals(16, frame.spectrum.size)
            assertTrue(frame.spectrum.all { it in 0.10f..0.96f })
            assertTrue(frame.stereoPhase in -1f..1f)
            assertTrue(frame.stereoBalance in -0.5f..0.5f)
            assertTrue(frame.lufs in -28f..-9f)
        }
    }

    @Test fun addingTimeStartsAndExtendsTimerWhileRestoringFadeVolume() = runTest {
        val volumes = mutableListOf<Float>()
        val deadlines = mutableListOf<Long?>()
        var expirations = 0
        val timer = SleepTimer(this, { testScheduler.currentTime }, { expirations++ }, deadlines::add, volumes::add)
        timer.addMinutes(1)
        assertEquals(60_000L, timer.endsAtMs)
        advanceTimeBy(55_000)
        runCurrent()
        assertEquals(0.5f, volumes.last(), 0.001f)
        timer.addMinutes(1)
        assertEquals(120_000L, timer.endsAtMs)
        assertEquals(1f, volumes.last(), 0f)
        advanceTimeBy(55_000)
        runCurrent()
        assertEquals(1f, volumes.last(), 0f)
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(0.6f, volumes.last(), 0.001f)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(0.1f, volumes.last(), 0.001f)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, expirations)
        assertNull(timer.endsAtMs)
        assertEquals(listOf(60_000L, 120_000L, null), deadlines)
        assertEquals(1f, volumes.last(), 0f)
    }
}
