package app.openradio.ui.components

import androidx.compose.foundation.Image
import androidx.compose.animation.Crossfade
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.openradio.R
import app.openradio.domain.PlaybackStatus
import app.openradio.domain.Station
import app.openradio.ui.theme.neuInset
import app.openradio.ui.theme.neuRaised
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent

/** The current song's cover art, falling back to the station logo while it loads or when it fails. */
@Composable
fun TrackCover(station: Station, artworkUrl: String?, modifier: Modifier = Modifier) {
    if (artworkUrl.isNullOrBlank()) {
        StationCover(station, modifier)
        return
    }
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        SubcomposeAsyncImage(
            model = artworkUrl,
            contentDescription = "Album artwork",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            loading = { StationCover(station, Modifier.fillMaxSize()) },
            error = { StationCover(station, Modifier.fillMaxSize()) },
        )
    }
}

@Composable
fun StationCover(station: Station, modifier: Modifier = Modifier) {
    Box(
        modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val logo: @Composable () -> Unit = {
            Image(
                painterResource(R.drawable.nipper_fm_logo),
                contentDescription = "Nipper FM",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (station.faviconUrl.isNullOrBlank()) {
            logo()
        } else {
            SubcomposeAsyncImage(
                model = station.faviconUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                loading = { logo() },
                error = { logo() },
            )
        }
    }
}

@Composable
fun StationArt(station: Station, size: Dp, modifier: Modifier = Modifier) {
    StationCover(station, modifier.size(size))
}

@Composable
fun StationRow(
    station: Station,
    isFavorite: Boolean,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
    failedLastAttempt: Boolean = false,
    trailing: @Composable () -> Unit = {},
) {
    val accent = MaterialTheme.colorScheme.primary
    val errColor = MaterialTheme.colorScheme.error
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .neuRaised(
                shape = shape,
                elevation = if (isCurrent) 7.dp else 5.dp,
                accentGlow = if (isCurrent) accent else null,
                borderWidth = if (isCurrent) 1.5.dp else 1.dp,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StationArt(station, 54.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                station.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = station.location.ifEmpty { station.tags.firstOrNull() ?: "" }
            if (sub.isNotEmpty()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isCurrent && !failedLastAttempt) {
                Spacer(Modifier.height(4.dp))
                // State is shown with an icon and a word, not colour alone.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.GraphicEq, null, Modifier.size(15.dp),
                        tint = accent,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Now playing",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = accent,
                    )
                }
            } else if (failedLastAttempt) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = errColor,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Failed last attempt",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = errColor,
                    )
                }
            }
        }
        trailing()
        Spacer(Modifier.width(4.dp))
        FavoriteButton(isFavorite, station.name, onToggleFavorite)
    }
}

@Composable
fun FavoriteButton(isFavorite: Boolean, name: String, onToggle: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(14.dp)
    IconButton(
        onClick = onToggle,
        modifier = Modifier
            .size(42.dp)
            .then(
                if (isFavorite) {
                    Modifier.neuInset(shape = shape, depth = 3.dp, accentBorder = accent)
                } else {
                    Modifier.neuRaised(shape = shape, elevation = 3.dp)
                },
            ),
    ) {
        Icon(
            if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = if (isFavorite) "Remove $name from favorites" else "Add $name to favorites",
            tint = if (isFavorite) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
fun PinButton(pinned: Boolean, name: String, enabled: Boolean = true, onToggle: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(14.dp)
    IconButton(
        onClick = onToggle,
        enabled = enabled || pinned,
        modifier = Modifier
            .size(42.dp)
            .then(
                if (pinned) {
                    Modifier.neuInset(shape = shape, depth = 3.dp, accentBorder = accent)
                } else {
                    Modifier.neuRaised(shape = shape, elevation = 3.dp)
                },
            ),
    ) {
        Icon(
            if (pinned) Icons.Filled.DirectionsCar else Icons.Outlined.DirectionsCar,
            contentDescription = if (pinned) "Remove $name from car presets" else "Add $name to car presets",
            tint = if (pinned) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
fun PlayPauseButton(isPlaying: Boolean, size: Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    Surface(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            onClick()
        },
        shape = CircleShape,
        color = colors.primary,
        contentColor = colors.onPrimary,
        modifier = modifier.size(size).semantics {
            contentDescription = if (isPlaying) "Pause" else "Play"
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Crossfade(targetState = isPlaying, label = "Playback icon") { playing ->
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(size * 0.5f),
                )
            }
        }
    }
}

@Composable
fun StatusChip(status: PlaybackStatus, attempt: Int, max: Int, modifier: Modifier = Modifier) {
    val (label, icon) = when (status) {
        PlaybackStatus.Live -> "Live" to Icons.Outlined.Sensors
        PlaybackStatus.Buffering -> "Buffering" to Icons.Outlined.Sync
        PlaybackStatus.Reconnecting -> (if (max > 0) "Reconnecting $attempt/$max" else "Reconnecting") to Icons.Outlined.Sync
        PlaybackStatus.Paused -> "Paused" to Icons.Filled.Pause
        PlaybackStatus.Unavailable -> "Unavailable" to Icons.Outlined.ErrorOutline
        PlaybackStatus.None -> return
    }
    val bad = status == PlaybackStatus.Unavailable
    val fg = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .neuInset(shape = shape, depth = 3.dp, accentBorder = fg)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = fg)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = fg)
    }
}

/** Static decorative waveform inside a recessed neumorphic trough; real metering is post-MVP. */
@Composable
fun WaveformArt(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val heights = remember {
        List(44) { i ->
            0.22f + 0.78f * kotlin.math.abs(kotlin.math.sin(i * 0.55f) * kotlin.math.cos(i * 0.23f))
        }
    }
    Box(
        modifier = modifier
            .neuInset(shape = RoundedCornerShape(18.dp), depth = 3.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Canvas(Modifier.fillMaxWidth().height(36.dp).semantics { contentDescription = "" }) {
            val gap = size.width / heights.size
            heights.forEachIndexed { i, h ->
                val bar = size.height * h
                val active = i % 3 != 0
                drawLine(
                    color = if (active) color.copy(alpha = 0.9f) else color.copy(alpha = 0.38f),
                    start = Offset(gap * i + gap / 2, (size.height - bar) / 2),
                    end = Offset(gap * i + gap / 2, (size.height + bar) / 2),
                    strokeWidth = gap * 0.46f,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge)
        if (trailing != null) {
            Text(
                trailing,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun MessagePanel(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(24.dp)
            .neuRaised(shape = RoundedCornerShape(24.dp), elevation = 6.dp)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (body.isNotEmpty()) {
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(4.dp))
            androidx.compose.material3.FilledTonalButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
