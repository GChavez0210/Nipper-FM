package app.openradio.playback

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import app.openradio.MainActivity
import app.openradio.domain.Station
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ListeningRoutine(
    val id: String,
    val station: Station,
    val hour: Int,
    val minute: Int,
    val days: Set<DayOfWeek>,
    val wakeUp: Boolean,
) {
    val stationId get() = station.id
    val stationName get() = station.name

    fun nextAfter(now: ZonedDateTime): ZonedDateTime? = (0L..7L).asSequence()
        .map { now.toLocalDate().plusDays(it) }
        .filter { it.dayOfWeek in days }
        .map { it.atTime(LocalTime.of(hour, minute)).atZone(now.zone) }
        .firstOrNull { it.isAfter(now) }
}

internal class ListeningRoutines(private val context: Context) {
    private val prefs = context.getSharedPreferences("listening_routines", Context.MODE_PRIVATE)
    private val alarms = checkNotNull(context.getSystemService<AlarmManager>())

    fun canSchedule(): Boolean = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    fun list(): List<ListeningRoutine> = runCatching {
        Json.decodeFromString<List<ListeningRoutine>>(prefs.getString("routines", "[]").orEmpty())
    }.getOrDefault(emptyList())

    fun save(routines: List<ListeningRoutine>) {
        prefs.edit().putString("routines", Json.encodeToString(routines)).apply()
        schedule()
    }

    fun schedule(after: ZonedDateTime = ZonedDateTime.now()) {
        if (!canSchedule()) return
        val next = list().mapNotNull { routine -> routine.nextAfter(after)?.let { routine to it } }
            .minWithOrNull(compareBy({ it.second.toInstant() }, { !it.first.wakeUp }, { it.first.id }))
        val intent = Intent(context, ListeningAlarmReceiver::class.java).setAction(ACTION_ALARM)
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        if (next == null) {
            PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                ?.let(alarms::cancel)
            return
        }
        val (routine, time) = next
        val epoch = time.toInstant().toEpochMilli()
        intent.putExtra(EXTRA_ROUTINE, routine.id).putExtra(EXTRA_TIME, epoch)
        val operation = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            if (routine.wakeUp) alarms.setAlarmClock(AlarmManager.AlarmClockInfo(epoch, open), operation)
            else alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epoch, operation)
        } catch (_: SecurityException) {
            // Permission may be revoked between the check and scheduling.
        }
    }

    companion object {
        const val ACTION_ALARM = "app.openradio.action.LISTENING_ALARM"
        const val EXTRA_ROUTINE = "routine"
        const val EXTRA_TIME = "time"
    }
}

class ListeningAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val routines = ListeningRoutines(context)
        val now = ZonedDateTime.now()
        if (intent.action == ListeningRoutines.ACTION_ALARM) {
            val expected = intent.getLongExtra(ListeningRoutines.EXTRA_TIME, 0L)
            val elapsed = now.toInstant().toEpochMilli() - expected
            val id = intent.getStringExtra(ListeningRoutines.EXTRA_ROUTINE)
            // Preserve future occurrences even if Android rejects this service launch.
            routines.schedule(now.plusSeconds(1))
            if (elapsed in 0..300_000 && routines.list().any { it.id == id }) {
                val service = Intent(context, ListeningRoutineService::class.java)
                    .putExtra(ListeningRoutines.EXTRA_ROUTINE, id)
                try {
                    ContextCompat.startForegroundService(context, service)
                } catch (error: IllegalStateException) {
                    android.util.Log.w("ListeningAlarm", "Could not start listening routine", error)
                }
            }
        } else {
            routines.schedule(now)
        }
    }
}
