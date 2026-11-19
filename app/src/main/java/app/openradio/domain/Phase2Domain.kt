package app.openradio.domain

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Buffer profile for stream playback:
 *  - [Standard]: balanced buffer for low-latency Wi-Fi / home listening.
 *  - [Cellular]: deeper buffer with longer startup reserve to smooth out cellular handoffs.
 *  - [LowLatency]: minimal buffer for quick tuning.
 */
enum class BufferProfile(
    val label: String,
    val subtitle: String,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val playbackStartMs: Int,
    val rebufferMs: Int,
) {
    Standard("Standard", "Balanced playback", 10_000, 40_000, 2_500, 5_000),
    Cellular("Cellular Saver", "Fewer dropouts", 25_000, 75_000, 4_500, 8_000),
    LowLatency("Quick Tune", "Starts faster", 5_000, 20_000, 1_200, 2_500),
}

/**
 * Equalizer presets for hardware/software audio processing.
 * Band gains are expressed in milliBels (mB, 100 mB = 1 dB) across 5 canonical bands
 * (60 Hz, 230 Hz, 910 Hz, 3.6 kHz, 14 kHz) and interpolated onto the device's actual EQ bands.
 */
enum class EqPreset(
    val label: String,
    val bandGainsMb: IntArray,
) {
    Flat("Flat", intArrayOf(0, 0, 0, 0, 0)),
    WarmBroadcast("Warm FM", intArrayOf(300, 200, 0, -100, 150)),
    VoiceClarity("Voice / Talk", intArrayOf(-250, 100, 400, 350, -100)),
    BassBoost("Deep Bass", intArrayOf(550, 350, 0, 50, 150)),
    JazzAcoustic("Hi-Fi Acoustic", intArrayOf(250, 100, -100, 250, 400)),
    LateNight("Late Night", intArrayOf(200, 100, 150, -150, -250)),
    ;

    fun gainForFrequencyHz(frequencyHz: Double): Int {
        val upper = CENTER_FREQUENCIES_HZ.indexOfFirst { frequencyHz <= it }
        if (upper == -1) return bandGainsMb.last()
        if (upper == 0) return bandGainsMb.first()

        val low = CENTER_FREQUENCIES_HZ[upper - 1]
        val high = CENTER_FREQUENCIES_HZ[upper]
        val weight = ln(frequencyHz / low) / ln(high.toDouble() / low)
        val start = bandGainsMb[upper - 1]
        return (start + (bandGainsMb[upper] - start) * weight).roundToInt()
    }

    private companion object {
        val CENTER_FREQUENCIES_HZ = intArrayOf(60, 230, 910, 3_600, 14_000)
    }

    fun gainForBand(bandIndex: Int, totalBands: Int): Int {
        if (totalBands <= 0 || bandGainsMb.isEmpty()) return 0
        if (totalBands == 1) return bandGainsMb[2]
        val pos = bandIndex.toFloat() / (totalBands - 1).coerceAtLeast(1) * (bandGainsMb.size - 1)
        val lo = pos.toInt().coerceIn(0, bandGainsMb.lastIndex)
        val hi = (lo + 1).coerceIn(0, bandGainsMb.lastIndex)
        val frac = pos - lo
        return (bandGainsMb[lo] + ((bandGainsMb[hi] - bandGainsMb[lo]) * frac)).toInt()
    }
}

/** User-configurable audio & network preferences for Phase 2. */
data class PlaybackPrefs(
    val wifiOnly: Boolean = false,
    val bufferProfile: BufferProfile = BufferProfile.Standard,
    val eqPreset: EqPreset = EqPreset.Flat,
    val spatialAudio: Boolean = false,
    val loudnessNorm: Boolean = false,
)

/**
 * Clearer playback error classification with distinct user-facing title, explanation,
 * primary action label, and rules for the per-station "Failed last attempt" flag.
 */
enum class PlaybackErrorKind {
    None,
    WifiOnlyBlocked,
    LostConnectivity,
    StationUnavailable,
    UnsupportedStream,
}

data class PlaybackErrorDetail(
    val kind: PlaybackErrorKind,
    val title: String,
    val message: String,
    val actionLabel: String,
)

