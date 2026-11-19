package app.openradio.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.openradio.domain.BackupPayload
import app.openradio.domain.BackupPreviewSummary
import app.openradio.domain.BufferProfile
import app.openradio.domain.DataUsageEstimator
import app.openradio.domain.DataUsageMath
import app.openradio.domain.MonthlyUsage
import androidx.compose.foundation.layout.PaddingValues
import app.openradio.domain.Station
import app.openradio.ui.AppViewModel
import app.openradio.ui.components.NeuDropdownSelector
import app.openradio.ui.components.NeuMenuOption
import app.openradio.ui.components.PlayPauseButton
import app.openradio.ui.components.StationArt
import app.openradio.ui.components.WaveformArt
import app.openradio.ui.theme.Accent
import app.openradio.ui.theme.ColorMath
import app.openradio.ui.theme.DarkStyle
import app.openradio.ui.theme.ThemeMode
import app.openradio.ui.theme.neuInset
import app.openradio.ui.theme.neuRaised
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit, onOpenAbout: () -> Unit) {
    val prefs by vm.appearance.collectAsState()
    val accent = MaterialTheme.colorScheme.primary
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingRestore by remember { mutableStateOf<Pair<BackupPayload, BackupPreviewSummary>?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val json = vm.buildBackupJson()
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(json.toByteArray(Charsets.UTF_8))
                        } ?: error("Cannot open output stream")
                    }.isSuccess
                }
                vm.showMessage(if (ok) "Backup exported." else "Failed to export backup.")
            }
        }
    }

    var errorLogCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) { errorLogCount = vm.errorLogCount() }

    val errorLogLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val version = runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull() ?: "?"
                val device = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
                val text = vm.buildErrorLogText(version, device)
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(text.toByteArray(Charsets.UTF_8))
                        } ?: error("Cannot open output stream")
                    }.isSuccess
                }
                vm.showMessage(if (ok) "Error log exported." else "Failed to export error log.")
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val raw = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    }.getOrNull()
                }
                val parsed = raw?.let(vm::parseBackup)
                if (parsed != null) {
                    pendingRestore = parsed
                } else {
                    vm.showMessage("Invalid backup file.")
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(44.dp)
                    .neuRaised(shape = RoundedCornerShape(14.dp), elevation = 4.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            Spacer(Modifier.width(14.dp))
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
        }

        PlaybackAudioSettings(vm)
        Section("Scheduled listening")
        ListeningRoutinesSettings(
            Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp)
                .neuRaised(shape = RoundedCornerShape(24.dp), elevation = 5.dp)
                .padding(18.dp),
        )

        Section("Theme")
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .neuInset(shape = RoundedCornerShape(22.dp), depth = 4.dp)
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThemeMode.entries.forEach { mode ->
                val selected = prefs.themeMode == mode
                val shape = RoundedCornerShape(16.dp)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (selected) {
                                Modifier.neuRaised(shape = shape, elevation = 4.dp, accentGlow = accent)
                            } else {
                                Modifier
                            },
                        )
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = { vm.setThemeMode(mode) },
                        )
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        mode.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Section("Dark surface")
        Column(
            Modifier
                .selectableGroup()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DarkStyle.entries.forEach { style ->
                val selected = prefs.darkStyle == style
                val shape = RoundedCornerShape(20.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(
                            if (selected) {
                                Modifier.neuInset(shape = shape, depth = 4.dp, accentBorder = accent)
                            } else {
                                Modifier.neuRaised(shape = shape, elevation = 5.dp)
                            },
                        )
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = { vm.setDarkStyle(style) },
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            style.label,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            style.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Section("Accent color")
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .neuRaised(shape = RoundedCornerShape(24.dp), elevation = 5.dp)
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val entries = Accent.entries
            val firstRow = entries.take(4)
            val secondRow = entries.drop(4)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                firstRow.forEach { item ->
                    Swatch(item, item == prefs.accent) { vm.setAccent(item) }
                }
            }
            if (secondRow.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    secondRow.forEach { item ->
                        Swatch(item, item == prefs.accent) { vm.setAccent(item) }
                    }
                }
            }
        }

        Section("Preview")
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .neuRaised(
                    shape = RoundedCornerShape(26.dp),
                    elevation = 7.dp,
                    accentGlow = accent,
                )
                .padding(18.dp),
        ) {
            val demo = Station("preview", "KEXP 90.3", "", country = "United States", state = "Seattle")
            Row(verticalAlignment = Alignment.CenterVertically) {
                StationArt(demo, 56.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(demo.name, style = MaterialTheme.typography.titleMedium)
                    Text("Seattle", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                PlayPauseButton(isPlaying = false, size = 48.dp, onClick = {})
            }
            Spacer(Modifier.height(14.dp))
            WaveformArt(Modifier.fillMaxWidth())
        }

        Section("Backup & Restore")
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .neuRaised(shape = RoundedCornerShape(24.dp), elevation = 5.dp)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Save or restore favorites, collections, car presets, custom streams, and appearance.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilledTonalButton(
                    onClick = { exportLauncher.launch("nipperfm-backup.json") },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Export")
                }
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Restore")
                }
            }
        }

        Section("Diagnostics")
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .neuRaised(shape = RoundedCornerShape(24.dp), elevation = 5.dp)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Playback errors, retries and feed failovers are kept on this device only. " +
                    "Export the log to share it when reporting a problem." +
                    (errorLogCount?.let { " $it entries." } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilledTonalButton(
                    onClick = { errorLogLauncher.launch("nipperfm-playback-errors.txt") },
                    enabled = errorLogCount != 0,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Export log")
                }
                OutlinedButton(
                    onClick = {
                        vm.clearErrorLog()
                        errorLogCount = 0
                    },
                    enabled = errorLogCount != 0,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Clear")
                }
            }
        }

        Section("About")
        OutlinedButton(
            onClick = onOpenAbout,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Text("About Nipper FM")
        }
        Spacer(Modifier.height(32.dp))
    }

    pendingRestore?.let { (payload, summary) ->
        RestorePreviewDialog(
            summary = summary,
            onMerge = {
                vm.applyBackup(payload, replace = false)
                pendingRestore = null
            },
            onReplace = {
                vm.applyBackup(payload, replace = true)
                pendingRestore = null
            },
            onDismiss = { pendingRestore = null },
        )
    }
}

