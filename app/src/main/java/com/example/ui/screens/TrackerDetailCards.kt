package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.analysis.TripDriveAnalysis
import com.example.analysis.TripFuelSummary
import com.example.analysis.WeeklyTripOverview

/**
 * TRIP DETAIL tracker cards — 100 % replication of the owner's attached reference screen 2
 * (OBDeleven trip detail, 355b6390….webp, 2026-09-13): "Trip summary" 2×3 divided stat grid
 * (Distance / Duration / Avg speed / Max speed / Max altitude / Altitude dif.), "Fuel usage"
 * card (Estimated chip, info, thumbs, Trip cost / Fuel used, Price-per-l + L/100km footer),
 * "Drive analysis" card (traffic strip + per-band minutes legend) and "Driving score" card
 * (tri-colour ring + Good chip + Hard braking / Rapid acceleration / Speed variability
 * gradient sliders with Abrupt→Controlled / Uneven→Steady captions).
 *
 * Altitude (owner 2026-09-15): GPS fixes carry altitude and GpsManager now aggregates a
 * per-trip min/max which RecordingManager persists (trips.maxAltitudeM / minAltitudeM,
 * DB v10). Trips WITH accuracy-gated GPS fixes show real metres; older trips (or recordings
 * without a GPS fix) keep the honest "-- m" blank instead of inventing metres (Batch-17 doctrine).
 */

private val TkCard = Color(0xFF1C1C1E)
private val TkInner = Color(0xFF2C2C2E)
private val TkWhite = Color(0xFFFFFFFF)
private val TkGray = Color(0xFF8E8E93)
private val TkDim = Color(0xFF636366)
private val TkGreen = Color(0xFF30D158)
private val TkBlue = Color(0xFF0A84FF)
private val TkYellow = Color(0xFFFFD60A)
private val TkOrange = Color(0xFFFF9F0A)
private val TkRed = Color(0xFFFF453A)

