package app.openradio.ui.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.openradio.domain.PlaybackStatus
import app.openradio.playback.LivePosition
import app.openradio.playback.SignalInfo
import app.openradio.ui.AppViewModel
import app.openradio.ui.components.FavoriteButton
import app.openradio.ui.components.PlayPauseButton
import app.openradio.ui.components.SignalArt
import app.openradio.ui.components.StationArt
import app.openradio.ui.components.StatusChip
import app.openradio.ui.components.WaveformArt
import app.openradio.ui.theme.neuInset
import app.openradio.ui.theme.neuRaised
import kotlinx.coroutines.delay

private val SLEEP_OPTIONS = listOf(15, 30, 45, 60, 90)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(vm: AppViewModel, onClose: () -> Unit) {
    val ui by vm.playback.state.collectAsState()
    val favoriteIds by vm.favoriteIds.collectAsState()
    val station = ui.station
    var showSleep by remember { mutableStateOf(false) }
    var showSignal by remember { mutableStateOf(false) }

    if (station == null) {
        // Nothing selected (e.g. the session ended): go back rather than show an empty screen.
        LaunchedEffect(Unit) { onClose() }
        return
    }

    val accent = MaterialTheme.colorScheme.primary
    val isLive = ui.status == PlaybackStatus.Live

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(44.dp)
                    .neuRaised(shape = RoundedCornerShape(14.dp), elevation = 4.dp),
            ) {
                Icon(Icons.Filled.KeyboardArrowDown, "Close Now Playing")
            }
        }

        Spacer(Modifier.height(12.dp))

        // Tactile neumorphic hero card with circular dial & station medallion (inspired by reference photos)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .neuRaised(
                    shape = RoundedCornerShape(30.dp),
                    elevation = 8.dp,
                    accentGlow = if (isLive) accent else null,
                )
                .padding(horizontal = 20.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(220.dp)
                    .neuRaised(
                        shape = CircleShape,
                        elevation = 7.dp,
                        accentGlow = if (isLive) accent else null,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                SignalArt(Modifier.fillMaxSize())
                StationArt(station, 76.dp)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                station.name,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            if (station.location.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    station.location,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(14.dp))
            StatusChip(ui.status, ui.recoveryAttempt, ui.recoveryMax)
            ui.trackTitle?.let {
                Spacer(Modifier.height(14.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
            if (ui.status == PlaybackStatus.Unavailable && ui.errorMessage != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    ui.errorMessage!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (ui.seekableLive) {
            LiveSeekControls(vm)
        } else {
            Spacer(Modifier.height(18.dp))
            WaveformArt(Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(22.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FavoriteButton(station.id in favoriteIds, station.name) { vm.toggleFavorite(station) }
            PlayPauseButton(
                isPlaying = ui.status == PlaybackStatus.Live || ui.status == PlaybackStatus.Buffering || ui.status == PlaybackStatus.Reconnecting,
                size = 88.dp,
                onClick = vm.playback::togglePlayPause,
            )
            SleepButton(ui.sleepEndsAtMs) { showSleep = true }
        }
        Spacer(Modifier.height(22.dp))
        OutlinedButton(
            onClick = { showSignal = true },
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .neuRaised(shape = RoundedCornerShape(50), elevation = 4.dp),
        ) {
            Text("Signal details", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(32.dp))
    }

    if (showSleep) SleepDialog(
        active = ui.sleepEndsAtMs > 0,
        onPick = { vm.playback.setSleepTimer(it); showSleep = false },
        onDismiss = { showSleep = false },
    )
    if (showSignal) {
        ModalBottomSheet(onDismissRequest = { showSignal = false }) {
            SignalSheet(vm)
        }
    }
}

@Composable
private fun SleepButton(endsAtMs: Long, onClick: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(endsAtMs) {
        while (endsAtMs > 0) { now = System.currentTimeMillis(); delay(1_000) }
    }
    val remaining = (endsAtMs - now).coerceAtLeast(0)
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(14.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(42.dp)
                .then(
                    if (endsAtMs > 0) {
                        Modifier.neuInset(shape = shape, depth = 3.dp, accentBorder = accent)
                    } else {
                        Modifier.neuRaised(shape = shape, elevation = 3.dp)
                    },
                ),
        ) {
            Icon(
                Icons.Outlined.Bedtime,
                contentDescription = if (endsAtMs > 0) "Sleep timer, ${formatMinutes(remaining)} left" else "Set sleep timer",
                tint = if (endsAtMs > 0) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (endsAtMs > 0) {
            Spacer(Modifier.height(4.dp))
            Text(formatMinutes(remaining), style = MaterialTheme.typography.labelMedium, color = accent)
        }
    }
}

private fun formatMinutes(ms: Long): String {
    val total = (ms / 1000).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

@Composable
private fun SleepDialog(active: Boolean, onPick: (Int?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep timer") },
        text = {
            Column {
                SLEEP_OPTIONS.forEach { m ->
                    TextButton(onClick = { onPick(m) }, modifier = Modifier.fillMaxWidth()) { Text("$m minutes") }
                }
                if (active) TextButton(onClick = { onPick(null) }, modifier = Modifier.fillMaxWidth()) { Text("Turn off timer") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Shown only when the current stream exposes a seekable live window. */
@Composable
private fun LiveSeekControls(vm: AppViewModel) {
    var pos by remember { mutableStateOf<LivePosition?>(null) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(Unit) {
        while (true) { pos = vm.playback.livePosition(); delay(500) }
    }
    val p = pos ?: return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 18.dp)
            .neuInset(shape = RoundedCornerShape(22.dp), depth = 4.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Slider(
            value = dragging ?: (p.positionMs.toFloat() / p.windowMs),
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let(vm.playback::seekToFraction); dragging = null },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (p.atLiveEdge) "Live" else "-" + formatMinutes(p.behindLiveMs), style = MaterialTheme.typography.labelMedium)
            Text("Live window ${formatMinutes(p.windowMs)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            OutlinedButton(onClick = { vm.playback.seekBack() }) { Text("−15s") }
            FilledTonalButton(onClick = vm.playback::jumpToLive, enabled = !p.atLiveEdge) { Text("Jump to Live") }
            OutlinedButton(onClick = { vm.playback.seekForward() }) { Text("+30s") }
        }
    }
}

@Composable
private fun SignalSheet(vm: AppViewModel) {
    var info by remember { mutableStateOf(SignalInfo()) }
    LaunchedEffect(Unit) { while (true) { info = vm.playback.signalSnapshot(); delay(1_000) } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).padding(bottom = 24.dp)) {
        Text("Signal details", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        SignalRow("Codec", info.codec)
        SignalRow(
            "Bitrate",
            info.bitrateKbps?.let { "$it kbps" + if (info.bitrateFromDirectory) " (from directory)" else "" },
        )
        SignalRow("Buffer", info.bufferedMs?.let { "%.1f s".format(it / 1000.0) })
        SignalRow("Output", info.route)
        Spacer(Modifier.height(8.dp))
        Text(
            "Loudness and stereo-phase metering are planned for a later release.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SignalRow(label: String, value: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value ?: "Unavailable", style = MaterialTheme.typography.bodyLarge)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
