package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.analysis.TripFuelSummary
import com.example.analysis.WeeklyTripOverview
import com.example.analysis.WeeklyTripOverview.DriveBand
import com.example.ui.theme.*
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TRIP OVERVIEW - the owner's 2026-09-13 benchmark: OBDeleven Trip-tracker's
 * "7-day overview" (score dial + trend, vs-last-week delta chips, stacked weekly
 * driving breakdown with legend, per-trip cards with quality gradient strips).
 * Same information architecture, our own dark automotive palette, and every number
 * computed from THIS car's recorded samples (no vendor cloud, no device).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripsOverviewScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onOpenTrip: (String) -> Unit
) {
    val repo = viewModel.recordingManager.tripRepository
    var weekOffset by remember { mutableStateOf(0) }
    var overview by remember { mutableStateOf<WeeklyTripOverview.WeekOverview?>(null) }
    var loading by remember { mutableStateOf(true) }

    // QA/QC 2026-09-13: refresh when a trip is inserted/completed while this screen is open
    // (e.g. a simulated EA211 drive finishing) - signature = trip count + newest start.
    val tripSignature by repo.allTripsFlow
        .map { list -> (list.size to (list.maxOfOrNull { it.startTimestamp } ?: 0L)) }
        .collectAsState(initial = 0 to 0L)

    LaunchedEffect(weekOffset, tripSignature) {
        loading = true
        val now = System.currentTimeMillis()
        val trips = withContext(Dispatchers.IO) { repo.recentTrips(300) }
        overview = withContext(Dispatchers.Default) {
            WeeklyTripOverview.overview(
                trips.filter { it.status != "RECORDING" }.map { t ->
                    val samples = repo.getSamplesForTrip(t.id)
                    WeeklyTripOverview.TripInput(
                        tripId = t.id,
                        title = t.title,
                        startMs = t.startTimestamp,
                        summary = TripFuelSummary.summarize(
                            samples.map { TripFuelSummary.SamplePoint(it.pid, it.timestamp, it.numericValue) }
                        )
                    )
                },
                now,
                weekOffset
            )
        }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trip Overview", color = TextPrimaryDark, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = CyberCyan) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
            )
        },
        containerColor = DarkCanvas
    ) { pad ->
        val ov = overview
        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize().padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            if (loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) {
                        CircularProgressIndicator(color = CyberCyan)
                    }
                }
            } else if (ov == null) {
                item { }
            } else {
                // ── Header: title + score dial + trend ─────────────────────────
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("7-day overview", color = TextPrimaryDark, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Weekly driving score", color = TextSecondaryDark, fontSize = 12.sp)
                                ov.scoreLabel?.let {
                                    Spacer(Modifier.width(6.dp))
                                    Box(
                                        Modifier.background(
                                            scoreColor(ov.score ?: 0).copy(alpha = 0.18f),
                                            RoundedCornerShape(6.dp)
                                        ).padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) { Text(it, color = scoreColor(ov.score ?: 0), fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                                }
                            }
                            Text(ov.rangeLabel, color = TextMutedDark, fontSize = 11.sp)
                        }
                        ov.prev?.let { p ->
                            if (p.totalKm > 0.05) {
                                val delta = (ov.totals.totalKm - p.totalKm) / p.totalKm * 100.0
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        if (delta >= 0) Icons.Default.TrendingUp else Icons.Default.TrendingDown,
                                        null, tint = if (delta >= 0) NeonEmerald else WarningRed,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        "%+.0f%%".format(delta),
                                        color = if (delta >= 0) NeonEmerald else WarningRed,
                                        fontSize = 12.sp, fontWeight = FontWeight.Bold
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                            }
                        }
                        ScoreDial(ov.score)
                    }
                }

                // ── Compared to last week ──────────────────────────────────────
                item {
                    OverviewCard("Compared to last week", null) {
                        Row(Modifier.fillMaxWidth()) {
                            DeltaStat(ov.totals.totalKm, ov.prev?.totalKm, "km", "Total distance")
                            DeltaStat(ov.totals.dailyAvgKm, ov.prev?.dailyAvgKm, "km", "Daily average")
                            DeltaStat(ov.totals.topDayKm, ov.prev?.topDayKm, "km", "Top daily distance")
                        }
                    }
                }

                // ── Weekly driving breakdown ───────────────────────────────────
                item {
                    OverviewCard("Weekly driving breakdown", "%dh %02dmin".format((ov.totalMinutes / 60).toInt(), (ov.totalMinutes % 60).toInt())) {
                        Row(
                            Modifier.fillMaxWidth().height(130.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            ov.days.forEach { day ->
                                val total = day.minutes.values.sum()
                                Column(
                                    Modifier.weight(1f).fillMaxHeight(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Bottom
                                ) {
                                    if (total > 0.01) {
                                        Column(Modifier.fillMaxWidth(0.72f).weight(1f)) {
                                            DriveBand.values().forEach { b ->
                                                val frac = (day.minutes[b] ?: 0.0) / total
                                                if (frac > 0.001) {
                                                    Box(
                                                        Modifier.fillMaxWidth()
                                                            .weight(frac.toFloat())
                                                            .background(bandColor(b), RoundedCornerShape(2.dp))
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        Box(Modifier.weight(1f))
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(day.label, color = TextMutedDark, fontSize = 9.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider(color = DarkBorder, thickness = 0.5.dp)
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            DriveBand.values().forEach { b ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(8.dp).background(bandColor(b), CircleShape))
                                    Spacer(Modifier.width(4.dp))
                                    Text(bandName(b), color = TextSecondaryDark, fontSize = 10.sp)
                                    Spacer(Modifier.width(3.dp))
                                    Text("%.0f%%".format(ov.bandPercents[b] ?: 0.0), color = TextPrimaryDark, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // ── Trip list grouped by day ───────────────────────────────────
                if (ov.trips.isEmpty()) {
                    item {
                        OverviewCard("NO DRIVES THIS WEEK", null) {
                            Text(
                                "Nothing recorded between ${ov.rangeLabel}. Connect the adapter, start the engine and " +
                                    "auto-record picks the drive up - or swipe the week arrows below to browse older weeks.",
                                color = TextSecondaryDark, fontSize = 12.sp
                            )
                        }
                    }
                } else {
                    ov.trips.groupBy { it.dayLabel + it.startMs / 86_400_000L }.forEach { (_, dayTrips) ->
                        item {
                            Text(
                                dayHeader(dayTrips.first().startMs),
                                color = TextSecondaryDark, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        items(dayTrips) { trip ->
                            TripOverviewCard(trip, onOpenTrip)
                        }
                    }
                }

                // ── Week navigation footer ─────────────────────────────────────
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        IconButton(onClick = { weekOffset-- }) {
                            Icon(Icons.Default.ChevronLeft, "Previous week", tint = CyberCyan)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                if (weekOffset == 0) "This week" else if (weekOffset == -1) "Last week" else "${-weekOffset} weeks ago",
                                color = TextPrimaryDark, fontSize = 12.sp, fontWeight = FontWeight.Bold
                            )
                            Text(ov.rangeLabel, color = TextMutedDark, fontSize = 10.sp)
                        }
                        IconButton(onClick = { if (weekOffset < 0) weekOffset++ }) {
                            Icon(
                                Icons.Default.ChevronRight, "Next week",
                                tint = if (weekOffset < 0) CyberCyan else TextMutedDark
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── building blocks ──────────────────────────────────────────────────────────

private fun bandColor(b: DriveBand): Color = when (b) {
    DriveBand.NORMAL -> Color(0xFF4FC3F7)
    DriveBand.SLOW -> Color(0xFFFFD54F)
    DriveBand.CONGESTED -> Color(0xFFFF8A65)
    DriveBand.STOPPED -> Color(0xFFE57373)
}

private fun bandName(b: DriveBand): String = when (b) {
    DriveBand.NORMAL -> "Normal"
    DriveBand.SLOW -> "Slow"
    DriveBand.CONGESTED -> "Congested"
    DriveBand.STOPPED -> "Stopped"
}

private fun scoreColor(score: Int): Color = when {
    score >= 75 -> NeonEmerald
    score >= 50 -> ElectricAmber
    else -> WarningRed
}

private fun dayHeader(startMs: Long): String {
    val fmt = SimpleDateFormat("EEEE, d MMM", Locale.US)
    return fmt.format(Date(startMs))
}

@Composable
private fun ScoreDial(score: Int?) {
    val color = scoreColor(score ?: 0)
    Box(
        Modifier
            .size(72.dp)
            .border(5.dp, if (score == null) DarkBorder else color, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                score?.toString() ?: "--",
                color = if (score == null) TextMutedDark else TextPrimaryDark,
                fontSize = 22.sp, fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun OverviewCard(title: String, trailing: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(DarkSurface, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = TextPrimaryDark, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            trailing?.let { Text(it, color = TextSecondaryDark, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun RowScope.DeltaStat(current: Double, prev: Double?, unit: String, label: String) {
    Column(Modifier.weight(1f)) {
        if (prev != null && prev > 0.05) {
            val d = current - prev
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (d >= 0) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                    null, tint = if (d >= 0) NeonEmerald else WarningRed, modifier = Modifier.size(11.dp)
                )
                Text(
                    "%.1f %s".format(kotlin.math.abs(d), unit),
                    color = if (d >= 0) NeonEmerald else WarningRed, fontSize = 10.sp
                )
            }
        } else {
            Text("—", color = TextMutedDark, fontSize = 10.sp)
        }
        Text("%.1f".format(current), color = TextPrimaryDark, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(label, color = TextMutedDark, fontSize = 9.sp)
    }
}

@Composable
private fun TripOverviewCard(trip: WeeklyTripOverview.TripCard, onOpenTrip: (String) -> Unit) {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.US) }
    Column(
        Modifier.fillMaxWidth()
            .background(DarkSurfaceElevated, RoundedCornerShape(12.dp))
            .clickable { onOpenTrip(trip.tripId) }
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.DirectionsCar, null, tint = CyberCyan, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(trip.title, color = TextPrimaryDark, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(timeFmt.format(Date(trip.startMs)), color = TextMutedDark, fontSize = 11.sp)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.ChevronRight, null, tint = TextMutedDark, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "%.1f km • %dh %02dm".format(
                    trip.distanceKm,
                    (trip.durationSec / 3600).toInt(),
                    ((trip.durationSec % 3600) / 60).toInt()
                ),
                color = TextSecondaryDark, fontSize = 12.sp, modifier = Modifier.weight(1f)
            )
            trip.kmPerL?.let {
                Text("%.1f km/L".format(it), color = NeonEmerald, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
            }
            Box(
                Modifier.background(scoreColor(trip.score).copy(alpha = 0.18f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            ) { Text("${trip.score}", color = scoreColor(trip.score), fontSize = 10.sp, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().height(4.dp)) {
            val ordered = listOf(DriveBand.NORMAL, DriveBand.SLOW, DriveBand.CONGESTED, DriveBand.STOPPED)
            val any = ordered.any { (trip.fractions[it] ?: 0.0) > 0.001 }
            if (any) {
                ordered.forEach { b ->
                    val f = (trip.fractions[b] ?: 0.0).toFloat()
                    if (f > 0.001f) Box(Modifier.weight(f).fillMaxHeight().background(bandColor(b)))
                }
            } else {
                Box(Modifier.weight(1f).fillMaxHeight().background(DarkBorder))
            }
        }
    }
}
