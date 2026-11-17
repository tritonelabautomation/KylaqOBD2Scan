package com.example.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.GpsRoutePoint
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.*

private val MapBg = Color(0xFF161A22)
private val RoadColor = Color(0xFF222834)
private val WaterColor = Color(0xFF1B2A3D)
private val RouteTeal = Color(0xFF00E5FF)
private val RouteGlow = Color(0xFF00B0FF).copy(alpha = 0.4f)
private val StartPinColor = Color(0xFF00E5FF)
private val EndFlagColor = Color(0xFF00E5FF)
private val HaltStopColor = Color(0xFFFF453A)

/**
 * Interactive Route Map Component.
 * Features:
 * - Dynamic route polyline from authentic vehicle GPS tracking with start pin, destination flag, and halt markers.
 * - Pan and zoom gestures.
 * - Interactive "Replay Trip" player with speed scrubber and live HUD telemetry.
 * - Direct 1-tap "Open in Google Maps" integration.
 * - Honest empty state when GPS coordinates were unrecorded (no fake/synthetic routes).
 */
@Composable
fun TripMapView(
    tripName: String,
    points: List<GpsRoutePoint>,
    modifier: Modifier = Modifier,
    startLabel: String = "From",
    endLabel: String = "To",
    startTimeStr: String = "02:38 PM",
    endTimeStr: String = "04:14 PM",
    distanceKm: Double = 0.0,
    maxSpeedKmh: Double = 0.0,
    onExportGpx: () -> Unit = {},
    onExportKml: () -> Unit = {}
) {
    val context = LocalContext.current
    val effectivePoints = points

    var isReplaying by remember { mutableStateOf(false) }
    var replayProgress by remember { mutableFloatStateOf(0f) }
    var replaySpeedMultiplier by remember { mutableFloatStateOf(2f) }
    var showSatelliteLayer by remember { mutableStateOf(false) }

    // Gesture Pan & Zoom state
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // Replay timer loop
    LaunchedEffect(isReplaying, replaySpeedMultiplier) {
        if (isReplaying) {
            while (isActive && isReplaying) {
                delay(30)
                replayProgress += 0.003f * replaySpeedMultiplier
                if (replayProgress >= 1f) {
                    replayProgress = 1f
                    isReplaying = false
                }
            }
        }
    }

    val currentPointIndex = remember(replayProgress, effectivePoints) {
        if (effectivePoints.isEmpty()) 0
        else (replayProgress * (effectivePoints.size - 1)).toInt().coerceIn(0, effectivePoints.size - 1)
    }
    val currentReplayPoint = effectivePoints.getOrNull(currentPointIndex)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MapBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (effectivePoints.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MapBg)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOff,
                            contentDescription = "No GPS Route",
                            tint = TextSecondaryDark,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "NO GPS ROUTE LOGGED",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "GPS was inactive or location permission was not granted during this drive.",
                            color = TextSecondaryDark,
                            fontSize = 11.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                // Map Canvas Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                        .background(if (showSatelliteLayer) Color(0xFF0F172A) else MapBg)
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(0.8f, 4f)
                                offset = Offset(offset.x + pan.x, offset.y + pan.y)
                            }
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawMapBackdrop(size, showSatelliteLayer)
                        drawRoutePolyline(
                            points = effectivePoints,
                            canvasSize = size,
                            scale = scale,
                            panOffset = offset,
                            progress = if (isReplaying || replayProgress > 0f) replayProgress else 1f
                        )
                    }

                    // Top Controls Overlay (Layer Toggle + Google Maps Launcher)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.65f),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, RouteTeal.copy(alpha = 0.3f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(NeonEmerald))
                                Text("GPS ROUTE (${effectivePoints.size} PTS)", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            IconButton(
                                onClick = {
                                    scale = 1f
                                    offset = Offset.Zero
                                },
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.65f))
                            ) {
                                Icon(Icons.Default.CropFree, contentDescription = "Reset Zoom", tint = RouteTeal, modifier = Modifier.size(16.dp))
                            }

                            IconButton(
                                onClick = {
                                    val first = effectivePoints.firstOrNull()
                                    val last = effectivePoints.lastOrNull()
                                    if (first != null && last != null) {
                                        val uri = Uri.parse("https://www.google.com/maps/dir/?api=1&origin=${first.latitude},${first.longitude}&destination=${last.latitude},${last.longitude}&travelmode=driving")
                                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                    }
                                },
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.65f))
                            ) {
                                Icon(Icons.Default.Public, contentDescription = "Open in Maps", tint = RouteTeal, modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    // Replay Live Telemetry HUD Callout (appears during replay)
                    if (isReplaying || replayProgress > 0f) {
                        Box(modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                            Surface(
                                color = Color.Black.copy(alpha = 0.85f),
                                shape = RoundedCornerShape(10.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, RouteTeal.copy(alpha = 0.5f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Column {
                                        Text("REPLAY SPEED", color = TextSecondaryDark, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        Text(
                                            text = String.format(Locale.US, "%.0f km/h", currentReplayPoint?.speedKmh ?: 0.0),
                                            color = RouteTeal,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Black,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    currentReplayPoint?.rpm?.let { rpm ->
                                        Column {
                                            Text("RPM", color = TextSecondaryDark, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                            Text(
                                                text = "${rpm.toInt()}",
                                                color = NeonEmerald,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Black,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }
                                    currentReplayPoint?.altitudeM?.let { alt ->
                                        Column {
                                            Text("ALTITUDE", color = TextSecondaryDark, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                            Text(
                                                text = String.format(Locale.US, "%.0f m", alt),
                                                color = ElectricAmber,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Replay Scrubber & Controls Bar
                Surface(
                    color = DarkSurface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledIconButton(
                                    onClick = {
                                        if (replayProgress >= 1f) replayProgress = 0f
                                        isReplaying = !isReplaying
                                    },
                                    modifier = Modifier.size(36.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = RouteTeal)
                                ) {
                                    Icon(
                                        imageVector = if (isReplaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = if (isReplaying) "Pause" else "Replay",
                                        tint = Color.Black
                                    )
                                }
                                Text(
                                    text = if (isReplaying) "Replaying Trip..." else "Replay Trip",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                // Speed multiplier button (1x, 2x, 5x)
                                OutlinedButton(
                                    onClick = {
                                        replaySpeedMultiplier = when (replaySpeedMultiplier) {
                                            1f -> 2f
                                            2f -> 5f
                                            else -> 1f
                                        }
                                    },
                                    modifier = Modifier.height(30.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text("${replaySpeedMultiplier.toInt()}x", fontSize = 11.sp, color = RouteTeal, fontWeight = FontWeight.Bold)
                                }

                                // Export Trip Menu Button
                                Button(
                                    onClick = onExportGpx,
                                    modifier = Modifier.height(30.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = RouteTeal.copy(alpha = 0.2f)),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Icon(Icons.Default.FileDownload, contentDescription = null, tint = RouteTeal, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("GPX / KML", color = RouteTeal, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // Scrubber Slider
                        Slider(
                            value = replayProgress,
                            onValueChange = {
                                replayProgress = it
                                if (it < 1f && !isReplaying) isReplaying = false
                            },
                            modifier = Modifier.fillMaxWidth().height(24.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = RouteTeal,
                                activeTrackColor = RouteTeal,
                                inactiveTrackColor = Color(0xFF2C3444)
                            )
                        )
                    }
                }
            }
        }
    }
}

/**
 * Draws map grid backdrop.
 */
private fun DrawScope.drawMapBackdrop(size: androidx.compose.ui.geometry.Size, isSatellite: Boolean) {
    val w = size.width
    val h = size.height

    // Grid road network
    val roadPaint = Stroke(width = 1.5f, cap = StrokeCap.Round)
    for (i in 1..5) {
        val y = h * (i * 0.18f)
        drawLine(RoadColor.copy(alpha = 0.35f), Offset(0f, y), Offset(w, y), strokeWidth = 1.5f)
    }
    for (i in 1..5) {
        val x = w * (i * 0.18f)
        drawLine(RoadColor.copy(alpha = 0.35f), Offset(x, 0f), Offset(x, h), strokeWidth = 1.5f)
    }
}

/**
 * Draws the recorded GPS route polyline, start/end markers, and replay cursor.
 */
private fun DrawScope.drawRoutePolyline(
    points: List<GpsRoutePoint>,
    canvasSize: androidx.compose.ui.geometry.Size,
    scale: Float,
    panOffset: Offset,
    progress: Float
) {
    if (points.size < 2) return

    val minLat = points.minOf { it.latitude }
    val maxLat = points.maxOf { it.latitude }
    val minLon = points.minOf { it.longitude }
    val maxLon = points.maxOf { it.longitude }

    val latSpan = (maxLat - minLat).coerceAtLeast(0.0005)
    val lonSpan = (maxLon - minLon).coerceAtLeast(0.0005)

    val padding = 36f
    val availW = canvasSize.width - padding * 2
    val availH = canvasSize.height - padding * 2

    fun project(pt: GpsRoutePoint): Offset {
        val normX = ((pt.longitude - minLon) / lonSpan).toFloat()
        val normY = (1f - ((pt.latitude - minLat) / latSpan).toFloat()) // Flip Y for screen coords
        val screenX = padding + normX * availW
        val screenY = padding + normY * availH
        val centerX = canvasSize.width / 2f
        val centerY = canvasSize.height / 2f
        return Offset(
            (screenX - centerX) * scale + centerX + panOffset.x,
            (screenY - centerY) * scale + centerY + panOffset.y
        )
    }

    val screenPoints = points.map { project(it) }

    // Glow background line
    val fullPath = Path().apply {
        moveTo(screenPoints.first().x, screenPoints.first().y)
        for (i in 1 until screenPoints.size) {
            lineTo(screenPoints[i].x, screenPoints[i].y)
        }
    }
    drawPath(fullPath, RouteGlow, style = Stroke(width = 8f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))

    // Active progress route line
    val activeCount = (progress * (screenPoints.size - 1)).toInt().coerceIn(1, screenPoints.size - 1)
    val activePath = Path().apply {
        moveTo(screenPoints.first().x, screenPoints.first().y)
        for (i in 1..activeCount) {
            lineTo(screenPoints[i].x, screenPoints[i].y)
        }
    }
    drawPath(activePath, RouteTeal, style = Stroke(width = 4.5f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))

    // Start Pin (A / 1 - Green/Cyan)
    val startPos = screenPoints.first()
    drawCircle(Color.Black, radius = 10f * scale, center = startPos)
    drawCircle(StartPinColor, radius = 8f * scale, center = startPos)
    drawCircle(Color.White, radius = 3.5f * scale, center = startPos)

    // Halt / Stop markers
    points.forEachIndexed { idx, pt ->
        if (pt.isHalt && idx in 1 until screenPoints.size - 1) {
            val haltPos = screenPoints[idx]
            drawCircle(HaltStopColor, radius = 6f * scale, center = haltPos)
            drawCircle(Color.White, radius = 2.5f * scale, center = haltPos)
        }
    }

    // Destination Flag Pin (B / End Flag)
    val endPos = screenPoints.last()
    drawCircle(Color.Black, radius = 10f * scale, center = endPos)
    drawCircle(EndFlagColor, radius = 8f * scale, center = endPos)
    drawCircle(Color(0xFF30D158), radius = 4f * scale, center = endPos)

    // Vehicle Cursor during Replay
    if (progress in 0f..0.999f && activeCount < screenPoints.size) {
        val carPos = screenPoints[activeCount]
        drawCircle(Color.Black.copy(alpha = 0.5f), radius = 14f * scale, center = carPos)
        drawCircle(NeonEmerald, radius = 9f * scale, center = carPos)
        drawCircle(Color.White, radius = 4f * scale, center = carPos)
    }
}
