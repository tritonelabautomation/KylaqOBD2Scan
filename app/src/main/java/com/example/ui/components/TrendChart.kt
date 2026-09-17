package com.example.ui.components

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.analysis.ChartSampling
import com.example.analysis.SeriesRole
import com.example.analysis.roleOfSeries
import com.example.analysis.ZoomWindow
import com.example.analysis.yDomain
import com.example.analysis.zoomWindow
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.TextSecondaryDark
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * One signal drawn on the trend chart. Owner 2026-09-16 pipeline task 1: "let me add
 * multiple signals the same trend see the behaviour w.r.t other signal" - e.g. Voltage vs
 * Engine Load to watch the AC compressor tug the electrical system, or Speed vs Throttle.
 *
 * Selection order matters: the FIRST line owns the labelled left axis and the full
 * treatment (volatility envelope, area fill, mean line, min/max markers); the SECOND gets
 * a labelled right axis in its own unit; third and further lines are scaled to fit the
 * plot height and are marked "fit" in the legend - their exact values come from the
 * crosshair bubble, never implied to be axis-true.
 */
data class TrendLine(
    val points: List<Pair<Long, Double>>,
    val name: String,
    val unit: String,
    val color: Color,
    /**
     * Integer-valued signal (gear estimate): bucket by MODE, integer axis labels and
     * integer crosshair reads - never "1.5 gear" (owner 2026-09-17).
     */
    val discrete: Boolean = false
)

/**
 * Multi-signal telemetry trend chart (owner 2026-09-16: "graphs are not good on the app";
 * pipeline task 1: overlay several signals on one time axis).
 *
 * Readability rules inherited from the single-series redesign, kept deliberately:
 *  - bucket-mean lines (not raw 1 Hz spaghetti) + min/max envelope for the primary;
 *  - real axes with units (left = primary, right = secondary), HH:mm time ticks;
 *  - drag anywhere for a crosshair; the bubble now lists EVERY overlaid signal's value
 *    with its own unit at that instant - that is the actual "behaviour w.r.t. the other
 *    signal" readout;
 *  - everything drawn is derived from the real samples handed in; gaps are skipped, never
 *    interpolated, and fitted (axis-less) series say so in the legend.
 */
