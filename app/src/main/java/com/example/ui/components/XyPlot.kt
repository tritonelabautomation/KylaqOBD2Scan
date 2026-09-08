package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.TextSecondaryDark

/**
 * One plotted series: name for the legend, colour, and (x, y) samples.
 */
data class XySeries(
    val name: String,
    val color: Color,
    val points: List<Pair<Float, Float>>,
    val dashed: Boolean = false
)

/**
 * Dependency-free multi-series XY plot with grid, axis range labels, legend and an optional
 * vertical marker (used for the efficiency sweet spot). Deliberately tiny: the app must build
 * without adding a charting dependency.
 */
@Composable
fun XyPlot(
    series: List<XySeries>,
    modifier: Modifier = Modifier,
    xLabel: String = "",
    yLabel: String = "",
    markerX: Float? = null,
    markerLabel: String? = null,
    height: Dp = 190.dp
) {
    val allPoints = series.flatMap { it.points }
    if (allPoints.isEmpty()) {
        Text(
            "Not enough data yet — drive a little with the adapter connected.",
            color = TextSecondaryDark,
            fontSize = 12.sp,
            modifier = modifier.padding(vertical = 8.dp)
        )
        return
    }

    val minX = allPoints.minOf { it.first }
    val maxX = allPoints.maxOf { it.first }
    val minY = 0f
    val rawMaxY = allPoints.maxOf { it.second }
    val maxY = if (rawMaxY <= 0f) 1f else rawMaxY * 1.1f
    val spanX = if (maxX - minX < 1e-6f) 1f else maxX - minX
    val spanY = if (maxY - minY < 1e-6f) 1f else maxY - minY

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            series.forEach { s ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(s.color, RoundedCornerShape(2.dp))
                    )
                    Text(s.name, color = TextSecondaryDark, fontSize = 11.sp)
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            // Y-axis tick scale (top = max, bottom = min) aligned to the grid rows.
            Column(
                modifier = Modifier.height(height),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End
            ) {
                for (i in 0..4) {
                    Text(
                        fmt(maxY - (maxY - minY) * i / 4f),
                        color = TextSecondaryDark,
                        fontSize = 9.sp
                    )
                }
            }
            Canvas(modifier = Modifier.weight(1f).height(height)) {
            // grid: drawLine takes strokeWidth/pathEffect arguments, not a Paint-like style
            val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 8f), 0f)
            for (i in 0..4) {
                val y = size.height * i / 4f
                drawLine(
                    color = TextSecondaryDark.copy(alpha = 0.25f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                    pathEffect = dash
                )
                val x = size.width * i / 4f
                drawLine(
                    color = TextSecondaryDark.copy(alpha = 0.15f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1f,
                    pathEffect = dash
                )
            }
            // marker
            markerX?.let { mx ->
                if (mx in minX..maxX) {
                    val x = (mx - minX) / spanX * size.width
                    drawLine(
                        color = Color(0xFFFFB300),
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 3f
                    )
                }
            }
            // series
            series.forEach { s ->
                if (s.points.size < 2) return@forEach
                val path = Path()
                s.points.sortedBy { it.first }.forEachIndexed { index, point ->
                    val x = (point.first - minX) / spanX * size.width
                    val y = size.height - (point.second - minY) / spanY * size.height
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path,
                    color = s.color,
                    style = Stroke(
                        width = 4f,
                        cap = StrokeCap.Round,
                        pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f) else null
                    )
                )
            }
        }
        }
        // X-axis tick scale (5 ticks, min → max) under the plot area.
        Row(modifier = Modifier.fillMaxWidth().padding(start = 30.dp)) {
            for (i in 0..4) {
                Text(
                    fmt(minX + spanX * i / 4f),
                    color = TextSecondaryDark,
                    fontSize = 9.sp,
                    modifier = Modifier.weight(1f),
                    textAlign = when (i) {
                        0 -> TextAlign.Start
                        4 -> TextAlign.End
                        else -> TextAlign.Center
                    }
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${fmt(minX)} – ${fmt(maxX)} $xLabel", color = TextSecondaryDark, fontSize = 10.sp)
            Text(
                (markerLabel ?: "") + "  ${fmt(minY)} – ${fmt(maxY)} $yLabel",
                color = if (markerLabel != null) Color(0xFFFFB300) else TextSecondaryDark,
                fontSize = 10.sp,
                fontWeight = if (markerLabel != null) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

private fun fmt(value: Float): String =
    if (value >= 100f) value.toInt().toString() else String.format("%.1f", value)