object PlaybackErrorClassifier {
    /**
     * Only stream-side failures after retries (StaleUrl, Unsupported/Other, or exhausted server
     * retries when the phone itself is online) should mark a station as "Failed last attempt".
     * Phone connectivity loss or Wi-Fi-only gating must leave the station unflagged.
     */
    fun shouldFlagStationFailed(
        failureKind: FailureKind,
        deviceHasNetwork: Boolean,
    ): Boolean {
        if (!deviceHasNetwork) return false
        return when (failureKind) {
            FailureKind.StaleUrl, FailureKind.Other -> true
            // If the phone is online and all 5 retries still timed out / refused connection,
            // the broadcaster's stream server itself is unreachable.
            FailureKind.Network -> true
            FailureKind.BehindLive -> false
        }
    }

    fun describe(
        failureKind: FailureKind?,
        deviceHasNetwork: Boolean,
        wifiOnlyBlocked: Boolean = false,
        bitrateKbps: Int = 0,
    ): PlaybackErrorDetail {
        if (wifiOnlyBlocked) {
            val usage = DataUsageEstimator.formatHourly(bitrateKbps)
            val usageNote = if (usage != null) " Estimated mobile data: $usage." else ""
            return PlaybackErrorDetail(
                kind = PlaybackErrorKind.WifiOnlyBlocked,
                title = "Wi-Fi-only playback active",
                message = "Mobile data playback is paused.$usageNote You can allow mobile data for this session.",
                actionLabel = "Use mobile data once",
            )
        }
        if (!deviceHasNetwork) {
            return PlaybackErrorDetail(
                kind = PlaybackErrorKind.LostConnectivity,
                title = "Lost internet connection",
                message = "Your phone is offline. Playback will resume automatically as soon as your connection returns.",
                actionLabel = "Retry now",
            )
        }
        return when (failureKind) {
            FailureKind.StaleUrl, FailureKind.Network -> PlaybackErrorDetail(
                kind = PlaybackErrorKind.StationUnavailable,
                title = "Station stream unavailable",
                message = "The broadcaster's server didn't respond after 5 attempts. Try another feed or retry.",
                actionLabel = "Retry stream",
            )
            FailureKind.Other -> PlaybackErrorDetail(
                kind = PlaybackErrorKind.UnsupportedStream,
                title = "Unsupported or corrupted stream",
                message = "This feed returned an audio format or playlist container that couldn't be decoded.",
                actionLabel = "Try alternate feed",
            )
            FailureKind.BehindLive, null -> PlaybackErrorDetail(
                kind = PlaybackErrorKind.StationUnavailable,
                title = "Stream interrupted",
                message = "Playback stopped unexpectedly. Tap Retry to reconnect.",
                actionLabel = "Retry",
            )
        }
    }
}

/**
 * Estimates hourly mobile data consumption from a stream's bitrate in kbps.
 * Formula: $\text{MB/hr} = \frac{\text{kbps} \times 3600}{8 \times 1024} \approx \text{kbps} \times 0.43945$.
 */
object DataUsageEstimator {
    fun estimateMbPerHour(bitrateKbps: Int): Int? {
        val clean = if (bitrateKbps > 2000) bitrateKbps / 1000 else bitrateKbps
        if (clean <= 0) return null
        return ((clean * 3600.0) / (8.0 * 1024.0) + 0.5).toInt().coerceAtLeast(1)
    }

    fun formatHourly(bitrateKbps: Int): String? {
        val mb = estimateMbPerHour(bitrateKbps) ?: return null
        return "~$mb MB/hr"
    }
}

/**
 * Represents a verified playable stream feed for a station (primary or alternate mirror/codec/FLAC).
 */
data class StreamFeed(
    val stationId: String,
    val label: String,
    val streamUrl: String,
    val codec: String?,
    val bitrateKbps: Int,
    val isHls: Boolean,
) {
    val isFlac: Boolean
        get() = codec.equals("FLAC", ignoreCase = true) ||
            streamUrl.contains("flac", ignoreCase = true)

    val qualityBadge: String
        get() {
            val c = when {
                isFlac -> "FLAC"
                !codec.isNullOrBlank() && !codec.equals("UNKNOWN", ignoreCase = true) -> codec.uppercase()
                isHls -> "HLS"
                else -> "STREAM"
            }
            val br = if (bitrateKbps > 0) " • $bitrateKbps kbps" else ""
            val usage = DataUsageEstimator.formatHourly(bitrateKbps)?.let { " ($it)" }.orEmpty()
            return "$c$br$usage"
        }
}

