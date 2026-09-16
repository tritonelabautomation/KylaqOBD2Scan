package com.example.ui.screens

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs

/**
 * Compact sparkline with honest amplitude context.
 *
 * 2026-09-13 (owner: "Trends are not proper"): the previous version drew a bare
 * min–max-normalised polyline — no baseline, no value labels, no points. Two
 * problems made the trend rows lie visually:
 *  - the line always spanned the full height of the box, so a ±2 % wobble across
 *    trips looked like a violent swing;
 *  - nothing on screen said which magnitudes the reader was looking at.
 *
 * Now: 12 % head/foot padding on the value range, a dashed mid baseline for
 * amplitude reference, max/min value labels, point dots for sparse series (a
 * handful of trips), and a subtle gradient fill under the curve. The call API
 * stays drop-in compatible (new parameters have defaults).
 */
@Composable
fun SimpleLineChart(
    data: List<Float>,
    modifier: Modifier = Modifier,
    lineColor: Color = Color.Cyan,
    showLabels: Boolean = true,
    valueFormatter: (Float) -> String = { formatSparkValue(it) }
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        if (data.isEmpty()) return@Canvas

        val maxVal = data.maxOrNull() ?: 0f
        val minVal = data.minOrNull() ?: 0f
        val rawRange = maxVal - minVal
        // Pad the range so a near-flat series is not amplified into a full-height
        // swing and the polyline never touches the box edges.
        val pad = if (rawRange > 1e-4f) rawRange * 0.12f else maxOf(abs(maxVal) * 0.05f, 1f)
        val plotMin = if (minVal >= 0f) (minVal - pad).coerceAtLeast(0f) else minVal - pad
        val plotRange = rawRange + 2f * pad

        val width = size.width
        val height = size.height

        // OWNER READABILITY FIX (2026-09-16 screenshots: end labels printed ON the line
        // and clipped at the right edge): reserve a right gutter sized to the widest
        // value label, plot inside it, and print labels IN the gutter - the line, its
        // dots and the fill can then never collide with their own numbers.
        val textSize = 9.sp.toPx()
        val measurePaint = Paint().apply {
            this.textSize = textSize
            isAntiAlias = true
        }
        val gutter = if (showLabels && width > 60f) {
            val widest = maxOf(
                measurePaint.measureText(valueFormatter(maxVal)),
                measurePaint.measureText(valueFormatter(minVal))
            )
            minOf(widest + 10f, width * 0.35f)
        } else 0f
        val plotWidth = width - gutter

        val stepX = if (data.size > 1) plotWidth / (data.size - 1) else 0f

        val xAt = { i: Int -> if (data.size > 1) i * stepX else plotWidth / 2f }
        val yAt = { v: Float -> height - ((v - plotMin) / plotRange * height) }

        // dashed mid baseline: gives the eye an amplitude reference
        drawLine(
            color = lineColor.copy(alpha = 0.18f),
            start = Offset(0f, height / 2f),
            end = Offset(plotWidth, height / 2f),
            strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f), 0f)
        )

        val path = Path()
        data.forEachIndexed { index, value ->
            val x = xAt(index)
            val y = yAt(value)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        // gradient fill under the curve
        val fill = Path().apply {
            addPath(path)
            lineTo(xAt(data.size - 1), height)
            lineTo(xAt(0), height)
            close()
        }
        drawPath(
            path = fill,
            brush = Brush.verticalGradient(
                colors = listOf(lineColor.copy(alpha = 0.22f), lineColor.copy(alpha = 0.02f))
            )
        )
        drawPath(path = path, color = lineColor, style = Stroke(width = 3f, cap = StrokeCap.Round))

        // point dots: with only a few trips every sample must be visible as a sample
        if (data.size <= 12) {
            data.forEachIndexed { index, value ->
                drawCircle(color = lineColor, radius = 4.5f, center = Offset(xAt(index), yAt(value)))
            }
        }

        if (showLabels && height > 24f && width > 60f) {
            val paint = Paint().apply {
                color = lineColor.copy(alpha = 0.9f).toArgb()
                this.textSize = textSize
                isAntiAlias = true
                textAlign = Paint.Align.LEFT
            }
            // Each label sits at its own value's height inside the gutter; when the
            // two values are too close to stack, pin max top / min bottom instead of
            // overprinting one number on the other.
            var yMax = yAt(maxVal).coerceIn(textSize + 2f, height - 4f)
            var yMin = yAt(minVal).coerceIn(textSize + 2f, height - 4f)
            if (kotlin.math.abs(yMax - yMin) < textSize + 4f) {
                yMax = textSize + 2f
                yMin = height - 4f
            }
            drawContext.canvas.nativeCanvas.apply {
                drawText(valueFormatter(maxVal), plotWidth + 6f, yMax, paint)
                drawText(valueFormatter(minVal), plotWidth + 6f, yMin, paint)
            }
        }
    }
}

private fun formatSparkValue(v: Float): String =
    if (abs(v) >= 100f) String.format(Locale.US, "%.0f", v)
    else String.format(Locale.US, "%.1f", v)