@Composable
fun TrendChart(
    lines: List<TrendLine>,
    modifier: Modifier = Modifier,
    bucketTarget: Int = 180
) {
    // Drop series too thin to draw; keep order (selection order = axis priority).
    val drawable = remember(lines) { lines.filter { it.points.size >= 2 } }
    if (drawable.isEmpty()) {
        Text(
            "Not enough samples to draw a trend for the selected signal(s) yet.",
            color = TextSecondaryDark,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            // OWNER BUG 2026-09-16: the empty state inherited the caller's tall chart
            // modifier and rendered a ~1200px void box. Wrap content instead.
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        )
        return
    }

    val t0 = drawable.minOf { it.points.first().first }
    val t1 = drawable.maxOf { it.points.last().first }
    if (t1 <= t0) {
        Text(
            "All samples share one timestamp - nothing to plot over time.",
            color = TextSecondaryDark,
            fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        )
        return
    }

    var scrubTs by remember { mutableStateOf<Long?>(null) }

    // Pinch-zoom window (owner pipeline task 2), as fractions of the full trip domain.
    // Reset whenever the underlying domain changes (different trip / signal set).
    var view by remember(t0, t1) { mutableStateOf(ZoomWindow.FULL) }
    val fullSpan = (t1 - t0).coerceAtLeast(1L)
    val viewT0 = t0 + (view.startFrac * fullSpan).toLong()
    val viewT1 = (t0 + (view.endFrac * fullSpan).toLong()).coerceAtLeast(viewT0 + 1L)

    // Bucketize the VISIBLE slice only, so zooming in actually reveals detail instead of
    // magnifying coarse full-trip means. Same x = same moment for every series.
    val bucketed = remember(drawable, bucketTarget, viewT0, viewT1) {
        drawable.map { line ->
            val visible = line.points.filter { it.first in viewT0..viewT1 }
            val src = if (visible.size >= 2) visible else line.points
            if (line.discrete) ChartSampling.bucketizeDiscrete(src, bucketTarget)
            else ChartSampling.bucketize(src, bucketTarget)
        }
    }

    Column(modifier = modifier) {
        // Legend: colored dot + name (unit); fitted series are labelled "fit". Hidden for
        // anonymous single-series callers so the old compact look survives.
        if (drawable.any { it.name.isNotBlank() } || view.span < 0.999) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (view.span < 0.999) {
                    val spanMs = viewT1 - viewT0
                    val spanLabel = if (spanMs >= 60_000L) "${spanMs / 60_000L} min" else "${spanMs / 1000L} s"
                    Surface(
                        modifier = Modifier.clickable { view = ZoomWindow.FULL },
                        shape = CircleShape,
                        color = DarkSurfaceElevated,
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
                    ) {
                        Text(
                            "\u27f2 full span (viewing $spanLabel)",
                            color = CyberCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
                drawable.forEachIndexed { i, line ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(line.color, CircleShape)
                        )
                        Text(
                            " ${line.name} (${line.unit}" +
                                (if (roleOfSeries(i) == SeriesRole.FITTED) ", fit)" else ")"),
                            color = TextSecondaryDark,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Adaptive time ticks: HH:mm:ss once the visible window is under 10 minutes.
        val tickFmt = remember(viewT0, viewT1) {
            SimpleDateFormat(if (viewT1 - viewT0 < 600_000L) "HH:mm:ss" else "HH:mm", Locale.US)
        }
        val bubbleFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
        val viewState = rememberUpdatedState(Pair(viewT0, viewT1))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                // Keys must NOT include the zoom state itself (restarting the gesture
                // mid-pinch would stutter), so the handler reads the live window through
                // rememberUpdatedState and writes back to the snapshot-state var.
                .pointerInput(t0, t1, drawable.size) {
                    val left = 46.dp.toPx()
                    val right = if (drawable.size >= 2) 46.dp.toPx() else 10.dp.toPx()
                    val plotW = (size.width - left - right).coerceAtLeast(1f)
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var transforming = false
                        var event = awaitPointerEvent()
                        while (event.changes.any { it.pressed }) {
                            val pressed = event.changes.count { it.pressed }
                            if (pressed >= 2) {
                                // Two fingers: pinch = zoom around the centroid, drag = pan.
                                transforming = true
                                scrubTs = null
                                val zoom = event.calculateZoom().toDouble()
                                val pan = event.calculatePan()
                                val centroid = event.calculateCentroid()
                                val centroidFrac = ((centroid.x - left) / plotW).toDouble().coerceIn(0.0, 1.0)
                                val panFrac = (pan.x / plotW).toDouble()
                                view = zoomWindow(view, centroidFrac, zoom, panFrac)
                                event.changes.forEach { it.consume() }
                            } else if (pressed == 1 && !transforming) {
                                // One finger: scrub crosshair within the VISIBLE window.
                                val change = event.changes.first()
                                val frac = ((change.position.x - left) / plotW).coerceIn(0f, 1f)
                                val (vt0, vt1) = viewState.value
                                scrubTs = vt0 + (frac * (vt1 - vt0)).toLong()
                                change.consume()
                            }
                            event = awaitPointerEvent()
                        }
                        scrubTs = null
                    }
                }
        ) {
            val left = 46.dp.toPx()
            val right = if (drawable.size >= 2) 46.dp.toPx() else 10.dp.toPx()
            val top = 10.dp.toPx()
            val bottom = 22.dp.toPx()
            val w = size.width
            val h = size.height
            val plotW = (w - left - right).coerceAtLeast(1f)
            val plotH = (h - top - bottom).coerceAtLeast(1f)

            // One y-domain per series (own units, own scale).
            val domains = drawable.map { line ->
                if (line.discrete) {
                    // Integer axis: 1..N gears, no 0.7 / 5.3 fractions.
                    val lo = kotlin.math.floor(line.points.minOf { it.second })
                    val hi = kotlin.math.ceil(line.points.maxOf { it.second }).coerceAtLeast(lo + 1.0)
                    lo to (hi - lo)
                } else {
                    yDomain(line.points.minOf { it.second }, line.points.maxOf { it.second })
                }
            }

            fun xOf(ts: Long) = left + ((ts - viewT0).toDouble() / (viewT1 - viewT0)) * plotW
            fun yOf(seriesIdx: Int, v: Double): Float {
                val (yMin, ySpan) = domains[seriesIdx]
                return (top + (1.0 - (v - yMin) / ySpan) * plotH).toFloat()
            }

            val gridColor = Color(0xFF2A2D3A)
            val labelColor = TextSecondaryDark
            val textSize = 10.sp.toPx()
            val textPaint = Paint().apply {
                this.color = labelColor.toArgb()
                this.textSize = textSize
                isAntiAlias = true
            }
            fun paintIn(c: Color) = Paint().apply {
                this.color = c.toArgb()
                this.textSize = textSize
                isAntiAlias = true
            }
            val fmt = { v: Double ->
                if (abs(v) >= 100.0) String.format(Locale.US, "%.0f", v)
                else String.format(Locale.US, "%.1f", v)
            }
            fun fmtFor(line: TrendLine): (Double) -> String =
                if (line.discrete) ({ v -> String.format(Locale.US, "%.0f", v) }) else fmt

            // ── grid + LEFT axis labels (primary series, unit on the top label) ──
            val (pMin, pSpan) = domains[0]
            for (i in 0..4) {
                val v = pMin + pSpan * (4 - i) / 4.0
                val y = top + plotH * i / 4f
                drawLine(gridColor, Offset(left, y), Offset(left + plotW, y), strokeWidth = 1f)
                val lf = fmtFor(drawable[0])
                val label = if (i == 0) "${lf(v)} ${drawable[0].unit}" else lf(v)
                drawContext.canvas.nativeCanvas.drawText(label, 2f, y + textSize / 2f, textPaint)
            }

            // ── RIGHT axis labels for the second series, tinted with its line colour ──
            if (drawable.size >= 2) {
                val (sMin, sSpan) = domains[1]
                val rightPaint = paintIn(drawable[1].color)
                for (i in 0..4) {
                    val v = sMin + sSpan * (4 - i) / 4.0
                    val y = top + plotH * i / 4f
                    val rf = fmtFor(drawable[1])
                    val label = if (i == 0) "${rf(v)} ${drawable[1].unit}" else rf(v)
                    val lw = rightPaint.measureText(label)
                    drawContext.canvas.nativeCanvas.drawText(
                        label,
                        w - lw - 2f,
                        y + textSize / 2f,
                        rightPaint
                    )
                }
            }

            // ── vertical time ticks ──
            for (i in 0..5) {
                val ts = viewT0 + ((viewT1 - viewT0) * i / 5.0).toLong()
                val x = xOf(ts).toFloat()
                drawLine(gridColor.copy(alpha = 0.6f), Offset(x, top), Offset(x, top + plotH), strokeWidth = 1f)
                val label = tickFmt.format(Date(ts))
                val labelW = textPaint.measureText(label)
                val lx = when (i) {
                    0 -> x
                    5 -> x - labelW
                    else -> x - labelW / 2f
                }
                drawContext.canvas.nativeCanvas.drawText(label, lx, h - 6f, textPaint)
            }

            // ── primary series: envelope, area, thick line ──
            val pBuckets = bucketed[0]
            val pColor = drawable[0].color
            val envelope = Path()
            pBuckets.forEachIndexed { i, b ->
                val x = xOf(b.ts).toFloat()
                val y = yOf(0, b.max)
                if (i == 0) envelope.moveTo(x, y) else envelope.lineTo(x, y)
            }
            for (i in pBuckets.indices.reversed()) {
                envelope.lineTo(xOf(pBuckets[i].ts).toFloat(), yOf(0, pBuckets[i].min))
            }
            envelope.close()
            drawPath(envelope, pColor.copy(alpha = 0.10f))

            fun linePath(buckets: List<ChartSampling.Bucket>, seriesIdx: Int): Path {
                val path = Path()
                // Owner 2026-09-17 ("Does it makes sense for you?"): a gear HOLDs its
                // value between observations - linear interpolation between sparse
                // samples drew 1->2->1 hunts as triangular spikes. Discrete series use
                // zero-order hold: horizontal at the last known gear until the next
                // observation, then a vertical shift edge = a real square wave.
                val disc = drawable[seriesIdx].discrete
                var prevY = 0f
                buckets.forEachIndexed { i, b ->
                    val x = xOf(b.ts).toFloat()
                    val y = yOf(seriesIdx, b.avg)
                    when {
                        i == 0 -> path.moveTo(x, y)
                        disc -> { path.lineTo(x, prevY); path.lineTo(x, y) }
                        else -> path.lineTo(x, y)
                    }
                    prevY = y
                }
                return path
            }

            val primaryLine = linePath(pBuckets, 0)
            val area = Path()
            area.addPath(primaryLine)
            area.lineTo(xOf(pBuckets.last().ts).toFloat(), top + plotH)
            area.lineTo(xOf(pBuckets.first().ts).toFloat(), top + plotH)
            area.close()
            drawPath(
                area,
                Brush.verticalGradient(
                    listOf(pColor.copy(alpha = 0.28f), pColor.copy(alpha = 0.02f)),
                    startY = top,
                    endY = top + plotH
                )
            )
            drawPath(primaryLine, pColor, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round))

            // ── overlaid series: clean lines, no fill (keeps the chart readable) ──
            for (i in 1 until drawable.size) {
                drawPath(
                    linePath(bucketed[i], i),
                    drawable[i].color,
                    style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // ── primary mean reference + raw extremes ──
            val rawMean = drawable[0].points.map { it.second }.average()
            drawLine(
                color = labelColor.copy(alpha = 0.55f),
                start = Offset(left, yOf(0, rawMean)),
                end = Offset(left + plotW, yOf(0, rawMean)),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
            )
            val minPoint = drawable[0].points.minBy { it.second }
            val maxPoint = drawable[0].points.maxBy { it.second }
            listOf(minPoint to true, maxPoint to false).forEach { (pt, isMin) ->
                val cx = xOf(pt.first).toFloat().coerceIn(left, left + plotW)
                val cy = yOf(0, pt.second).coerceIn(top, top + plotH)
                drawCircle(color = pColor, radius = 3.5.dp.toPx(), center = Offset(cx, cy))
                val label = fmt(pt.second)
                val lx = (cx + 6f).coerceAtMost(left + plotW - textPaint.measureText(label) - 2f)
                val ly = if (isMin) (cy + textSize + 2f).coerceAtMost(top + plotH) else (cy - 6f).coerceAtLeast(top + textSize)
                drawContext.canvas.nativeCanvas.drawText(label, lx, ly, textPaint)
            }

            // ── scrub crosshair + MULTI-signal bubble ──
            scrubTs?.let { ts ->
                val anchor = pBuckets.minByOrNull { abs(it.ts - ts) } ?: return@let
                val cx = xOf(anchor.ts).toFloat()
                drawLine(
                    color = labelColor.copy(alpha = 0.8f),
                    start = Offset(cx, top),
                    end = Offset(cx, top + plotH),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f), 0f)
                )
                // One row per series at its own nearest bucket.
                val rows = drawable.mapIndexedNotNull { i, line ->
                    val b = bucketed[i].minByOrNull { abs(it.ts - ts) } ?: return@mapIndexedNotNull null
                    Triple(line, b, yOf(i, b.avg))
                }
                rows.forEach { (line, b, _) ->
                    drawCircle(color = Color.White, radius = 3.5.dp.toPx(), center = Offset(cx, yOf(drawable.indexOf(line), b.avg)))
                    drawCircle(color = line.color, radius = 2.dp.toPx(), center = Offset(cx, yOf(drawable.indexOf(line), b.avg)))
                }

                val rowH = textSize + 4f
                val headerH = textSize + 8f
                var bw = textPaint.measureText(bubbleFmt.format(Date(anchor.ts)))
                rows.forEach { (line, b, _) ->
                    val lf2 = fmtFor(line)
                    val label = if (line.name.isBlank()) "${lf2(b.avg)} ${line.unit}"
                    else "${line.name}  ${lf2(b.avg)} ${line.unit}"
                    bw = maxOf(bw, textPaint.measureText(label) + 14f)
                }
                bw += 16f
                val bh = headerH + rows.size * rowH + 8f
                val anchorY = rows.firstOrNull()?.third ?: (top + plotH / 2f)
                val bx = (cx - bw / 2f).coerceIn(left, left + plotW - bw)
                val by = (anchorY - bh - 12f).coerceAtLeast(top)
                drawRoundRect(
                    color = Color(0xE616222F),
                    topLeft = Offset(bx, by),
                    size = androidx.compose.ui.geometry.Size(bw, bh),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
                )
                drawContext.canvas.nativeCanvas.drawText(
                    bubbleFmt.format(Date(anchor.ts)),
                    bx + 8f,
                    by + headerH - 4f,
                    textPaint
                )
                rows.forEachIndexed { ri, (line, b, _) ->
                    val rowPaint = paintIn(line.color)
                    val ry = by + headerH + ri * rowH + rowH - 6f
                    drawRect(
                        color = line.color,
                        topLeft = Offset(bx + 8f, ry - textSize + 2f),
                        size = androidx.compose.ui.geometry.Size(6f, 6f)
                    )
                    val lf2 = fmtFor(line)
                    val label = if (line.name.isBlank()) "${lf2(b.avg)} ${line.unit}"
                    else "${line.name}  ${lf2(b.avg)} ${line.unit}"
                    drawContext.canvas.nativeCanvas.drawText(label, bx + 18f, ry, rowPaint)
                }
            }
        }
    }
}

/** Back-compat single-series entry (anonymous legend, old compact behaviour). */
@Composable
fun TrendChart(
    points: List<Pair<Long, Double>>,
    unit: String,
    modifier: Modifier = Modifier,
    color: Color = CyberCyan,
    bucketTarget: Int = 180
) {
    TrendChart(
        lines = listOf(TrendLine(points = points, name = "", unit = unit, color = color)),
        modifier = modifier,
        bucketTarget = bucketTarget
    )
}
