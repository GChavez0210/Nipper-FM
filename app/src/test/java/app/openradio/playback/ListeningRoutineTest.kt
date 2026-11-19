package app.openradio.playback

import app.openradio.domain.Station
import java.time.DayOfWeek
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningRoutineTest {
    private val routine = ListeningRoutine(
        id = "weekday",
        station = Station("test", "Test station", "https://example.org/stream"),
        hour = 7,
        minute = 30,
        days = DayOfWeek.entries.take(5).toSet(),
        wakeUp = true,
    )

    @Test
    fun beforeTimeUsesTheSameDay() {
        val now = ZonedDateTime.parse("2026-11-20T07:29:00-07:00[America/Denver]")
        assertEquals(now.plusMinutes(1), routine.nextAfter(now))
    }

    @Test
    fun afterFridayAlarmSkipsWeekend() {
        val now = ZonedDateTime.parse("2026-11-20T07:30:00-07:00[America/Denver]")
        assertEquals(now.plusDays(3), routine.nextAfter(now))
    }

    @Test
    fun daylightSavingGapMovesTheAlarmForward() {
        val sunday = routine.copy(hour = 2, days = setOf(DayOfWeek.SUNDAY))
        val now = ZonedDateTime.parse("2026-03-08T01:30:00-07:00[America/Denver]")
        assertEquals(ZonedDateTime.parse("2026-03-08T03:30:00-06:00[America/Denver]"), sunday.nextAfter(now))
    }
}
