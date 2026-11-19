package app.openradio.ui.nowplaying

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.Crossfade
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.openradio.domain.OnAirEntry
import app.openradio.domain.PlaybackErrorDetail
import app.openradio.domain.PlaybackErrorKind
import app.openradio.domain.PlaybackStatus
import app.openradio.domain.SleepTimer
import app.openradio.domain.StreamFeed
import app.openradio.domain.VolumeTrim
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import kotlin.math.roundToInt
import app.openradio.playback.LivePosition
import app.openradio.playback.SignalInfo
import app.openradio.ui.AppViewModel
import app.openradio.ui.components.FavoriteButton
import app.openradio.ui.components.LiveAudioVisualizerPanel
import app.openradio.ui.components.NeuDropdownSelector
import app.openradio.ui.components.NeuMenuOption
import app.openradio.ui.components.NeuRotaryDial
import app.openradio.ui.components.PlayPauseButton
import app.openradio.ui.components.StationCover
import app.openradio.ui.components.StatusChip
import app.openradio.ui.theme.neuInset
import app.openradio.ui.theme.neuRaised
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.delay

/** Sleep dial stops in minutes; 0 = timer off. */
private val SLEEP_STEPS = listOf(0, 15, 30, 45, 60, 90)

private enum class NowPlayingMenu { Signal, OnAir, Source, Trim }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(vm: AppViewModel, onClose: () -> Unit) {
    val ui by vm.playback.state.collectAsState()
    val favoriteIds by vm.favoriteIds.collectAsState()
    val onAirHistory by vm.onAirHistory.collectAsState()
    val context = LocalContext.current
    val station = ui.station
    var showSleep by remember { mutableStateOf(false) }
    var showSignal by remember { mutableStateOf(false) }
    var showOnAir by remember { mutableStateOf(false) }
    var showFeeds by remember { mutableStateOf(false) }
    var showTrim by remember { mutableStateOf(false) }
    val alternateFeeds by vm.alternateFeeds.collectAsState()

    if (station == null) {
        // Nothing selected (e.g. the session ended): go back rather than show an empty screen.
        LaunchedEffect(Unit) { onClose() }
        return
    }