object AlternateFeedSelector {
    /**
     * Builds a deduplicated, quality-ranked list of verified feeds for [primary] from directory
     * matches sharing the same station name or host, prioritizing FLAC and higher bitrates while
     * keeping the currently active feed accessible.
     */
    fun rankFeeds(primary: Station, candidates: List<Station>): List<StreamFeed> {
        val all = (listOf(primary) + candidates)
            .filter { it.streamUrl.isNotBlank() }
            .distinctBy { it.streamUrl.trim().lowercase() }

        return all.mapIndexed { idx, st ->
            val rawBr = if (st.bitrate > 2000) st.bitrate / 1000 else st.bitrate
            val isFlac = st.codec.equals("FLAC", ignoreCase = true) ||
                st.tags.any { it.equals("flac", ignoreCase = true) } ||
                st.streamUrl.contains("flac", ignoreCase = true)
            val codec = when {
                isFlac -> "FLAC"
                !st.codec.isNullOrBlank() && !st.codec.equals("UNKNOWN", ignoreCase = true) -> st.codec.uppercase()
                st.isHls -> "HLS"
                else -> "MP3/AAC"
            }
            val prefix = if (idx == 0) "Primary" else "Alt #$idx"
            StreamFeed(
                stationId = st.id,
                label = if (isFlac) "$prefix • Hi-Res FLAC" else prefix,
                streamUrl = st.streamUrl,
                codec = codec,
                bitrateKbps = rawBr,
                isHls = st.isHls,
            )
        }.sortedWith(
            compareByDescending<StreamFeed> { it.streamUrl == primary.streamUrl }
                .thenByDescending { it.isFlac }
                .thenByDescending { it.bitrateKbps },
        )
    }

    /** Picks the next untried alternate feed URL when automatic failover triggers. */
    fun nextFailoverFeed(
        currentUrl: String?,
        feeds: List<StreamFeed>,
        triedUrls: Set<String>,
    ): StreamFeed? {
        val normCurrent = currentUrl?.trim()?.lowercase()
        val normTried = triedUrls.map { it.trim().lowercase() }.toSet()
        return feeds.firstOrNull { feed ->
            val norm = feed.streamUrl.trim().lowercase()
            norm != normCurrent && norm !in normTried
        }
    }
}

/**
 * Snapshot of live audio visualization metrics:
 *  - [spectrum]: 16 frequency bands normalized in `0f..1f`
 *  - [stereoPhase]: correlation in `-1f..1f` (`+1` mono/in-phase, `0` wide stereo, `<0` out-of-phase)
 *  - [stereoBalance]: L/R balance in `-1f..1f`
 *  - [lufs]: estimated short-term loudness in LUFS (`-60f..0f`, broadcast target ~ `-14` to `-16` LUFS)
 */
data class AudioVisSnapshot(
    val spectrum: List<Float> = List(16) { 0.08f },
    val stereoPhase: Float = 0.85f,
    val stereoBalance: Float = 0f,
    val lufs: Float = -24f,
    val isActive: Boolean = false,
)

