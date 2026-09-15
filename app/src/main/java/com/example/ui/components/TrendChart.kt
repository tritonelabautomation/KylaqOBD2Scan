package com.example.ui.components

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.analysis.ChartSampling
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.TextSecondaryDark
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Single-series telemetry trend chart (owner 2026-09-16: "graphs are not good on the app
 * see how bad they're").
 *
 * What was wrong with the old canvas and what this fixes:
 *  - RAW 1 Hz samples were connected point-to-point, so a 70-minute city drive drew as
 *    spaghetti. Now the line is the per-time-slice MEAN (bucketized) and the raw min/max of
 *    each slice is drawn as a faint envelope - signal AND volatility, both readable.
 *  - Three floating numbers were the only scale. Now a proper left axis: five grid lines
 *    with labels, unit printed once on the top label, plus a dashed MEAN reference line.
 *  - No time scale at all. Now HH:mm ticks along the bottom (ends plus four inner ticks).
 *  - Fixed 220 dp box left half the screen empty. The chart now fills the height its
 *    parent gives it (the Trends tab weights it), so the plot uses the whole display.
 *  - No way to READ a value. Drag anywhere: a crosshair snaps to the nearest bucket and
 *    shows "HH:mm:ss - value unit" in a bubble; releasing clears it.
 *  - Extremes vanished into the noise. Raw min and max are marked with dots and labels.
 *
 * Every plotted number is derived from the real samples handed in (means of real buckets);
 * nothing is smoothed into existence and gaps are skipped, not interpolated.
 */
@Composable
fun TrendChart(
    points: List<Pair<Long, Double>>,
    unit: String,
    modifier: Modifier = Modifier,
    color: Color = CyberCyan,
    bucketTarget: Int = 180
) {
    if (points.size < 2) {
        Text(
            "Not enough samples to draw a trend for this channel yet.",
            color = TextSecondaryDark,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = modifier.padding(16.dp)
        )
        return
    }

    val buckets = remember(points, bucketTarget) { ChartSampling.bucketize(points, bucketTarget) }
    if (buckets.size < 2) {
        Text(
            "All samples share one timestamp - nothing to plot over time.",
            color = TextSecondaryDark,
            fontSize = 12.sp,
            modifier = modifier.padding(16.dp)
        )
        return
    }

    val rawMin = points.minOf { it.second }
    val rawMax = points.maxOf { it.second }
    val rawMean = points.map { it.second }.average()
    val minPoint = points.minBy { it.second }
    val maxPoint = points.maxBy { it.second }
    val t0 = points.first().first
    val t1 = points.last().first

    var scrubTs by remember { mutableStateOf<Long?>(null) }

    val tickFmt = remember { SimpleDateFormat("HH:mm", Locale.US) }
    val bubbleFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val fmt = remember {
        { v: Double ->
            if (abs(v) >= 100.0) String.format(Locale.US, "%.0f", v)
            else String.format(Locale.US, "%.1f", v)
        }
    }

    Canvas(
        modifier = modifier.pointerInput(buckets) {
            // Same geometry the draw scope uses, so the crosshair lands where the eye is.
            val left = 46.dp.toPx()
            val right = 10.dp.toPx()
            val plotW = (size.width - left - right).coerceAtLeast(1f)
            detectDragGestures(
                onDragEnd = { scrubTs = null },
                onDragCancel = { scrubTs = null }
            ) { change, _ ->
                change.consume()
                val frac = ((change.position.x - left) / plotW).coerceIn(0f, 1f)
                scrubTs = t0 + (frac * (t1 - t0)).toLong()
            }
        }
    ) {
        val left = 46.dp.toPx()
        val right = 10.dp.toPx()
        val top = 10.dp.toPx()
        val bottom = 22.dp.toPx()
        val w = size.width
        val h = size.height
        val plotW = (w - left - right).coerceAtLeast(1f)
        val plotH = (h - top - bottom).coerceAtLeast(1f)

        val spanRaw = rawMax - rawMin
        val pad = if (spanRaw > 0.001) spanRaw * 0.08 else maxOf(abs(rawMax) * 0.05, 0.5)
        val yMin = rawMin - pad
        val ySpan = (spanRaw + 2 * pad).coerceAtLeast(1e-6)

        fun xOf(ts: Long) = left + ((ts - t0).toDouble() / (t1 - t0).coerceAtLeast(1L)) * plotW
        fun yOf(v: Double) = top + (1.0 - (v - yMin) / ySpan) * plotH

        val gridColor = Color(0xFF2A2D3A)
        val labelColor = TextSecondaryDark
        val textSize = 10.sp.toPx()
        val paint = Paint().apply {
            color = labelColor.toArgb()
            this.textSize = textSize
            isAntiAlias = true
        }

        // ── horizontal grid + y labels (unit printed once, on the top label) ──
        for (i in 0..4) {
            val v = yMin + ySpan * (4 - i) / 4.0
            val y = (top + plotH * i / 4f).toFloat()
            drawLine(gridColor, Offset(left, y), Offset(left + plotW, y), strokeWidth = 1f)
            val label = if (i == 0) "${fmt(v)} $unit" else fmt(v)
            drawContext.canvas.nativeCanvas.drawText(
                label,
                2f,
                y + textSize / 2f,
                paint
            )
        }

        // ── vertical time ticks: both ends plus four inner ticks ──
        for (i in 0..5) {
            val ts = t0 + ((t1 - t0) * i / 5.0).toLong()
            val x = xOf(ts).toFloat()
            drawLine(
                gridColor.copy(alpha = 0.6f),
                Offset(x, top),
                Offset(x, top + plotH),
                strokeWidth = 1f
            )
            val label = tickFmt.format(Date(ts))
            val labelW = paint.measureText(label)
            val lx = when (i) {
                0 -> x
                5 -> x - labelW
                else -> x - labelW / 2f
            }
            drawContext.canvas.nativeCanvas.drawText(label, lx, h - 6f, paint)
        }

        // ── volatility envelope: bucket max forward, bucket min back ──
        val envelope = Path()
        buckets.forEachIndexed { i, b ->
            val x = xOf(b.ts).toFloat()
            if (i == 0) envelope.moveTo(x, yOf(b.max).toFloat())
            else envelope.lineTo(x, yOf(b.max).toFloat())
        }
        for (i in buckets.indices.reversed()) {
            envelope.lineTo(xOf(buckets[i].ts).toFloat(), yOf(buckets[i].min).toFloat())
        }
        envelope.close()
        drawPath(envelope, color.copy(alpha = 0.10f))

        // ── signal line (bucket means) + gradient area under it ──
        val line = Path()
        buckets.forEachIndexed { i, b ->
            val x = xOf(b.ts).toFloat()
            val y = yOf(b.avg).toFloat()
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        val area = Path()
        area.addPath(line)
        area.lineTo(xOf(buckets.last().ts).toFloat(), top + plotH)
        area.lineTo(xOf(buckets.first().ts).toFloat(), top + plotH)
        area.close()
        drawPath(
            area,
            Brush.verticalGradient(
                listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0.02f)),
                startY = top,
                endY = top + plotH
            )
        )
        drawPath(line, color, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round))

        if (buckets.size <= 12) {
            buckets.forEach { b ->
                drawCircle(color, radius = 4.dp.toPx(), center = Offset(xOf(b.ts).toFloat(), yOf(b.avg).toFloat()))
            }
        }

        // ── mean reference line ──
        drawLine(
            color = labelColor.copy(alpha = 0.55f),
            start = Offset(left, yOf(rawMean).toFloat()),
            end = Offset(left + plotW, yOf(rawMean).toFloat()),
            strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
        )

        // ── raw extremes: dot + value label ──
        listOf(minPoint to true, maxPoint to false).forEach { (pt, isMin) ->
            val cx = xOf(pt.first).toFloat().coerceIn(left, left + plotW)
            val cy = yOf(pt.second).toFloat().coerceIn(top, top + plotH)
            drawCircle(color = color, radius = 3.5.dp.toPx(), center = Offset(cx, cy))
            val label = fmt(pt.second)
            val lx = (cx + 6f).coerceAtMost(left + plotW - paint.measureText(label) - 2f)
            val ly = if (isMin) (cy + textSize + 2f).coerceAtMost(top + plotH) else (cy - 6f).coerceAtLeast(top + textSize)
            drawContext.canvas.nativeCanvas.drawText(label, lx, ly, paint)
        }

        // ── scrub crosshair + value bubble ──
        scrubTs?.let { ts ->
            val bucket = buckets.minByOrNull { abs(it.ts - ts) } ?: return@let
            val cx = xOf(bucket.ts).toFloat()
            val cy = yOf(bucket.avg).toFloat()
            drawLine(
                color = labelColor.copy(alpha = 0.8f),
                start = Offset(cx, top),
                end = Offset(cx, top + plotH),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f), 0f)
            )
            drawCircle(color = Color.White, radius = 4.dp.toPx(), center = Offset(cx, cy))
            drawCircle(color = color, radius = 2.5.dp.toPx(), center = Offset(cx, cy))

            val bubbleText = "${bubbleFmt.format(Date(bucket.ts))}  ${fmt(bucket.avg)} $unit"
            val tw = paint.measureText(bubbleText)
            val bw = tw + 16f
            val bh = textSize + 12f
            val bx = (cx - bw / 2f).coerceIn(left, left + plotW - bw)
            val by = (cy - bh - 12f).coerceAtLeast(top)
            drawRoundRect(
                color = Color(0xE616222F),
                topLeft = Offset(bx, by),
                size = androidx.compose.ui.geometry.Size(bw, bh),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
            )
            drawContext.canvas.nativeCanvas.drawText(bubbleText, bx + 8f, by + bh - 8f, paint)
        }
    }
}
