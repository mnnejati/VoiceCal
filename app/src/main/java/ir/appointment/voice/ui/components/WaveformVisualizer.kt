package ir.appointment.voice.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt

/**
 * Lightweight live waveform: a fixed number of bars whose heights follow the
 * most recent amplitude readings from [VoiceCaptureEngine]. Cheap by design —
 * a bounded-size list (no growth/allocation churn) and a single Canvas pass of
 * simple rounded rectangles, updated at whatever rate [amplitude] changes
 * (throttled upstream to ~16/sec), so it doesn't add meaningful CPU/GPU load.
 *
 * Colours are deliberately white, not a theme colour: this widget only ever sits on the
 * main screen's fixed indigo→purple gradient, and the theme's purple "primary" colour has
 * almost no contrast against that gradient (≈1.3–1.6:1, i.e. invisible). White gives ≈7–9:1.
 */
@Composable
fun WaveformVisualizer(
    amplitude: Float,
    active: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 26
) {
    val history = remember { mutableStateListOf<Float>().apply { repeat(barCount) { add(MIN_LEVEL) } } }

    LaunchedEffect(amplitude, active) {
        // Normal speech peaks are only ~0.1–0.4 of full scale, which would barely move the
        // bars; a square-root curve spreads those quiet-to-medium levels over most of the
        // bar height (0.1 -> 0.32, 0.4 -> 0.63) so the display visibly follows the voice.
        val level = if (active) sqrt(amplitude.coerceIn(0f, 1f)) else 0f
        if (history.size >= barCount) history.removeAt(0)
        history.add(level.coerceAtLeast(MIN_LEVEL)) // small floor so idle bars stay visible
    }

    val activeColor = Color.White
    val idleColor = Color.White.copy(alpha = 0.35f)

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
                color = if (active) activeColor else idleColor,
                topLeft = Offset(x, centerY - barHeight / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

private const val MIN_LEVEL = 0.04f
