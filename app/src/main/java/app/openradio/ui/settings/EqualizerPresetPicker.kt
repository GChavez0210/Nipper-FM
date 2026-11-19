package app.openradio.ui.settings

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.openradio.domain.EqPreset
import app.openradio.ui.theme.neuInset
import kotlin.math.abs

@Composable
internal fun EqualizerPresetPicker(preset: EqPreset, onSelect: (EqPreset) -> Unit) {
    val presets = EqPreset.entries
    val haptics = LocalHapticFeedback.current
    val select = rememberUpdatedState<(Int) -> Unit> { delta ->
        presets.getOrNull(preset.ordinal + delta)?.let { next ->
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onSelect(next)
        }
    }

    Column(
        Modifier.fillMaxWidth().pointerInput(Unit) {
            var dragDistance = 0f
            detectHorizontalDragGestures(
                onDragStart = { dragDistance = 0f },
                onDragEnd = {
                    if (abs(dragDistance) >= 36.dp.toPx()) {
                        select.value(if (dragDistance < 0f) 1 else -1)
                    }
                },
                onHorizontalDrag = { _, distance -> dragDistance += distance },
            )
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Equalizer", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Start))
        EqualizerCurve(preset)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { select.value(-1) }, enabled = preset.ordinal > 0) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous EQ preset")
            }
            Text(preset.label, style = MaterialTheme.typography.labelLarge)
            IconButton(onClick = { select.value(1) }, enabled = preset.ordinal < presets.lastIndex) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next EQ preset")
            }
        }
    }
}

@Composable
private fun EqualizerCurve(preset: EqPreset) {
    val transition = updateTransition(preset, label = "Equalizer curve")
    val gains = preset.bandGainsMb.indices.map { index ->
        transition.animateFloat(label = "EQ band") { it.bandGainsMb[index].toFloat() }
    }
    val accent = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant

    Canvas(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .height(136.dp)
            .neuInset(RoundedCornerShape(16.dp), depth = 3.dp)
            .padding(16.dp)
            .semantics { contentDescription = "EQ curve. Swipe left or right to change preset." },
    ) {
        val center = size.height / 2
        val points = gains.mapIndexed { index, gain ->
            Offset(size.width * index / gains.lastIndex, center - gain.value / 600f * center)
        }
        drawLine(grid, Offset(0f, center), Offset(size.width, center), 1.dp.toPx())
        points.forEach { point ->
            drawLine(grid, Offset(point.x, 0f), Offset(point.x, size.height), 1.dp.toPx())
        }
        val curve = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.zipWithNext { start, end ->
                val middle = (start.x + end.x) / 2
                cubicTo(middle, start.y, middle, end.y, end.x, end.y)
            }
        }
        drawPath(curve, accent, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
        points.forEach { drawCircle(accent, radius = 3.dp.toPx(), center = it) }
    }
}
