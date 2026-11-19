package app.openradio.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.openradio.domain.AudioVisualizerMath
import app.openradio.ui.theme.LocalNeumorphicStyle
import app.openradio.ui.theme.neuInset
import app.openradio.ui.theme.neuRaised
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * Sleek, strictly single-line (`46.dp` height) neumorphic search input bar that never wraps
 * placeholder text onto a second line.
 */
@Composable
fun CompactNeuSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    trailingContent: @Composable () -> Unit = {},
) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(23.dp)
    Row(
        modifier = modifier
            .height(46.dp)
            .neuInset(
                shape = shape,
                depth = 3.5.dp,
                accentBorder = if (value.isNotEmpty()) accent else null,
            )
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = if (value.isNotEmpty()) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions.Default,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            IconButton(
                onClick = { onValueChange("") },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Clear search",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        trailingContent()
    }
}

/**
 * Minimalistic neumorphic dropdown selector pill with an attached [DropdownMenu].
 */
data class NeuMenuOption<T>(
    val label: String,
    val value: T,
    val subtitle: String? = null,
    val icon: ImageVector? = null,
)

@Composable
fun <T> NeuDropdownSelector(
    label: String,
    active: Boolean,
    options: List<NeuMenuOption<T>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    onMenuOpened: () -> Unit = {},
    iconOnly: Boolean = false,
    fillAnchor: Boolean = false,
    labelOverflow: TextOverflow = TextOverflow.Ellipsis,
) {
    var expanded by remember { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "Menu arrow")
    val haptics = LocalHapticFeedback.current
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(16.dp)
    val fg = if (active) accent else MaterialTheme.colorScheme.onSurface

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .then(if (fillAnchor) Modifier.fillMaxWidth() else Modifier)
                .height(if (iconOnly) 48.dp else 38.dp)
                .then(
                    if (active) {
                        Modifier.neuInset(shape = shape, depth = 3.dp, accentBorder = accent)
                    } else {
                        Modifier.neuRaised(shape = shape, elevation = 3.5.dp)
                    },
                )
                .clickable(role = Role.DropdownList) {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onMenuOpened()
                    expanded = true
                }
                .padding(horizontal = if (iconOnly) 14.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (leadingIcon != null) {
                Icon(
                    leadingIcon,
                    contentDescription = if (iconOnly) label else null,
                    tint = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(if (iconOnly) 20.dp else 15.dp),
                )
                if (!iconOnly) Spacer(Modifier.width(6.dp))
            }
            if (!iconOnly) Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                color = fg,
                maxLines = 1,
                overflow = labelOverflow,
                modifier = Modifier.weight(1f, fill = fillAnchor),
            )
            if (!iconOnly) Spacer(Modifier.width(2.dp))
            if (!iconOnly) Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(18.dp).rotate(arrowRotation),
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    leadingIcon = opt.icon?.let { ic ->
                        { Icon(ic, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp)) }
                    },
                    text = {
                        Column {
                            Text(
                                opt.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = if (labelOverflow == TextOverflow.Clip) 1 else Int.MAX_VALUE,
                                overflow = labelOverflow,
                            )
                            if (!opt.subtitle.isNullOrBlank()) {
                                Text(
                                    opt.subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        onSelect(opt.value)
                    },
                )
            }
        }
    }
}

/**
 * Tactile neumorphic rotary dial selector (inspired by hardware radio knobs & reference photo 1).
 * Users can tap or drag around the dial arc—or tap the center knob to step through [steps].
 */
