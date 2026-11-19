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

    fun start(minutes: Int) {
        val target = clock() + minutes * 60_000L
        scheduleTo(target)
    }

    /** Extends an active sleep timer by [extraMinutes], or starts a new timer if inactive. */
    fun addMinutes(extraMinutes: Int) {
        val base = endsAtMs?.takeIf { it > clock() } ?: clock()
        scheduleTo(base + extraMinutes * 60_000L)
    }

    private fun scheduleTo(endsAt: Long) {
        job?.cancel()
        onFadeVolume(1f)
        endsAtMs = endsAt
        onChanged(endsAt)
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
            endsAtMs = null
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
            endsAtMs = null
            onChanged(null)
        }
    }
}
