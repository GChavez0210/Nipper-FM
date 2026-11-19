package app.openradio.playback

import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import app.openradio.domain.EqPreset
import app.openradio.domain.PlaybackPrefs

/**
 * Manages hardware/OS audio effects (5-band Equalizer presets, Virtualizer / spatial audio, and
 * LoudnessEnhancer normalization) bound to ExoPlayer's active audio session ID.
 *
 * All effect instantiations are wrapped in [runCatching] so devices or emulators without specific
 * DSP effects degrade gracefully without crashing playback.
 */
class AudioFxController {
    private var sessionId: Int = 0
    private var equalizer: Equalizer? = null
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var currentPrefs: PlaybackPrefs = PlaybackPrefs()
    private var boostMb: Int = 0

    /** Extra makeup gain (millibels) for a positive per-station volume trim. */
    fun setBoostMillibels(mb: Int) {
        val next = mb.coerceAtLeast(0)
        if (next == boostMb) return
        boostMb = next
        applyPrefs(currentPrefs)
    }

    fun bindSession(audioSessionId: Int) {
        val nextSessionId = audioSessionId.coerceAtLeast(0)
        if (nextSessionId == sessionId) return
        releaseEffects()
        sessionId = nextSessionId
        applyPrefs(currentPrefs)
    }

    fun applyPrefs(prefs: PlaybackPrefs) {
        currentPrefs = prefs
        val sid = sessionId
        if (sid <= 0) return

        // 1. Equalizer preset
        runCatching {
            val eq = equalizer ?: Equalizer(0, sid).also { equalizer = it }
            eq.enabled = prefs.eqPreset != EqPreset.Flat
            val bands = eq.numberOfBands.toInt()
            val range = eq.bandLevelRange
            val minMb = range.getOrNull(0)?.toInt() ?: -1500
            val maxMb = range.getOrNull(1)?.toInt() ?: 1500
            for (b in 0 until bands) {
                val frequencyHz = eq.getCenterFreq(b.toShort()) / 1_000.0
                val target = prefs.eqPreset.gainForFrequencyHz(frequencyHz).coerceIn(minMb, maxMb)
                eq.setBandLevel(b.toShort(), target.toShort())
            }
        }

        // 2. Spatial Audio (Virtualizer)
        runCatching {
            if (prefs.spatialAudio) {
                val virt = virtualizer ?: Virtualizer(0, sid).also { virtualizer = it }
                virt.enabled = true
                if (virt.strengthSupported) {
                    virt.setStrength(750.toShort())
                }
            } else {
                virtualizer?.enabled = false
            }
        }

        // 3. Loudness Normalization (+3.5 dB gentle makeup gain) plus any per-station boost.
        runCatching {
            val gainMb = (if (prefs.loudnessNorm) 350 else 0) + boostMb
            if (gainMb > 0) {
                val le = loudnessEnhancer ?: LoudnessEnhancer(sid).also { loudnessEnhancer = it }
                le.setTargetGain(gainMb)
                le.enabled = true
            } else {
                loudnessEnhancer?.enabled = false
            }
        }
    }

    fun release() {
        releaseEffects()
        sessionId = 0
    }

    private fun releaseEffects() {
        runCatching { equalizer?.release() }
        equalizer = null
        runCatching { virtualizer?.release() }
        virtualizer = null
        runCatching { loudnessEnhancer?.release() }
        loudnessEnhancer = null
    }
}
