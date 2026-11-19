package app.openradio.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectControllerTest {

    private class Harness(scope: TestScope, val failOnRetry: Boolean = false, val fresh: suspend (String) -> String? = { null }) {
        val retries = mutableListOf<Triple<String, String?, Long>>()
        val states = mutableListOf<RecoveryState>()
        val lookups = mutableListOf<String>()
        lateinit var controller: ReconnectController
        init {
            controller = ReconnectController(
                scope = scope.backgroundScope,
                fetchFreshUrl = { id -> lookups += id; fresh(id) },
                performRetry = { id, url ->
                    retries += Triple(id, url, scope.currentTime)
                    // A dead stream raises another error right after the retry.
                    if (failOnRetry) controller.onPlaybackError(id, FailureKind.Network, url)
                },
                onStateChanged = { states += it },
            )
        }
    }

    @Test fun backoffIsExponentialAndBounded() = runTest {
        val h = Harness(this, failOnRetry = true)
        h.controller.onStationChanged("A")
        h.controller.onPlaybackError("A", FailureKind.Network, "http://a")
        settle()

        assertEquals(listOf(1_000L, 3_000L, 7_000L, 15_000L, 31_000L), h.retries.map { it.third })
        assertEquals(5, h.retries.size)
        assertEquals(RecoveryPhase.Failed, h.controller.state.phase)
        assertTrue(h.controller.state.message!!.contains("5 tries"))

        // Nothing further happens after the limit.
        advanceTimeBy(10 * 60_000)
        assertEquals(5, h.retries.size)
    }

    @Test fun stationChangeDuringBackoffNeverRetriesPreviousStation() = runTest {
        val h = Harness(this, failOnRetry = true)
        h.controller.onStationChanged("A")
        h.controller.onPlaybackError("A", FailureKind.Network, "http://a")
        advanceTimeBy(500)
        h.controller.onStationChanged("B")
        advanceTimeBy(10 * 60_000)

        assertTrue(h.retries.isEmpty())
        assertEquals(RecoveryPhase.Idle, h.controller.state.phase)
    }

    @Test fun stationChangeBetweenAttemptsStopsLoop() = runTest {
        val h = Harness(this, failOnRetry = true)
        h.controller.onStationChanged("A")
        h.controller.onPlaybackError("A", FailureKind.Network, "http://a")
        advanceTimeBy(3_500) // two retries for A
        assertEquals(2, h.retries.size)
        h.controller.onStationChanged("B")
        advanceTimeBy(10 * 60_000)

        assertEquals(2, h.retries.size)
        assertTrue(h.retries.all { it.first == "A" })
    }

    @Test fun stationChangeWhileLookingUpFreshUrlDiscardsTheResult() = runTest {
        val gate = CompletableDeferred<String?>()
        val h = Harness(this, fresh = { gate.await() })
        h.controller.onStationChanged("A")
        h.controller.onPlaybackError("A", FailureKind.StaleUrl, "http://old")
        advanceTimeBy(1_100)
        assertEquals(listOf("A"), h.lookups) // lookup in flight

        h.controller.onStationChanged("B")
        gate.complete("http://new")
        advanceTimeBy(60_000)

        assertTrue(h.retries.isEmpty())
    }

    @Test fun staleUrlRetriesWithRefreshedUrl() = runTest {
        val h = Harness(this, fresh = { "http://new" })
        h.controller.onStationChanged("A")
        h.controller.onPlaybackError("A", FailureKind.StaleUrl, "http://old")
        advanceTimeBy(1_100)

        assertEquals(1, h.retries.size)
        assertEquals("A", h.retries[0].first)
        assertEquals("http://new", h.retries[0].second)
    }

    @Test fun unchangedOrMissingFreshUrlReusesCurrentUrl() = runTest {
        val same = Harness(this, fresh = { "http://old" })
        same.controller.onStationChanged("A")
        same.controller.onPlaybackError("A", FailureKind.StaleUrl, "http://old")
        advanceTimeBy(1_100)
        assertNull(same.retries.single().second)

        val offline = Harness(this, fresh = { null })
        offline.controller.onStationChanged("B")
        offline.controller.onPlaybackError("B", FailureKind.Other, "http://b")
        advanceTimeBy(1_100)
        assertNull(offline.retries.single().second)
    }

    @Test fun networkFailuresRefreshOnlyOnceAndOnlyFromThirdAttempt() = runTest {
        val h = Harness(this, failOnRetry = true, fresh = { "http://moved" })
        h.controller.onStationChanged("A")
        h.controller.onPlaybackError("A", FailureKind.Network, "http://a")
        settle()

        assertEquals(1, h.lookups.size)
        assertNull(h.retries[0].second)
        assertNull(h.retries[1].second)
        assertEquals("http://moved", h.retries[2].second)
    }

    @Test fun successfulStartCancelsPendingRetry() = runTest {
        val h = Harness(this)
        h.controller.onStationChanged("A")
        h.controller.onPlaybackError("A", FailureKind.Network, "http://a")
        advanceTimeBy(500)
        h.controller.onPlaybackStarted()
        advanceTimeBy(60_000)

        assertTrue(h.retries.isEmpty())
        assertEquals(RecoveryPhase.Idle, h.controller.state.phase)
    }

    @Test fun userPauseCancelsPendingRetry() = runTest {
        val h = Harness(this)
        h.controller.onStationChanged("A")
        h.controller.onPlaybackError("A", FailureKind.Network, "http://a")
        advanceTimeBy(500)
        h.controller.onUserPaused()
        advanceTimeBy(60_000)

        assertTrue(h.retries.isEmpty())
    }

    @Test fun behindLiveWindowRetriesImmediately() = runTest {
        val h = Harness(this)
        h.controller.onStationChanged("A")
        h.controller.onPlaybackError("A", FailureKind.BehindLive, "http://a")
        advanceTimeBy(1)

        assertEquals(1, h.retries.size)
        assertEquals(0L, h.retries[0].third)
    }

    @Test fun networkReturningRestartsAfterNetworkFailure() = runTest {
        val h = Harness(this, failOnRetry = true)
        h.controller.onStationChanged("A")
        h.controller.onPlaybackError("A", FailureKind.Network, "http://a")
        settle()
        assertEquals(RecoveryPhase.Failed, h.controller.state.phase)
        val before = h.retries.size

        h.controller.onNetworkAvailable("A")
        advanceTimeBy(1)
        assertEquals(before + 1, h.retries.size) // immediate attempt, not after a backoff
    }

    @Test fun networkEventForAnotherStationIsIgnored() = runTest {
        val h = Harness(this, failOnRetry = true)
        h.controller.onStationChanged("A")
        h.controller.onPlaybackError("A", FailureKind.Network, "http://a")
        settle()
        val before = h.retries.size
        h.controller.onStationChanged("B")
        h.controller.onNetworkAvailable("A")
        advanceTimeBy(60_000)
        assertEquals(before, h.retries.size)
    }
}

private suspend fun TestScope.settle() { advanceTimeBy(120_000); runCurrent() }