@Composable
fun NeuRotaryDial(
    title: String,
    steps: List<String>,
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    dialSize: Dp = 128.dp,
) {
    val accent = MaterialTheme.colorScheme.primary
    val neu = LocalNeumorphicStyle.current
    val safeIndex = selectedIndex.coerceIn(0, (steps.size - 1).coerceAtLeast(0))
    val fraction by animateFloatAsState(
        if (steps.size <= 1) 0f else safeIndex.toFloat() / steps.lastIndex,
        label = "Dial position",
    )
    val haptics = LocalHapticFeedback.current
    var requestedIndex by remember(safeIndex, steps) { mutableIntStateOf(safeIndex) }
    val select by rememberUpdatedState<(Int) -> Unit> { index ->
        if (index in steps.indices && index != requestedIndex) {
            requestedIndex = index
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onSelectIndex(index)
        }
    }
    val nextIndex by rememberUpdatedState { (requestedIndex + 1) % steps.size.coerceAtLeast(1) }

    // Sweep from -135 deg (bottom-left) to +135 deg (bottom-right), where 0 deg is top (12 o'clock)
    fun angleToIndex(dx: Float, dy: Float): Int {
        // atan2(dx, -dy) gives 0 at top, -PI..PI clockwise
        val deg = (atan2(dx.toDouble(), -dy.toDouble()) * 180.0 / PI).toFloat()
        val clamped = deg.coerceIn(-135f, 135f)
        val norm = (clamped + 135f) / 270f
        return (norm * (steps.size - 1)).roundToInt().coerceIn(0, steps.size - 1)
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(dialSize)
                .neuRaised(
                    shape = CircleShape,
                    elevation = 6.dp,
                )
                .semantics {
                    contentDescription = title
                    stateDescription = steps.getOrNull(safeIndex).orEmpty()
                    if (steps.size > 1) {
                        progressBarRangeInfo = ProgressBarRangeInfo(
                            safeIndex.toFloat(), 0f..steps.lastIndex.toFloat(), steps.size - 2,
                        )
                        setProgress { value ->
                            val index = value.roundToInt().coerceIn(steps.indices)
                            if (index == requestedIndex) false else {
                                select(index)
                                true
                            }
                        }
                    }
                }
                .pointerInput(steps.size) {
                    if (steps.isEmpty()) return@pointerInput
                    detectTapGestures { offset ->
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val dx = offset.x - cx
                        val dy = offset.y - cy
                        val dist = kotlin.math.hypot(dx.toDouble(), dy.toDouble())
                        val minDim = minOf(size.width, size.height)
                        if (dist < minDim * 0.22) {
                            select(nextIndex())
                        } else {
                            select(angleToIndex(dx, dy))
                        }
                    }
                }
                .pointerInput(steps.size) {
                    if (steps.isEmpty()) return@pointerInput
                    detectDragGestures { change, _ ->
                        change.consume()
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        select(angleToIndex(change.position.x - cx, change.position.y - cy))
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val trackColor = if (neu.isDark) Color(0xFF14161C) else Color(0xFFD9E1EC)
            val tickColor = MaterialTheme.colorScheme.outlineVariant
            Canvas(Modifier.matchParentSize().padding(10.dp)) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val arcRadius = size.minDimension * 0.41f
                val knobRadius = size.minDimension * 0.29f
                val strokeW = 6.dp.toPx()

                // Recessed groove track (-225 deg to +45 deg in Canvas coordinates where 0 is 3 o'clock)
                drawArc(
                    color = trackColor,
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                    size = Size(arcRadius * 2, arcRadius * 2),
                    style = Stroke(width = strokeW, cap = StrokeCap.Round),
                )

                // Active accent sweep
                val activeSweep = 270f * fraction
                if (activeSweep > 2f) {
                    drawArc(
                        color = accent,
                        startAngle = 135f,
                        sweepAngle = activeSweep,
                        useCenter = false,
                        topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                        size = Size(arcRadius * 2, arcRadius * 2),
                        style = Stroke(width = strokeW, cap = StrokeCap.Round),
                    )
                }

                // Tick dots around the outer bezel
                steps.indices.forEach { i ->
                    val stepFrac = if (steps.size <= 1) 0f else i.toFloat() / (steps.size - 1)
                    val angleRad = (135.0 + 270.0 * stepFrac) * PI / 180.0
                    val tickR = size.minDimension * 0.48f
                    val tx = center.x + (cos(angleRad) * tickR).toFloat()
                    val ty = center.y + (sin(angleRad) * tickR).toFloat()
                    drawCircle(
                        color = if (i <= safeIndex) accent else tickColor,
                        radius = if (i == safeIndex) 3.dp.toPx() else 2.dp.toPx(),
                        center = Offset(tx, ty),
                    )
                }

                // Inner rotary knob disc
                drawCircle(
                    brush = Brush.linearGradient(
                        colors = listOf(neu.raisedTopLeft, neu.raisedBottomRight),
                        start = Offset(center.x - knobRadius, center.y - knobRadius),
                        end = Offset(center.x + knobRadius, center.y + knobRadius),
                    ),
                    radius = knobRadius,
                    center = center,
                )
                drawCircle(
                    color = accent.copy(alpha = 0.35f),
                    radius = knobRadius,
                    center = center,
                    style = Stroke(width = 1.2.dp.toPx()),
                )

                // Indicator notch on the knob pointing to the selected step
                val pointerAngleRad = (135.0 + 270.0 * fraction) * PI / 180.0
                val pInner = knobRadius * 0.42f
                val pOuter = knobRadius * 0.80f
                drawLine(
                    color = accent,
                    start = Offset(
                        center.x + (cos(pointerAngleRad) * pInner).toFloat(),
                        center.y + (sin(pointerAngleRad) * pInner).toFloat(),
                    ),
                    end = Offset(
                        center.x + (cos(pointerAngleRad) * pOuter).toFloat(),
                        center.y + (sin(pointerAngleRad) * pOuter).toFloat(),
                    ),
                    strokeWidth = 2.8.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = steps.getOrElse(safeIndex) { "" },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Shows the session spectrum only while Android is delivering real FFT frames. */
@Composable
fun LiveAudioVisualizerPanel(audioSessionId: Int, isPlaying: Boolean) {
    val context = LocalContext.current
    if (!isPlaying || audioSessionId <= 0 ||
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
    ) return

    var latestFrame by remember(audioSessionId) { mutableStateOf<Pair<Long, List<Float>>?>(null) }
    DisposableEffect(audioSessionId) {
        var visualizer: Visualizer? = null
        var active = true
        runCatching {
            val capture = Visualizer(audioSessionId)
            visualizer = capture
            check(capture.setCaptureSize(Visualizer.getCaptureSizeRange().first()) == Visualizer.SUCCESS)
            val result = capture.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(vis: Visualizer?, waveform: ByteArray?, samplingRate: Int) = Unit

                    override fun onFftDataCapture(vis: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        if (active && fft != null && fft.size >= 32) {
                            latestFrame = SystemClock.elapsedRealtime() to AudioVisualizerMath.spectrumFromFft(fft)
                        }
                    }
                },
                minOf(10_000, Visualizer.getMaxCaptureRate()),
                false,
                true,
            )
            check(result == Visualizer.SUCCESS && capture.setEnabled(true) == Visualizer.SUCCESS)
        }.onFailure {
            active = false
            runCatching { visualizer?.release() }
            visualizer = null
        }
        onDispose {
            active = false
            runCatching { visualizer?.release() }
        }
    }

    val (capturedAt, bands) = latestFrame ?: return
    LaunchedEffect(capturedAt) {
        delay(1_000)
        latestFrame = null
    }

    val accent = MaterialTheme.colorScheme.primary
    Canvas(
        Modifier.fillMaxWidth().height(44.dp)
            .semantics { contentDescription = "Live audio spectrum" },
    ) {
        val gap = size.width / bands.size
        val barWidth = gap * 0.56f
        bands.forEachIndexed { index, level ->
            val height = size.height * level
            drawRoundRect(
                color = accent,
                topLeft = Offset(gap * index + (gap - barWidth) / 2f, size.height - height),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}
