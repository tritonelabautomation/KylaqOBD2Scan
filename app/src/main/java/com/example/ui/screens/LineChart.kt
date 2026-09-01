package com.example.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

@Composable
fun SimpleLineChart(
    data: List<Float>,
    modifier: Modifier = Modifier,
    lineColor: Color = Color.Cyan
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        if (data.isEmpty()) return@Canvas

        val maxVal = data.maxOrNull() ?: 0f
        val minVal = data.minOrNull() ?: 0f
        val range = maxVal - minVal
        
        val width = size.width
        val height = size.height

        val stepX = if (data.size > 1) width / (data.size - 1) else 0f
        
        val path = Path()
        data.forEachIndexed { index, value ->
            val normalizedY = if (range == 0f) height / 2f else height - ((value - minVal) / range * height)
            val x = index * stepX
            if (index == 0) {
                path.moveTo(x, normalizedY)
            } else {
                path.lineTo(x, normalizedY)
            }
        }
        
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 4f, cap = StrokeCap.Round)
        )
    }
}
