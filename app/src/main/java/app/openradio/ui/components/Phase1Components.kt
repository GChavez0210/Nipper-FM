package app.openradio.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.openradio.domain.CustomStreamBuilder
import app.openradio.domain.Station
import app.openradio.ui.AppViewModel
import app.openradio.ui.theme.LocalNeumorphicStyle
import app.openradio.ui.theme.neuInset
import app.openradio.ui.theme.neuRaised

/**
 * Tactile "Last Station" shortcut banner that prepares the last played station in the player
 * and opens Now Playing with playback ready to start on tap.
 */
@Composable
fun LastStationShortcutCard(
    station: Station,
    onOpenReady: () -> Unit,
    onPlayNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .neuRaised(shape = shape, elevation = 5.dp, accentGlow = accent)
            .clickable(onClick = onOpenReady)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StationArt(station, 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.History, contentDescription = null, tint = accent, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    "Last station • Ready to start",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                station.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (station.location.isNotEmpty()) {
                Text(
                    station.location,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = onPlayNow,
            modifier = Modifier
                .size(42.dp)
                .neuRaised(shape = RoundedCornerShape(14.dp), elevation = 4.dp),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "Play ${station.name}", tint = accent)
        }
    }
}

/**
 * Dialog for adding a custom HLS/M3U8, Icecast, or Shoutcast stream URL, with a live
 * playback preview before saving to Favorites (and an optional Favorite Group).
 */
@Composable
fun CustomStreamDialog(
    vm: AppViewModel,
    initialGroup: String? = null,
    onDismiss: () -> Unit,
) {
    val groups by vm.favoriteGroups.collectAsState()
    val ui by vm.playback.state.collectAsState()
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var selectedGroup by remember { mutableStateOf(initialGroup) }
    var previewed by remember { mutableStateOf(false) }

    val urlValid = CustomStreamBuilder.isValidUrl(url)
    val formatHint = when {
        url.isBlank() -> "Supports HLS (.m3u8), Icecast, and Shoutcast http(s):// streams"
        !urlValid -> "Enter a valid http:// or https:// stream URL"
        CustomStreamBuilder.isHlsUrl(url) -> "Detected format: HLS / M3U8 stream"
        else -> "Detected format: Icecast / Shoutcast direct stream"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add custom stream") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Station name") },
                    placeholder = { Text("e.g. SomaFM Groove Salad") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Stream URL") },
                    placeholder = { Text("https://.../stream.mp3 or .m3u8") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    formatHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (url.isNotBlank() && !urlValid) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )

                Text(
                    "Collection (optional)",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = selectedGroup == null,
                        onClick = { selectedGroup = null },
                        label = { Text("None") },
                    )
                    groups.forEach { g ->
                        FilterChip(
                            selected = selectedGroup == g,
                            onClick = { selectedGroup = g },
                            label = { Text(g) },
                        )
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = {
                            if (vm.previewCustomStream(name, url)) {
                                previewed = true
                            }
                        },
                        enabled = urlValid,
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Preview stream")
                    }
                    if (previewed && ui.station?.isCustom == true) {
                        StatusChip(ui.status, ui.recoveryAttempt, ui.recoveryMax)
                    }
                }
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = {
                    vm.saveCustomStream(name, url, selectedGroup) {
                        onDismiss()
                    }
                },
                enabled = name.isNotBlank() && urlValid,
            ) {
                Text("Save to Favorites")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Dialog to assign a favorite station to a Favorite Group (e.g. Commute, Late Night) or create a new group.
 * Android Auto car presets remain independently ordered.
 */
@Composable
fun GroupAssignDialog(
    station: Station,
    groups: List<String>,
    currentGroup: String?,
    onAssign: (String?) -> Unit,
    onCreateGroup: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newGroup by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Collection for ${station.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Organize favorites into collections. Android Auto presets stay independently ordered.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onAssign(null); onDismiss() }
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("No collection", style = MaterialTheme.typography.bodyLarge)
                    if (currentGroup == null) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                groups.forEach { group ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onAssign(group); onDismiss() }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(group, style = MaterialTheme.typography.bodyLarge)
                        }
                        if (currentGroup == group) {
                            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = newGroup,
                        onValueChange = { newGroup = it },
                        placeholder = { Text("New collection") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(
                        onClick = {
                            val clean = newGroup.trim()
                            if (clean.isNotEmpty()) {
                                onCreateGroup(clean)
                                onAssign(clean)
                                onDismiss()
                            }
                        },
                        enabled = newGroup.isNotBlank(),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Create collection")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