    val accent = MaterialTheme.colorScheme.primary

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
                    .neuRaised(shape = RoundedCornerShape(14.dp), elevation = 1.dp),
            ) {
                Icon(Icons.Filled.KeyboardArrowDown, "Close Now Playing")
            }
        }

        Spacer(Modifier.height(12.dp))

        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StationCover(
                station = station,
                modifier = Modifier.widthIn(max = 300.dp).fillMaxWidth(),
            )
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
            ui.trackTitle?.let { track ->
                Spacer(Modifier.height(14.dp))
                Text(
                    track,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                val currentBookmark = onAirHistory.firstOrNull {
                    it.stationId == station.id && it.trackTitle.equals(track, ignoreCase = true) && it.isBookmarked
                }
                val isCurrentBookmarked = currentBookmark != null
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier
                            .neuRaised(shape = RoundedCornerShape(50), elevation = 3.dp)
                            .clickable {
                                copyToClipboard(context, "$track — ${station.name}")
                                vm.showMessage("Copied track info")
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp), tint = accent)
                        Spacer(Modifier.width(5.dp))
                        Text("Copy", style = MaterialTheme.typography.labelMedium, color = accent)
                    }
                    Row(
                        modifier = Modifier
                            .then(
                                if (isCurrentBookmarked) {
                                    Modifier.neuInset(shape = RoundedCornerShape(50), depth = 2.5.dp, accentBorder = accent)
                                } else {
                                    Modifier.neuRaised(shape = RoundedCornerShape(50), elevation = 3.dp)
                                },
                            )
                            .clickable {
                                if (currentBookmark != null) vm.toggleOnAirBookmark(currentBookmark.id)
                                else vm.bookmarkCurrentTrack()
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BookmarkIcon(isCurrentBookmarked, Modifier.size(14.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            if (isCurrentBookmarked) "Saved" else "Save track",
                            style = MaterialTheme.typography.labelMedium,
                            color = accent,
                        )
                    }
                }
            }
            val detail = ui.errorDetail
            if (detail != null) {
                Spacer(Modifier.height(14.dp))
                PlaybackErrorCard(
                    detail = detail,
                    onAction = {
                        when (detail.kind) {
                            PlaybackErrorKind.WifiOnlyBlocked -> vm.playback.allowMobileDataOnce()
                            PlaybackErrorKind.UnsupportedStream -> {
                                vm.loadAlternateFeeds(station)
                                showFeeds = true
                            }
                            else -> vm.playback.retryCurrent()
                        }
                    },
                    onOtherFeeds = if (detail.kind == PlaybackErrorKind.StationUnavailable) {
                        { vm.loadAlternateFeeds(station); showFeeds = true }
                    } else {
                        null
                    },
                )
            } else if (ui.status == PlaybackStatus.Unavailable && ui.errorMessage != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    ui.errorMessage!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (ui.seekableLive) LiveSeekControls(vm)
        LiveAudioVisualizerPanel(ui.audioSessionId, ui.isPlaying)

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
            SleepButton(ui.sleepEndsAtMs, ui.sleepAtEndOfTrack) { showSleep = true }
        }
        Spacer(Modifier.height(22.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeuDropdownSelector(
                label = "More",
                active = false,
                leadingIcon = Icons.Filled.MoreHoriz,
                options = listOf(
                    NeuMenuOption("Signal details", NowPlayingMenu.Signal, "Codec, bitrate, buffer, output", Icons.Filled.GraphicEq),
                    NeuMenuOption(
                        if (onAirHistory.isNotEmpty()) "Recently on air (${onAirHistory.size})" else "Recently on air",
                        NowPlayingMenu.OnAir,
                        "Track history & bookmarks",
                        Icons.Filled.History,
                    ),
                    NeuMenuOption("Stream source", NowPlayingMenu.Source, "Alternate feeds, codec & bitrate", Icons.Filled.Tune),
                    NeuMenuOption(
                        "Volume trim",
                        NowPlayingMenu.Trim,
                        if (ui.volumeTrimDb == 0) "Balance this station's loudness" else "This station: ${VolumeTrim.label(ui.volumeTrimDb)}",
                        Icons.AutoMirrored.Filled.VolumeUp,
                    ),
                ),
                onSelect = { choice ->
                    when (choice) {
                        NowPlayingMenu.Signal -> showSignal = true
                        NowPlayingMenu.OnAir -> showOnAir = true
                        NowPlayingMenu.Source -> {
                            vm.loadAlternateFeeds(station)
                            showFeeds = true
                        }
                        NowPlayingMenu.Trim -> showTrim = true
                    }
                },
            )
            if (ui.sleepEndsAtMs > 0 && !ui.sleepAtEndOfTrack) {
                Row(
                    modifier = Modifier
                        .height(38.dp)
                        .neuRaised(shape = RoundedCornerShape(16.dp), elevation = 3.5.dp)
                        .clickable { vm.playback.addSleepMinutes(15) }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("+15 min", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = accent)
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    if (showSleep) {
        ModalBottomSheet(onDismissRequest = { showSleep = false }) {
            SleepSheet(
                endsAtMs = ui.sleepEndsAtMs,
                atEndOfTrack = ui.sleepAtEndOfTrack,
                canStopAfterTrack = ui.trackTitle != null,
                onSet = { vm.playback.setSleepTimer(it); showSleep = false },
                onAddFifteen = { vm.playback.addSleepMinutes(15); showSleep = false },
                onEndOfTrack = { vm.playback.sleepAtEndOfTrack(); showSleep = false },
            )
        }
    }
    if (showTrim) {
        ModalBottomSheet(onDismissRequest = { showTrim = false }) {
            VolumeTrimSheet(
                stationName = station.name,
                trimDb = ui.volumeTrimDb,
                onChange = vm.playback::setVolumeTrim,
            )
        }
    }
    if (showFeeds) {
        ModalBottomSheet(onDismissRequest = { showFeeds = false }) {
            StreamSourceSheet(
                feeds = alternateFeeds,
                currentUrl = station.streamUrl,
                onPick = { feed -> vm.selectStreamFeed(feed); showFeeds = false },
            )
        }
    }
    if (showSignal) {
        ModalBottomSheet(onDismissRequest = { showSignal = false }) {
            SignalSheet(vm)
        }
    }
    if (showOnAir) {
        ModalBottomSheet(onDismissRequest = { showOnAir = false }) {
            RecentlyOnAirSheet(
                entries = onAirHistory,
                onToggleBookmark = vm::toggleOnAirBookmark,
                onCopy = { entry ->
                    copyToClipboard(context, entry.copyText)
                    vm.showMessage("Copied: ${entry.copyText}")
                },
            )
        }
    }
}

@Composable
private fun BookmarkIcon(bookmarked: Boolean, modifier: Modifier = Modifier) {
    Crossfade(
        targetState = bookmarked,
        modifier = modifier.semantics {
            contentDescription = if (bookmarked) "Remove saved track" else "Save track"
        },
        label = "Track bookmark",
    ) { selected ->
        Icon(
            if (selected) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
            contentDescription = null,
            modifier = modifier,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("Track info", text))
}

@Composable
private fun RecentlyOnAirSheet(
    entries: List<OnAirEntry>,
    onToggleBookmark: (Long) -> Unit,
    onCopy: (OnAirEntry) -> Unit,
) {
    var bookmarkedOnly by remember { mutableStateOf(false) }
    val filtered = remember(entries, bookmarkedOnly) {
        if (bookmarkedOnly) entries.filter { it.isBookmarked } else entries
    }
    val formatter = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .padding(bottom = 28.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Recently On Air", style = MaterialTheme.typography.titleLarge)
            FilterChip(
                selected = bookmarkedOnly,
                onClick = { bookmarkedOnly = !bookmarkedOnly },
                label = { Text("Saved tracks") },
                leadingIcon = {
                    Icon(
                        if (bookmarkedOnly) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
        Spacer(Modifier.height(12.dp))

        if (filtered.isEmpty()) {
            Text(
                if (bookmarkedOnly) "No saved tracks yet."
                else "No tracks yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        } else {
            LazyColumn(Modifier.height(340.dp)) {
                items(filtered, key = { it.id }) { entry ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.trackTitle,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${entry.stationName} • ${formatter.format(Date(entry.timestampMs))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onCopy(entry) }) {
                            Icon(
                                Icons.Filled.ContentCopy,
                                contentDescription = "Copy track info for ${entry.trackTitle}",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        val haptics = LocalHapticFeedback.current
                        IconButton(onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            onToggleBookmark(entry.id)
                        }) {
                            BookmarkIcon(entry.isBookmarked)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun SleepButton(endsAtMs: Long, atEndOfTrack: Boolean, onClick: () -> Unit) {
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
                contentDescription = when {
                    atEndOfTrack -> "Sleep after this track"
                    endsAtMs > 0 -> "Sleep timer, ${formatMinutes(remaining)} left"
                    else -> "Set sleep timer"
                },
                tint = if (endsAtMs > 0) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (endsAtMs > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                if (atEndOfTrack) "Track" else formatMinutes(remaining),
                style = MaterialTheme.typography.labelMedium,
                color = accent,
            )
        }
    }
}

private fun formatMinutes(ms: Long): String {
    val total = (ms / 1000).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

@Composable
private fun SleepSheet(
    endsAtMs: Long,
    atEndOfTrack: Boolean,
    canStopAfterTrack: Boolean,
    onSet: (Int?) -> Unit,
    onAddFifteen: () -> Unit,
    onEndOfTrack: () -> Unit,
) {
    val active = endsAtMs > 0 && !atEndOfTrack
    val initial = remember(endsAtMs) {
        if (!active) {
            2 // 30 min default
        } else {
            val remainingMin = ((endsAtMs - System.currentTimeMillis()) / 60_000).toInt()
            SLEEP_STEPS.indices.minBy { abs(SLEEP_STEPS[it] - remainingMin) }
        }
    }
    var index by remember(initial) { mutableIntStateOf(initial) }
    val minutes = SLEEP_STEPS[index]
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Sleep timer", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "Audio fades out before playback stops.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        NeuRotaryDial(
            title = "Minutes",
            steps = SLEEP_STEPS.map { if (it == 0) "Off" else "$it" },
            selectedIndex = index,
            onSelectIndex = { index = it },
            subtitle = if (minutes == 0) "Timer off" else "$minutes min",
            dialSize = 160.dp,
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (active) OutlinedButton(onClick = onAddFifteen) { Text("+15 min") }
            FilledTonalButton(onClick = { onSet(minutes.takeIf { it > 0 }) }) {
                Text(if (minutes == 0) "Turn off" else if (active || atEndOfTrack) "Restart timer" else "Start timer")
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onEndOfTrack, enabled = canStopAfterTrack && !atEndOfTrack) {
            Text(if (atEndOfTrack) "Stopping after this track" else "Stop after this track")
        }
        Text(
            when {
                atEndOfTrack -> "Stops when the title changes, or after ${SleepTimer.END_OF_TRACK_CAP_MINUTES} min at most."
                canStopAfterTrack -> "Uses the station's track titles."
                else -> "This station isn't sending track titles."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun VolumeTrimSheet(stationName: String, trimDb: Int, onChange: (Int) -> Unit) {
    var value by remember(trimDb) { mutableIntStateOf(trimDb) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Volume trim", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "Remembered for $stationName. Cuts lower the player volume; boosts add gain.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            VolumeTrim.label(value),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { value = it.roundToInt() },
            onValueChangeFinished = { onChange(value) },
            valueRange = VolumeTrim.MIN_DB.toFloat()..VolumeTrim.MAX_DB.toFloat(),
            steps = VolumeTrim.MAX_DB - VolumeTrim.MIN_DB - 1,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(VolumeTrim.label(VolumeTrim.MIN_DB), style = MaterialTheme.typography.labelSmall)
            Text(VolumeTrim.label(VolumeTrim.MAX_DB), style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = { value = 0; onChange(0) }, enabled = value != 0) { Text("Reset to 0 dB") }
    }
}

@Composable
private fun PlaybackErrorCard(
    detail: PlaybackErrorDetail,
    onAction: () -> Unit,
    onOtherFeeds: (() -> Unit)?,
) {
    val err = MaterialTheme.colorScheme.error
    Column(
        Modifier
            .fillMaxWidth()
            .neuInset(shape = RoundedCornerShape(20.dp), depth = 3.dp, accentBorder = err)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (detail.kind == PlaybackErrorKind.WifiOnlyBlocked || detail.kind == PlaybackErrorKind.LostConnectivity) {
                    Icons.Filled.WifiOff
                } else {
                    Icons.Filled.ErrorOutline
                },
                contentDescription = null,
                tint = err,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(detail.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = err)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            detail.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = onAction) { Text(detail.actionLabel) }
            if (onOtherFeeds != null) OutlinedButton(onClick = onOtherFeeds) { Text("Other feeds") }
        }
    }
}

@Composable
private fun StreamSourceSheet(feeds: List<StreamFeed>, currentUrl: String, onPick: (StreamFeed) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .padding(bottom = 24.dp),
    ) {
        Text("Stream source", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        if (feeds.isEmpty()) {
            Row(Modifier.padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Looking for alternate feeds…", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(feeds, key = { it.streamUrl }) { feed ->
                    val active = feed.streamUrl.equals(currentUrl, ignoreCase = true)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !active) { onPick(feed) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                feed.label,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (active) accent else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                feed.qualityBadge,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (active) Text("Selected", style = MaterialTheme.typography.labelMedium, color = accent)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            if (feeds.size == 1) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "No alternate feeds found for this station.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
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
            FilledTonalButton(onClick = vm.playback::jumpToLive, enabled = !p.atLiveEdge) { Text("Live") }
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
