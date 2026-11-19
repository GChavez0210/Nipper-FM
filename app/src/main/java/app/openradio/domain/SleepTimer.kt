package app.openradio.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sleep timer owned by the playback service. It compares wall-clock time on every tick so a
 * delayed wake-up (Doze, CPU sleep) still fires as soon as the deadline has passed, and supports
 * extending an active timer (+15 min) plus a smooth volume fade-out during the final seconds.
 */
class SleepTimer(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val onExpired: () -> Unit,
    private val onChanged: (endsAtMs: Long?) -> Unit,
    private val onFadeVolume: (Float) -> Unit = {},
    private val fadeDurationMs: Long = 10_000L,
    private val tickMs: Long = 1_000L,
) {
    private var job: Job? = null
    var endsAtMs: Long? = null
        private set

    /** True while waiting for the current track to end; [endsAtMs] is then the safety cap. */
    var endOfTrack: Boolean = false
        private set
    private var armedTitle: String? = null

    fun start(minutes: Int) {
        val target = clock() + minutes * 60_000L
        scheduleTo(target)
    }

    /** Extends an active sleep timer by [extraMinutes], or starts a new timer if inactive. */
    fun addMinutes(extraMinutes: Int) {
        val base = endsAtMs?.takeIf { it > clock() } ?: clock()
        scheduleTo(base + extraMinutes * 60_000L)
    }

    /**
     * Stops playback as soon as the stream's track title changes away from [currentTitle].
     * If no change arrives within [maxWaitMinutes], it stops anyway (with the normal fade).
     */
    fun stopAfterTrack(currentTitle: String, maxWaitMinutes: Int = END_OF_TRACK_CAP_MINUTES) {
        scheduleTo(clock() + maxWaitMinutes * 60_000L, notify = false)
        endOfTrack = true
        armedTitle = currentTitle.trim()
        onChanged(endsAtMs)
    }

    /** Feed every stream title change here; ends an armed end-of-track timer on a new title. */
    fun onTrackTitle(title: String?) {
        if (!endOfTrack) return
        val t = title?.trim().orEmpty()
        if (t.isEmpty() || t.equals(armedTitle, ignoreCase = true)) return
        if (armedTitle == null) {
            // First title after a station switch: wait for this track to end instead.
            armedTitle = t
            return
        }
        job?.cancel()
        job = null
        reset()
        onChanged(null)
        onExpired()
        onFadeVolume(1f)
    }

    /** A new station's first title must not count as "the track ended"; re-arm on it instead. */
    fun onStationChanged() {
        if (endOfTrack) armedTitle = null
    }

    private fun reset() {
        endsAtMs = null
        endOfTrack = false
        armedTitle = null
    }

    private fun scheduleTo(endsAt: Long, notify: Boolean = true) {
        job?.cancel()
        onFadeVolume(1f)
        reset()
        endsAtMs = endsAt
        if (notify) onChanged(endsAt)
        job = scope.launch {
            while (true) {
                val remaining = endsAt - clock()
                if (remaining <= 0) break
                if (fadeDurationMs in 1..remaining) {
                    onFadeVolume(1f)
                } else if (fadeDurationMs > 0 && remaining < fadeDurationMs) {
                    val vol = (remaining.toFloat() / fadeDurationMs.toFloat()).coerceIn(0.05f, 1f)
                    onFadeVolume(vol)
                }
                delay(minOf(remaining, tickMs))
            }
            reset()
            onChanged(null)
            onExpired()
            onFadeVolume(1f)
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        onFadeVolume(1f)
        if (endsAtMs != null) {
            reset()
            onChanged(null)
        }
    }

    companion object {
        const val END_OF_TRACK_CAP_MINUTES = 30
    }
}
