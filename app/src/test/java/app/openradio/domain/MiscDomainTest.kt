package app.openradio.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackStatusRulesTest {
    private fun s(
        item: Boolean = true, pwr: Boolean = true, ready: Boolean = true, buf: Boolean = false,
        err: Boolean = false, rec: RecoveryPhase = RecoveryPhase.Idle,
    ) = PlaybackStatusRules.derive(item, pwr, ready, buf, err, rec)

    @Test fun none() = assertEquals(PlaybackStatus.None, s(item = false))
    @Test fun live() = assertEquals(PlaybackStatus.Live, s())
    @Test fun buffering() = assertEquals(PlaybackStatus.Buffering, s(ready = false, buf = true))
    @Test fun paused() = assertEquals(PlaybackStatus.Paused, s(pwr = false))
    @Test fun reconnectingBeatsError() = assertEquals(PlaybackStatus.Reconnecting, s(err = true, rec = RecoveryPhase.Reconnecting))
    @Test fun failedIsUnavailable() = assertEquals(PlaybackStatus.Unavailable, s(err = true, rec = RecoveryPhase.Failed))
    @Test fun pausedWithStaleErrorShowsPaused() = assertEquals(PlaybackStatus.Paused, s(pwr = false, err = true))
}

class VoiceMatchTest {
    private val list = listOf(
        Station("1", "KEXP 90.3", "u"), Station("2", "KEXP Live Sessions", "u"), Station("3", "Radio Paradise", "u"),
    )

    @Test fun exactNameWinsOverPrefix() = assertEquals("1", VoiceMatch.best("KEXP 90.3", list)?.id)
    @Test fun fillerWordsAreStripped() {
        assertEquals("kexp", VoiceMatch.cleanQuery("Play KEXP radio"))
        assertEquals("3", VoiceMatch.best("play radio paradise", list)?.id)
    }
    @Test fun prefixBeatsContains() = assertEquals("2", VoiceMatch.best("kexp live", list)?.id)
    @Test fun noMatch() = assertNull(VoiceMatch.best("jazz fm", list))
    @Test fun emptyQuery() = assertNull(VoiceMatch.best("play", list))
}

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerTest {
    @Test fun expiresAfterRequestedMinutesWhileScreenOff() = runTest {
        var expired = 0
        val changes = mutableListOf<Long?>()
        val timer = SleepTimer(backgroundScope, clock = { currentTime }, onExpired = { expired++ }, onChanged = { changes += it })
        timer.start(45)
        advanceTimeBy(44 * 60_000L)
        assertEquals(0, expired)
        advanceTimeBy(60_000L + 1)
        assertEquals(1, expired)
        assertNull(timer.endsAtMs)
        assertEquals(listOf(45 * 60_000L, null), changes)
    }

    @Test fun cancelStopsExpiry() = runTest {
        var expired = 0
        val timer = SleepTimer(backgroundScope, clock = { currentTime }, onExpired = { expired++ }, onChanged = {})
        timer.start(15)
        advanceTimeBy(60_000)
        timer.cancel()
        advanceTimeBy(60 * 60_000L)
        assertEquals(0, expired)
    }

    @Test fun restartReplacesPreviousTimer() = runTest {
        var expired = 0
        val timer = SleepTimer(backgroundScope, clock = { currentTime }, onExpired = { expired++ }, onChanged = {})
        timer.start(15)
        timer.start(45)
        advanceTimeBy(16 * 60_000L)
        assertEquals(0, expired)
        advanceTimeBy(30 * 60_000L)
        assertEquals(1, expired)
    }
}

class RetryPolicyTest {
    @Test fun delaysDoubleUpToCap() {
        val p = RetryPolicy()
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 16_000L), (1..6).map { p.delayFor(it) })
    }
}
