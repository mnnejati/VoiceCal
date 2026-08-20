package ir.appointment.voice.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Lightweight live waveform: a fixed number of bars whose heights follow the
 * most recent amplitude readings from [VoiceCaptureEngine]. Cheap by design —
 * a bounded-size list (no growth/allocation churn) and a single Canvas pass of
 * simple rounded rectangles, updated at whatever rate [amplitude] changes
 * (throttled upstream to ~16/sec), so it doesn't add meaningful CPU/GPU load.
 */
@Composable
fun WaveformVisualizer(
    amplitude: Float,
    active: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 26
) {
    val history = remember { mutableStateListOf<Float>().apply { repeat(barCount) { add(0.04f) } } }

    LaunchedEffect(amplitude, active) {
        val level = if (active) amplitude.coerceIn(0f, 1f) else 0f
        if (history.size >= barCount) history.removeAt(0)
        history.add(level.coerceAtLeast(0.04f)) // tiny floor so idle bars stay visible, not invisible
    }

    val barColor = MaterialTheme.colorScheme.primary
    val idleColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)

    Canvas(modifier = modifier.fillMaxWidth().height(56.dp)) {
        if (history.isEmpty()) return@Canvas
        val slot = size.width / barCount
        val barWidth = slot * 0.55f
        val centerY = size.height / 2f
        val maxBarHeight = size.height * 0.85f

        history.forEachIndexed { index, level ->
            val barHeight = (size.height * 0.12f + level * maxBarHeight).coerceAtMost(size.height)
            val x = index * slot + (slot - barWidth) / 2f
            drawRoundRect(
                color = if (active) barColor else idleColor,
                topLeft = Offset(x, centerY - barHeight / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}