@Composable
private fun PlaybackAudioSettings(vm: AppViewModel) {
    val prefs by vm.playbackPrefs.collectAsState()
    val resumePreference = remember(vm) { vm.resumeHeadset }
    val resumeHeadset by resumePreference.collectAsState(initial = false)

    Section("Playback & Audio")
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .neuRaised(shape = RoundedCornerShape(24.dp), elevation = 5.dp)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PlaybackSwitch("Wi-Fi only", prefs.wifiOnly, vm::setWifiOnly)
        PlaybackSwitch("Resume when headphones reconnect", resumeHeadset, vm::setResumeHeadset)
        Text("Resumes after an audio device disconnects.", style = MaterialTheme.typography.bodySmall)
        DataUsageSummary(vm)
        Text("Buffer profile", style = MaterialTheme.typography.titleMedium)
        NeuDropdownSelector(
            label = "${prefs.bufferProfile.label} · ${prefs.bufferProfile.subtitle}",
            active = true,
            options = BufferProfile.entries.map { NeuMenuOption(it.label, it, it.subtitle) },
            modifier = Modifier.fillMaxWidth(),
            fillAnchor = true,
            onSelect = vm::setBufferProfile,
        )
        EqualizerPresetPicker(prefs.eqPreset, vm::setEqPreset)
        PlaybackSwitch("Spatial audio", prefs.spatialAudio, vm::setSpatialAudio)
        PlaybackSwitch("Loudness normalization", prefs.loudnessNorm, vm::setLoudnessNorm)
    }
}

/** Measured streaming data for this month and last month, split into mobile and Wi-Fi. */
@Composable
private fun DataUsageSummary(vm: AppViewModel) {
    val months by vm.dataUsage.collectAsState()
    val thisKey = remember { DataUsageMath.monthKey(System.currentTimeMillis()) }
    val current = months.firstOrNull { it.month == thisKey } ?: MonthlyUsage(thisKey, 0, 0)
    val last = months.firstOrNull { it.month == DataUsageMath.previousMonth(thisKey) }
    Text("Data streamed", style = MaterialTheme.typography.titleMedium)
    UsageLine("This month", current)
    Text(
        "${DataUsageEstimator.formatHourly(128)} at 128 kbps on mobile data.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (last != null) UsageLine("Last month", last)
    if (months.isNotEmpty()) {
        TextButton(onClick = vm::resetDataUsage, contentPadding = PaddingValues(0.dp)) { Text("Reset counters") }
    }
}

@Composable
private fun UsageLine(label: String, usage: MonthlyUsage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            "Mobile ${DataUsageMath.format(usage.mobileBytes)} · Wi-Fi ${DataUsageMath.format(usage.wifiBytes)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PlaybackSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun RestorePreviewDialog(
    summary: BackupPreviewSummary,
    onMerge: () -> Unit,
    onReplace: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore backup preview") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Choose how to restore these items.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PreviewRow("Favorites", "${summary.favoriteCount}")
                PreviewRow("Collections", "${summary.groupCount}")
                PreviewRow("Car Presets", "${summary.presetCount}")
                PreviewRow("Custom Streams", "${summary.customStreamCount}")
                PreviewRow("Appearance", summary.appearanceSummary)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onReplace) { Text("Replace") }
                FilledTonalButton(onClick = onMerge) { Text("Merge") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun PreviewRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(start = 18.dp, top = 22.dp, bottom = 10.dp),
    )
}

@Composable
private fun Swatch(accent: Accent, selected: Boolean, onClick: () -> Unit) {
    val isDark = MaterialTheme.colorScheme.background.luminanceIsDark()
    // For Graphite in Light mode, display the warm off-white swatch inside a crisp dark-slate ring
    // so the swatch looks off-white while remaining clearly visible on Paper White.
    val isLightGraphite = !isDark && accent == Accent.OffWhite
    val swatchArgb = if (isDark || isLightGraphite) accent.dark else accent.light
    val fill = Color(swatchArgb)
    val checkColor = if (isLightGraphite) Color(accent.light) else Color(ColorMath.onColor(swatchArgb))
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .semantics { contentDescription = accent.label + if (selected) ", selected" else "" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .then(
                    if (selected) {
                        Modifier
                            .border(2.5.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            .padding(4.dp)
                    } else {
                        Modifier
                    },
                )
                .clip(CircleShape)
                .background(fill)
                .border(
                    width = if (isLightGraphite) 2.dp else 1.dp,
                    color = if (isLightGraphite) Color(accent.light) else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = checkColor,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            accent.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun Color.luminanceIsDark(): Boolean = (red * 0.2126f + green * 0.7152f + blue * 0.0722f) < 0.4f
