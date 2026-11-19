package app.openradio.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sleep timer owned by the playback service. It compares wall-clock time on every tick so a
 * delayed wake-up (Doze, CPU sleep) still fires as soon as the deadline has passed.
 */
class SleepTimer(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val onExpired: () -> Unit,
    private val onChanged: (endsAtMs: Long?) -> Unit,
    private val tickMs: Long = 5_000,
) {
    private var job: Job? = null
    var endsAtMs: Long? = null
        private set

    fun start(minutes: Int) {
        cancel()
        val endsAt = clock() + minutes * 60_000L
        endsAtMs = endsAt
        onChanged(endsAt)
        job = scope.launch {
            while (true) {
                val remaining = endsAt - clock()
                if (remaining <= 0) break
                delay(minOf(remaining, tickMs))
            }
            endsAtMs = null
            onChanged(null)
            onExpired()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        if (endsAtMs != null) {
            endsAtMs = null
            onChanged(null)
        }
    }
}