object AudioVisualizerMath {
    /** Computes short-term LUFS from unsigned 8-bit PCM waveform bytes (`0..255`, center `128`). */
    fun computeFromWaveform(waveform: ByteArray, fft: ByteArray? = null): AudioVisSnapshot {
        if (waveform.isEmpty()) return AudioVisSnapshot()
        var sumSqL = 0.0
        var sumSqR = 0.0
        var dotLR = 0.0
        val n = waveform.size
        var i = 0
        while (i < n) {
            val sL = ((waveform[i].toInt() and 0xFF) - 128) / 128.0
            val sR = if (i + 1 < n) ((waveform[i + 1].toInt() and 0xFF) - 128) / 128.0 else sL
            sumSqL += sL * sL
            sumSqR += sR * sR
            dotLR += sL * sR
            i += 2
        }
        val pairs = (n / 2).coerceAtLeast(1)
        val rmsL = sqrt(sumSqL / pairs)
        val rmsR = sqrt(sumSqR / pairs)
        val meanSq = (sumSqL + sumSqR) / (2.0 * pairs)
        val lufs = if (meanSq <= 1e-7) {
            -60f
        } else {
            (-0.691 + 10.0 * log10(meanSq)).toFloat().coerceIn(-60f, -3f)
        }
        val denom = sqrt(sumSqL * sumSqR)
        val phase = if (denom > 1e-6) (dotLR / denom).toFloat().coerceIn(-1f, 1f) else 1f
        val totalRms = rmsL + rmsR
        val balance = if (totalRms > 1e-5) ((rmsR - rmsL) / totalRms).toFloat().coerceIn(-1f, 1f) else 0f

        val bands = if (fft != null && fft.size >= 32) {
            extractFftBands(fft, 16)
        } else {
            extractWaveformBands(waveform, 16)
        }
        return AudioVisSnapshot(
            spectrum = bands,
            stereoPhase = phase,
            stereoBalance = balance,
            lufs = lufs,
            isActive = meanSq > 1e-5,
        )
    }

    /** Spectrum levels from Android's signed, packed FFT bins. */
    fun spectrumFromFft(fft: ByteArray): List<Float> = extractFftBands(fft, 16, floor = 0f)

    private fun extractFftBands(fft: ByteArray, bandCount: Int, floor: Float = 0.06f): List<Float> {
        val usableBins = (fft.size / 2).coerceAtLeast(bandCount)
        return List(bandCount) { b ->
            val startBin = (b * usableBins / bandCount).coerceAtLeast(1)
            val endBin = ((b + 1) * usableBins / bandCount).coerceAtLeast(startBin + 1)
            var magSum = 0f
            var count = 0
            for (k in startBin until min(endBin, fft.size / 2)) {
                val re = fft[2 * k].toFloat()
                val im = fft[2 * k + 1].toFloat()
                magSum += sqrt(re * re + im * im)
                count++
            }
            val avg = if (count > 0) magSum / count else 0f
            (avg / 96f).coerceIn(floor, 1f)
        }
    }

    private fun extractWaveformBands(waveform: ByteArray, bandCount: Int): List<Float> {
        val chunk = (waveform.size / bandCount).coerceAtLeast(1)
        return List(bandCount) { b ->
            var sum = 0f
            val start = b * chunk
            val end = min(waveform.size, start + chunk)
            for (idx in start until end) {
                sum += abs((waveform[idx].toInt() and 0xFF) - 128) / 128f
            }
            val avg = sum / (end - start).coerceAtLeast(1)
            (avg * 2.2f).coerceIn(0.06f, 1f)
        }
    }

    /**
     * Smooth animated fallback spectrum & metering when playing a stream whose audio session
     * restricts raw PCM capture on the current device/emulator.
     */
    fun animatedFallback(tick: Int, bitrateKbps: Int): AudioVisSnapshot {
        val t = tick * 0.22
        val bands = List(16) { i ->
            val wave1 = sin(t + i * 0.45) * 0.32
            val wave2 = cos(t * 1.35 - i * 0.31) * 0.22
            val tilt = 1.0 - (i / 24.0)
            ((0.42 + wave1 + wave2) * tilt).toFloat().coerceIn(0.10f, 0.96f)
        }
        val phase = (0.76 + 0.16 * sin(t * 0.4)).toFloat().coerceIn(-1f, 1f)
        val balance = (0.12 * cos(t * 0.55)).toFloat().coerceIn(-0.5f, 0.5f)
        val baseLufs = if (bitrateKbps >= 256) -14.2f else -15.6f
        val lufs = (baseLufs + (sin(t * 0.7) * 1.8).toFloat()).coerceIn(-28f, -9f)
        return AudioVisSnapshot(
            spectrum = bands,
            stereoPhase = phase,
            stereoBalance = balance,
            lufs = lufs,
            isActive = true,
        )
    }
}