@Composable
fun TrackerSummaryCards(
    summary: TripFuelSummary.Summary,
    pricePerL: Double,
    speedPoints: List<Pair<Long, Double>>,
    /** GPS altitude extremes persisted per trip (owner 2026-09-15 fix). Null = no accuracy-gated GPS fix with altitude was recorded for this trip → honest blank. */
    maxAltitudeM: Double? = null,
    minAltitudeM: Double? = null
) {
    val analysis = remember(summary, speedPoints) {
        TripDriveAnalysis.analyse(speedPoints, summary.idleSeconds + summary.engineOffSeconds, summary.speedHistogram)
    }
    val score = remember(summary) { WeeklyTripOverview.scoreOf(summary) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ── Trip summary grid ──────────────────────────────────────────────
        TkCard {
            Text("Trip summary", color = TkGray, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            TkRow {
                TkStat(Icons.Default.Route, "%.1f km".format(summary.distanceKm), "Distance", withDivider = true)
                TkStat(Icons.Default.Schedule, fmtDur(summary.durationSeconds), "Duration")
            }
            TkDivider()
            TkRow {
                TkStat(Icons.Default.Speed, "%.0f km/h".format(summary.averageSpeedKmh), "Avg speed", withDivider = true)
                TkStat(Icons.Default.RocketLaunch, "%.0f km/h".format(summary.maxSpeedKmh), "Max speed")
            }
            TkDivider()
            val altDiff = if (maxAltitudeM != null && minAltitudeM != null) maxAltitudeM - minAltitudeM else null
            TkRow {
                TkStat(
                    Icons.Default.Terrain,
                    maxAltitudeM?.let { "%.0f m".format(it) } ?: "-- m",
                    if (maxAltitudeM != null) "Max altitude (GPS)" else "Max altitude *",
                    withDivider = true
                )
                TkStat(
                    Icons.Default.TrendingUp,
                    altDiff?.let { "%.0f m".format(it) } ?: "-- m",
                    if (altDiff != null) "Altitude dif. (GPS)" else "Altitude dif. *"
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (maxAltitudeM != null) {
                    "altitude from accuracy-gated GPS fixes (≤ 40 m) - persisted since 2026-09-15"
                } else {
                    "* no GPS altitude was persisted for this trip (recorded before 2026-09-15, or no accuracy-gated GPS fix) - honest blank, never invented"
                },
                color = TkDim,
                fontSize = 9.sp
            )
        }

        // ── Fuel usage ─────────────────────────────────────────────────────
        TkCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Fuel usage", color = TkWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.background(TkInner, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                    Text("Estimated", color = TkGray, fontSize = 10.sp)
                }
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Default.Info, null, tint = TkGray, modifier = Modifier.size(14.dp))
                Spacer(Modifier.weight(1f))
                Icon(Icons.Default.ThumbDownOffAlt, null, tint = TkGray, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(14.dp))
                Icon(Icons.Default.ThumbUpOffAlt, null, tint = TkGray, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Payments, null, tint = TkGray, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("%.2f".format(summary.fuelLiters * pricePerL), color = TkWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("Trip cost", color = TkGray, fontSize = 10.sp)
                    }
                }
                Box(Modifier.width(1.dp).height(34.dp).background(TkInner))
                Row(Modifier.weight(1f).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocalGasStation, null, tint = TkGray, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("%.1f l".format(summary.fuelLiters), color = TkWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("Fuel used", color = TkGray, fontSize = 10.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(TkInner))
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Price per l  ", color = TkGray, fontSize = 11.sp)
                Text("%.2f".format(pricePerL), color = TkWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.Payments, null, tint = TkGray, modifier = Modifier.size(12.dp))
                Spacer(Modifier.weight(1f))
                Text(
                    if (summary.distanceKm > 0.5 && summary.fuelLiters > 0.01) "%.1f L/100km".format(summary.fuelLiters / summary.distanceKm * 100.0) else "-- L/100km",
                    color = TkWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                )
            }
        }

        // ── Drive analysis ─────────────────────────────────────────────────
        TkCard {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Drive analysis", color = TkWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(3.dp))
                    Text("A breakdown of your trip by traffic", color = TkGray, fontSize = 11.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(fmtDur(summary.durationSeconds), color = TkWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("Trip duration", color = TkGray, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            BandStrip(fractions = analysis.bandMinutes.map { m -> m / analysis.bandMinutes.sum().coerceAtLeast(0.001) })
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val names = listOf("Normal" to TkBlue, "Slow" to TkYellow, "Congested" to TkOrange, "Stopped" to TkRed)
                names.forEachIndexed { i, n ->
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).background(n.second, androidx.compose.foundation.shape.CircleShape))
                            Spacer(Modifier.width(5.dp))
                            Text(n.first, color = TkGray, fontSize = 11.sp)
                        }
                        Text(TripDriveAnalysis.fmtMinutes(analysis.bandMinutes[i]), color = TkWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
        }

        // ── Driving score ──────────────────────────────────────────────────
        TkCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Driving score", color = TkWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.background(TkGreen, RoundedCornerShape(4.dp)).padding(horizontal = 7.dp, vertical = 2.dp)) {
                    Text(WeeklyTripOverview.scoreLabel(score), color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Default.Info, null, tint = TkGray, modifier = Modifier.size(14.dp))
                Spacer(Modifier.weight(1f))
                GradientRing(score = score, size = 62.dp, stroke = 5.dp, numberSp = 18)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "A comprehensive measure of how well you drive, combining three key aspects: safety, comfort & efficiency",
                color = TkGray, fontSize = 11.sp
            )
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(TkInner))
            Spacer(Modifier.height(12.dp))
            SliderRow("Hard braking", analysis.brakingControl, "Abrupt", "Controlled")
            Spacer(Modifier.height(14.dp))
            SliderRow("Rapid acceleration", analysis.accelControl, "Aggressive", "Controlled")
            Spacer(Modifier.height(14.dp))
            SliderRow("Speed variability", analysis.speedSteadiness, "Uneven", "Steady")
        }
    }
}

private fun fmtDur(sec: Long): String {
    val m = (sec / 60).toInt()
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}

@Composable
private fun TkCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(TkCard, RoundedCornerShape(14.dp)).padding(14.dp)) { content() }
}

@Composable
private fun TkRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) { content() }
}

@Composable
private fun TkDivider() { Box(Modifier.fillMaxWidth().height(1.dp).background(TkInner)) }

@Composable
private fun RowScope.TkStat(icon: ImageVector, value: String, label: String, withDivider: Boolean = false) {
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = TkGray, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(value, color = TkWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(label, color = TkGray, fontSize = 10.sp)
        }
    }
    if (withDivider) Box(Modifier.width(1.dp).height(32.dp).background(TkInner))
}

@Composable
private fun SliderRow(label: String, fraction: Double, leftCap: String, rightCap: String) {
    val f = fraction.coerceIn(0.03, 0.97).toFloat()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = TkWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Default.Info, null, tint = TkGray, modifier = Modifier.size(13.dp))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().height(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(f).height(4.dp).background(Brush.horizontalGradient(listOf(TkBlue, TkGreen)), RoundedCornerShape(2.dp)))
            Box(Modifier.size(10.dp).background(TkWhite, androidx.compose.foundation.shape.CircleShape))
            Box(Modifier.weight(1f - f).height(4.dp).background(Brush.horizontalGradient(listOf(TkGreen, TkGreen)), RoundedCornerShape(2.dp)))
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(leftCap, color = TkOrange, fontSize = 10.sp, modifier = Modifier.weight(1f))
            Text(rightCap, color = TkGreen, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
