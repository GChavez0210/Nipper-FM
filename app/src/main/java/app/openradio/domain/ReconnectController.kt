package app.openradio.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class FailureKind {
    /** Connection failed or timed out. */
    Network,
    /** Server answered with an HTTP error: the URL may be stale. */
    StaleUrl,
    /** Fell outside the live window; a plain re-prepare is enough. */
    BehindLive,
    /** Anything else (bad container, unsupported format, ...). */
    Other,
}

data class RecoveryState(
    val phase: RecoveryPhase = RecoveryPhase.Idle,
    val attempt: Int = 0,
    val maxAttempts: Int = 0,
    val message: String? = null,
)

data class RetryPolicy(
    val maxAttempts: Int = 5,
    val baseDelayMs: Long = 1_000,
    val maxDelayMs: Long = 16_000,
    /** For network failures, ask the directory for a fresh URL from this attempt on (once per cycle). */
    val refreshAfterNetworkAttempt: Int = 3,
) {
    fun delayFor(attempt: Int): Long =
        (baseDelayMs shl (attempt - 1).coerceIn(0, 20)).coerceAtMost(maxDelayMs)

    fun shouldRefresh(kind: FailureKind, attempt: Int, alreadyRefreshed: Boolean): Boolean = when {
        alreadyRefreshed -> false
        kind == FailureKind.StaleUrl || kind == FailureKind.Other -> true
        kind == FailureKind.Network -> attempt >= refreshAfterNetworkAttempt
        else -> false
    }
}

/**
 * Bounded reconnect with exponential backoff for a single live stream.
 *
 * Every pending retry is tied to the station that failed. Selecting another station, pausing, or a
 * successful start cancels pending work, and a retry that wakes up after a switch is discarded, so a
 * previous station can never be restarted.
 */
class ReconnectController(
    private val scope: CoroutineScope,
    private val policy: RetryPolicy = RetryPolicy(),
    /** Looks the station up by UUID; returns a fresh playable URL or null. */
    private val fetchFreshUrl: suspend (stationId: String) -> String?,
    /** Re-prepares the player for [stationId]; [url] is null to reuse the current URL. */
    private val performRetry: (stationId: String, url: String?) -> Unit,
    private val onStateChanged: (RecoveryState) -> Unit,
) {
    private var job: Job? = null
    private var generation = 0
    private var activeStationId: String? = null
    var lastKind: FailureKind? = null
        private set
    private var currentUrl: String? = null
    private var failureSignal = Channel<FailureKind>(Channel.CONFLATED)

    var state: RecoveryState = RecoveryState()
        private set

    /** A new station (or none) was selected: drop everything pending for the old one. */
    fun onStationChanged(stationId: String?) {
        cancelInternal()
        activeStationId = stationId
        publish(RecoveryState())
    }

    fun onPlaybackStarted() {
        cancelInternal()
        publish(RecoveryState())
    }

    fun onUserPaused() {
        cancelInternal()
        publish(RecoveryState())
    }

    /** The user pressed play again after a failure: begin a fresh cycle on the next error. */
    fun onUserResumed() {
        cancelInternal()
        publish(RecoveryState())
    }

    /** Network came back: retry now if we were waiting on a network failure. */
    fun onNetworkAvailable(stationId: String) {
        if (stationId != activeStationId) return
        val recoverable = state.phase == RecoveryPhase.Reconnecting ||
            (state.phase == RecoveryPhase.Failed && lastKind == FailureKind.Network)
        if (recoverable) startCycle(stationId, FailureKind.Network, currentUrl, immediateFirst = true)
    }

    fun onPlaybackError(stationId: String, kind: FailureKind, currentUrl: String?) {
        activeStationId = stationId
        // Errors raised while we already retry are consumed by the running loop.
        if (job?.isActive == true && state.phase == RecoveryPhase.Reconnecting && kind != FailureKind.BehindLive) {
            lastKind = kind
            failureSignal.trySend(kind)
            return
        }
        startCycle(stationId, kind, currentUrl, immediateFirst = kind == FailureKind.BehindLive)
    }

    private fun startCycle(stationId: String, kind: FailureKind, url: String?, immediateFirst: Boolean) {
        cancelInternal()
        lastKind = kind
        currentUrl = url
        val token = generation
        val signal = Channel<FailureKind>(Channel.CONFLATED).also { failureSignal = it }
        job = scope.launch {
            var refreshed = false
            var attempt = 1
            var failKind = kind
            while (attempt <= policy.maxAttempts) {
                if (!isCurrent(token, stationId)) return@launch
                publish(RecoveryState(RecoveryPhase.Reconnecting, attempt, policy.maxAttempts))
                if (!(immediateFirst && attempt == 1)) delay(policy.delayFor(attempt))
                if (!isCurrent(token, stationId)) return@launch

                var urlToUse: String? = null
                if (policy.shouldRefresh(failKind, attempt, refreshed)) {
                    refreshed = true
                    val fresh = runCatching { fetchFreshUrl(stationId) }.getOrNull()
                    if (!isCurrent(token, stationId)) return@launch // station changed during lookup
                    if (!fresh.isNullOrBlank() && fresh != currentUrl) {
                        urlToUse = fresh
                        currentUrl = fresh
                    }
                }
                performRetry(stationId, urlToUse)
                // Wait for the outcome: onPlaybackStarted cancels us; a failure wakes us early.
                val failed = withTimeoutOrNull(RESULT_WAIT_MS) { signal.receive() }
                if (!isCurrent(token, stationId)) return@launch
                attempt++
                failKind = failed ?: lastKind ?: failKind
            }
            publish(
                RecoveryState(
                    RecoveryPhase.Failed, policy.maxAttempts, policy.maxAttempts,
                    "Couldn't reach this station after ${policy.maxAttempts} tries. " +
                        "Check your connection and tap Play to retry, or pick another station.",
                ),
            )
        }
    }

    private fun isCurrent(token: Int, stationId: String) = token == generation && stationId == activeStationId

    private fun cancelInternal() {
        generation++
        job?.cancel()
        job = null
    }

    private fun publish(newState: RecoveryState) {
        state = newState
        onStateChanged(newState)
    }

    companion object {
        /** How long a retry gets to reach playback before the next attempt is scheduled. */
        const val RESULT_WAIT_MS = 6_000L
    }
}
