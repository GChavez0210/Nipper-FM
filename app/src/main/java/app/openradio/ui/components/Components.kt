package app.openradio.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.PushPin
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.openradio.domain.PlaybackStatus
import app.openradio.domain.Station
import app.openradio.ui.theme.LocalNeumorphicStyle
import app.openradio.ui.theme.neuInset
import app.openradio.ui.theme.neuRaised
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent

@Composable
fun StationArt(station: Station, size: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size / 4)
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .size(size)
            .neuInset(shape = shape, depth = 3.dp, accentBorder = accent.copy(alpha = 0.28f)),
        contentAlignment = Alignment.Center,
    ) {
        if (station.faviconUrl == null) {
            Monogram(station, size)
        } else {
            // Logos are often transparent or tiny, so they sit on a clean tile; the
            // monogram stands in while loading and when the logo can't be fetched.
            SubcomposeAsyncImage(
                model = station.faviconUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                loading = { Monogram(station, size) },
                error = { Monogram(station, size) },
                success = {
                    val imageScope = this
                    Box(
                        Modifier
                            .size(size)
                            .clip(shape)
                            .background(Color.White),
                        contentAlignment = Alignment.Center,
                    ) {
                        with(imageScope) {
                            SubcomposeAsyncImageContent(Modifier.size(size).padding(size / 10))
                        }
                    }
                },
                modifier = Modifier.size(size).clip(shape),
            )
        }
    }
}

@Composable
private fun Monogram(station: Station, size: Dp) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .size(size)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.25f),
                        accent.copy(alpha = 0.08f),
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            station.monogram,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            style = if (size >= 120.dp) MaterialTheme.typography.displaySmall else MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
fun StationRow(
    station: Station,
    isFavorite: Boolean,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    val accent = MaterialTheme.colorScheme.primary
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
            if (isCurrent) {
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
            if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
            contentDescription = if (pinned) "Unpin $name from car presets" else "Pin $name to car presets",
            tint = if (pinned) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
fun PlayPauseButton(isPlaying: Boolean, size: Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary
    val neu = LocalNeumorphicStyle.current
    if (size >= 64.dp && !neu.isFlatHighContrast) {
        // Large tactile neumorphic dial button (matches reference voice/music player control)
        Box(
            modifier = modifier
                .size(size)
                .neuRaised(
                    shape = CircleShape,
                    elevation = 8.dp,
                    accentGlow = if (isPlaying) accent else null,
                    borderWidth = 1.5.dp,
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            // Subtle glowing accent ring around the inner button
            Canvas(Modifier.size(size - 10.dp)) {
                drawArc(
                    color = accent.copy(alpha = if (isPlaying) 0.9f else 0.35f),
                    startAngle = -140f,
                    sweepAngle = if (isPlaying) 280f else 120f,
                    useCenter = false,
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            Box(
                modifier = Modifier
                    .size(size * 0.72f)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = if (isPlaying) {
                                listOf(accent, accent.copy(alpha = 0.82f))
                            } else {
                                listOf(neu.raisedTopLeft, neu.raisedBottomRight)
                            },
                        ),
                    )
                    .border(1.dp, accent.copy(alpha = 0.65f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = if (isPlaying) onAccent else accent,
                    modifier = Modifier.size(size * 0.38f),
                )
            }
        }
    } else {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = accent,
            contentColor = onAccent,
            shadowElevation = if (neu.isFlatHighContrast) 0.dp else 4.dp,
            border = BorderStroke(1.dp, accent.copy(alpha = 0.85f)),
            modifier = modifier.size(size),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
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

/**
 * Tactile neumorphic dial & radio-wave halo used on Now Playing (inspired by reference photos).
 */
@Composable
fun SignalArt(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val neu = LocalNeumorphicStyle.current
    val trackColor = if (neu.isDark) Color(0xFF13151B) else Color(0xFFDCE3EE)
    val plateTop = neu.raisedTopLeft
    val plateBottom = neu.raisedBottomRight
    Canvas(modifier.semantics { contentDescription = "" }) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val outerR = size.minDimension * 0.44f
        val ringR = size.minDimension * 0.36f
        val innerR = size.minDimension * 0.27f

        // Outer soft platter shadow & disc
        drawCircle(
            brush = Brush.linearGradient(
                colors = listOf(plateTop, plateBottom),
                start = Offset(center.x - outerR, center.y - outerR),
                end = Offset(center.x + outerR, center.y + outerR),
            ),
            radius = outerR,
            center = center,
        )
        drawCircle(
            color = color.copy(alpha = 0.18f),
            radius = outerR,
            center = center,
            style = Stroke(width = 1.5.dp.toPx()),
        )

        // Recessed dial track groove
        val strokeW = size.minDimension * 0.045f
        drawCircle(
            color = trackColor,
            radius = ringR,
            center = center,
            style = Stroke(width = strokeW),
        )

        // Glowing accent arc along the dial groove (like the +40% and voice ring in reference photo 1)
        drawArc(
            brush = Brush.sweepGradient(
                colors = listOf(
                    color.copy(alpha = 0.15f),
                    color.copy(alpha = 0.85f),
                    color,
                    color.copy(alpha = 0.2f),
                ),
                center = center,
            ),
            startAngle = -215f,
            sweepAngle = 250f,
            useCenter = false,
            topLeft = Offset(center.x - ringR, center.y - ringR),
            size = Size(ringR * 2, ringR * 2),
            style = Stroke(width = strokeW * 0.82f, cap = StrokeCap.Round),
        )

        // Inner raised medallion
        drawCircle(
            brush = Brush.linearGradient(
                colors = listOf(plateTop, plateBottom),
                start = Offset(center.x - innerR, center.y - innerR),
                end = Offset(center.x + innerR, center.y + innerR),
            ),
            radius = innerR,
            center = center,
        )
        drawCircle(
            color = color.copy(alpha = 0.38f),
            radius = innerR,
            center = center,
            style = Stroke(width = 1.2.dp.toPx()),
        )

        // Subtle broadcast signal arcs inside medallion
        for (i in 1..3) {
            val r = innerR * (0.28f + i * 0.18f)
            drawArc(
                color = color.copy(alpha = 0.9f - i * 0.22f),
                startAngle = 205f,
                sweepAngle = 130f,
                useCenter = false,
                topLeft = Offset(center.x - r, center.y - r + innerR * 0.12f),
                size = Size(r * 2, r * 2),
                style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        drawCircle(
            color = color,
            radius = 4.dp.toPx(),
            center = Offset(center.x, center.y + innerR * 0.18f),
        )
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
