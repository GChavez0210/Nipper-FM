package app.openradio.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.openradio.RadioApp
import app.openradio.domain.Station
import app.openradio.playback.ListeningRoutine
import app.openradio.playback.ListeningRoutines
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.UUID

private const val NEW_ROUTINE = "new"

@Composable
internal fun ListeningRoutinesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ListeningRoutines(context.applicationContext) }
    val repo = (context.applicationContext as RadioApp).container.stations
    val favorites by repo.favorites.collectAsState(initial = emptyList())
    val recents by repo.recents.collectAsState(initial = emptyList())
    val stations = (favorites + recents).map { it.station }.distinctBy { it.id }
    var routines by remember { mutableStateOf(store.list()) }
    var canSchedule by remember { mutableStateOf(store.canSchedule()) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }

    LifecycleResumeEffect(store) {
        canSchedule = store.canSchedule()
        if (canSchedule) store.schedule()
        onPauseOrDispose {}
    }
    fun save(updated: List<ListeningRoutine>) {
        store.save(updated)
        routines = updated
        editingId = null
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text("Back to settings") }
        Text("Scheduled listening", style = MaterialTheme.typography.headlineSmall)
        Text("Tune in automatically on selected days. Wake-up routines start quietly and use your alarm sound if the stream fails.")
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }) { Text("Enable notifications for wake-up alarms") }
        }
        if (!canSchedule && Build.VERSION.SDK_INT >= 31) {
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
            }) { Text("Allow alarms and reminders") }
        }
        routines.sortedWith(compareBy({ it.hour }, { it.minute })).forEach { routine ->
            Card(onClick = { editingId = routine.id }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(routine.stationName, style = MaterialTheme.typography.titleMedium)
                    Text(LocalTime.of(routine.hour, routine.minute).format(DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT)))
                    val locale = LocalLocale.current.platformLocale
                    Text(routine.days.sorted().joinToString(", ") { it.getDisplayName(TextStyle.SHORT, locale) })
                    if (routine.wakeUp) Text("Wake-up")
                }
            }
        }
        if (stations.isEmpty()) Text("Favorite or play a station to add it to a routine.")
        Button(onClick = { editingId = NEW_ROUTINE }, enabled = stations.isNotEmpty()) { Text("Add routine") }
    }
    editingId?.let { id ->
        val existing = routines.firstOrNull { it.id == id }
        RoutineEditor(
            existing = existing,
            stations = stations,
            onDismiss = { editingId = null },
            onSave = { updated -> save(routines.filterNot { it.id == updated.id } + updated) },
            onDelete = { save(routines.filterNot { it.id == id }) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoutineEditor(
    existing: ListeningRoutine?,
    stations: List<Station>,
    onDismiss: () -> Unit,
    onSave: (ListeningRoutine) -> Unit,
    onDelete: () -> Unit,
) {
    var stationId by rememberSaveable { mutableStateOf(existing?.stationId ?: stations.firstOrNull()?.id) }
    var daysMask by rememberSaveable { mutableIntStateOf(existing?.days?.sumOf { 1 shl it.ordinal } ?: 0) }
    var wakeUp by rememberSaveable { mutableStateOf(existing?.wakeUp ?: false) }
    val time = rememberTimePickerState(existing?.hour ?: 7, existing?.minute ?: 0)
    val choices = (stations + listOfNotNull(existing?.station)).distinctBy { it.id }
    val station = choices.firstOrNull { it.id == stationId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add routine" else "Edit routine") },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TimeInput(state = time)
                Text("Days")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DayOfWeek.entries.forEach { day ->
                        val bit = 1 shl day.ordinal
                        FilterChip(
                            selected = daysMask and bit != 0,
                            onClick = { daysMask = daysMask xor bit },
                            label = { Text(day.getDisplayName(TextStyle.SHORT, LocalLocale.current.platformLocale)) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = wakeUp, onCheckedChange = { wakeUp = it })
                    Text("Wake-up routine")
                }
                Text("Station")
                choices.forEach { candidate ->
                    FilterChip(
                        selected = stationId == candidate.id,
                        onClick = { stationId = candidate.id },
                        label = { Text(candidate.name) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = station != null && daysMask != 0,
                onClick = {
                    station ?: return@TextButton
                    onSave(ListeningRoutine(
                        id = existing?.id ?: UUID.randomUUID().toString(),
                        station = station,
                        hour = time.hour,
                        minute = time.minute,
                        days = DayOfWeek.entries.filter { daysMask and (1 shl it.ordinal) != 0 }.toSet(),
                        wakeUp = wakeUp,
                    ))
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (existing != null) TextButton(onClick = onDelete) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
