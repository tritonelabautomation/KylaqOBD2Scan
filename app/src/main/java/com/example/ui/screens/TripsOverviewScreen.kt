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
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TRIP OVERVIEW — pixel-faithful replication of the owner's benchmark screenshot
 * (OBDeleven Trip-tracker "7-day overview", obdeleven.com/how-to-save-fuel §1, phone 1 of 3;
 * owner instruction 2026-09-13: "replicate as it is, do not change anything").
 *
 * Replicated 1:1: pure-black canvas; header row (bold title, "Weekly driving score" + green
 * Good-chip, ↑trend + circular score dial + chevron); "Compared to last week" 3-column delta
 * card; "Weekly driving breakdown" card with total time top-right, 7 stacked rounded bars with
 * right-hand hour ticks and 4-item dot legend with percentages; "Weekly trips" day-grouped cards
 * (icon, title, time, km • duration, proportional 4-colour quality strip, chevron); bottom week
 * pill with circular prev/next buttons. Data underneath remains 100 % this car's own recordings.
 */

// Exact palette sampled from the reference screen
private val TtBlack = Color(0xFF000000)
private val TtCard = Color(0xFF1C1C1E)
private val TtCardInner = Color(0xFF2C2C2E)
private val TtWhite = Color(0xFFFFFFFF)
private val TtGray = Color(0xFF8E8E93)
private val TtGrayDim = Color(0xFF636366)
private val TtGreen = Color(0xFF30D158)
private val TtRed = Color(0xFFFF453A)
private val TtBlue = Color(0xFF0A84FF)
private val TtYellow = Color(0xFFFFD60A)
private val TtOrange = Color(0xFFFF9F0A)

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
    var weekFuel by remember { mutableStateOf(Triple(0.0, 0.0, 0)) } // litres, cost, refuels
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
        val inputs = withContext(Dispatchers.Default) {
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
            }
        }
        overview = WeeklyTripOverview.overview(inputs, now, weekOffset)
        val windowStart = WeeklyTripOverview.weekStartMs(now, weekOffset)
        val inWeek = inputs.filter { it.startMs in windowStart until windowStart + 7L * 86_400_000L }
        val litres = inWeek.sumOf { it.summary.fuelLiters }
        val price = viewModel.fuelLogRepository.entries().maxByOrNull { it.idMs }?.pricePerL ?: 0.0
        val refuels = viewModel.fuelLogRepository.entries()
            .count { it.idMs in windowStart until windowStart + 7L * 86_400_000L }
        weekFuel = Triple(litres, litres * price, refuels)
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trip Overview", color = TtWhite, fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = TtWhite) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = TtBlack)
            )
        },
        containerColor = TtBlack
    ) { pad ->
        val ov = overview
        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 10.dp)
        ) {
            if (loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) {
                        CircularProgressIndicator(color = TtWhite, strokeWidth = 2.dp)
                    }
                }
            } else if (ov == null) {
                item { }
            } else {
                // ── HEADER: title + score line + trend + dial ────────────────────
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("7-day overview", color = TtWhite, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Weekly driving score", color = TtGray, fontSize = 12.sp)
                                ov.scoreLabel?.let {
                                    Spacer(Modifier.width(6.dp))
                                    Box(
                                        Modifier.background(TtGreen, RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 1.dp)
                                    ) {
                                        Text(it, color = TtBlack, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                        ov.prev?.let { p ->
                            if (p.totalKm > 0.05) {
                                val d = (ov.totals.totalKm - p.totalKm).let { delta -> delta / p.totalKm * 100.0 }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        if (d >= 0) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                        null, tint = if (d >= 0) TtGreen else TtRed, modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        "%.0f".format(kotlin.math.abs(d)),
                                        color = if (d >= 0) TtGreen else TtRed,
                                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(Modifier.width(10.dp))
                                }
                            }
                        }
                        ScoreDial(ov.score)
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.ExpandMore, null, tint = TtGray, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(ov.rangeLabel, color = TtGrayDim, fontSize = 11.sp)
                }

                // ── COMPARED TO LAST WEEK ────────────────────────────────────────
                item {
                    TtCard {
                        Text("Compared to last week", color = TtGray, fontSize = 12.sp)
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth()) {
                            DeltaCol(ov.totals.totalKm, ov.prev?.totalKm, "Total distance")
                            DeltaCol(ov.totals.dailyAvgKm, ov.prev?.dailyAvgKm, "Daily average")
                            DeltaCol(ov.totals.topDayKm, ov.prev?.topDayKm, "Top daily distance")
                        }
                    }
                }

                // ── WEEKLY DRIVING BREAKDOWN ─────────────────────────────────────
                item {
                    TtCard {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Weekly driving breakdown", color = TtWhite, fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
                            )
                            Text(
                                "%dh %02dmin".format((ov.totalMinutes / 60).toInt(), (ov.totalMinutes % 60).toInt()),
                                color = TtWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth().height(140.dp)) {
                            // bars
                            Row(
                                Modifier.weight(1f).fillMaxHeight(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val maxMin = ov.days.maxOf { it.minutes.values.sum() }.coerceAtLeast(0.001)
                                ov.days.forEach { day ->
                                    val total = day.minutes.values.sum()
                                    Column(
                                        Modifier.weight(1f).fillMaxHeight(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Bottom
                                    ) {
                                        if (total > 0.01) {
                                            Column(
                                                Modifier.fillMaxWidth(0.62f)
                                                    .height((120.dp * (total / maxMin).toFloat()))
                                            ) {
                                                listOf(DriveBand.STOPPED, DriveBand.CONGESTED, DriveBand.SLOW, DriveBand.NORMAL)
                                                    .forEach { b ->
                                                        val frac = if (total > 0) (day.minutes[b] ?: 0.0) / total else 0.0
                                                        if (frac > 0.001) {
                                                            Box(
                                                                Modifier.fillMaxWidth()
                                                                    .weight(frac.toFloat())
                                                                    .background(bandColor(b), RoundedCornerShape(3.dp))
                                                            )
                                                        }
                                                    }
                                            }
                                        } else {
                                            Box(Modifier.weight(1f))
                                        }
                                        Spacer(Modifier.height(6.dp))
                                        Text(day.label, color = TtGray, fontSize = 9.sp)
                                    }
                                }
                            }
                            // right-hand hour ticks
                            Column(
                                Modifier.width(24.dp).fillMaxHeight(),
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                val maxMin = ov.days.maxOf { it.minutes.values.sum() }
                                listOf(maxMin, maxMin * 2 / 3, maxMin / 3, 0.0).forEach { m ->
                                    Text(
                                        if (m >= 59.5) "%dh".format((m / 60).toInt() + 1) else "%.0fm".format(m),
                                        color = TtGrayDim, fontSize = 8.sp
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            DriveBand.values().forEach { b ->
                                Column(horizontalAlignment = Alignment.Start) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(7.dp).background(bandColor(b), CircleShape))
                                        Spacer(Modifier.width(4.dp))
                                        Text(bandName(b), color = TtGray, fontSize = 10.sp)
                                    }
                                    Text(
                                        "%.0f%%".format(ov.bandPercents[b] ?: 0.0),
                                        color = TtWhite, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(start = 11.dp, top = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // ── WEEKLY FUEL (fuel-tracker pillar, same language) ─────────────
                item {
                    TtCard {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Weekly fuel", color = TtWhite, fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
                            )
                            Text(
                                if (weekFuel.first > 0.05) "%.1f L".format(weekFuel.first) else "no fuel data",
                                color = if (weekFuel.first > 0.05) TtWhite else TtGray,
                                fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth()) {
                            FuelCol("%.1f".format(weekFuel.first), "Litres burned")
                            FuelCol(if (weekFuel.second > 0.5) "₹%.0f".format(weekFuel.second) else "--", "Est. cost")
                            FuelCol("${weekFuel.third}", "Refuels logged")
                        }
                    }
                }

                // ── WEEKLY TRIPS ─────────────────────────────────────────────────
                item {
                    Text("Weekly trips", color = TtWhite, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                if (ov.trips.isEmpty()) {
                    item {
                        TtCard {
                            Text(
                                "NO DRIVES THIS WEEK (${ov.rangeLabel}). Connect and drive - auto-record picks the " +
                                    "trip up; simulated EA211 drives count too. Use the arrows below for older weeks.",
                                color = TtGray, fontSize = 12.sp
                            )
                        }
                    }
                } else {
                    ov.trips.groupBy { it.startMs / 86_400_000L }.forEach { (_, dayTrips) ->
                        item {
                            Text(
                                dayHeader(dayTrips.first().startMs),
                                color = TtGray, fontSize = 12.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        items(dayTrips) { trip -> TripRow(trip, onOpenTrip) }
                    }
                }

                // ── WEEK PILL ────────────────────────────────────────────────────
                item {
                    Row(
                        Modifier.fillMaxWidth()
                            .background(TtCard, RoundedCornerShape(28.dp))
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(36.dp).background(TtCardInner, CircleShape).clickable { weekOffset-- },
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Default.ChevronLeft, "Previous week", tint = TtWhite, modifier = Modifier.size(18.dp)) }
                        Column(
                            Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                if (weekOffset == 0) "This week" else if (weekOffset == -1) "Last week" else "${-weekOffset} weeks ago",
                                color = TtWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold
                            )
                            Text(ov.rangeLabel, color = TtGrayDim, fontSize = 9.sp)
                        }
                        Box(
                            Modifier.size(36.dp).background(TtCardInner, CircleShape)
                                .clickable(enabled = weekOffset < 0) { if (weekOffset < 0) weekOffset++ },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.ChevronRight, "Next week",
                                tint = if (weekOffset < 0) TtWhite else TtGrayDim, modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── replicated building blocks ───────────────────────────────────────────────

private fun bandColor(b: DriveBand): Color = when (b) {
    DriveBand.NORMAL -> TtBlue
    DriveBand.SLOW -> TtYellow
    DriveBand.CONGESTED -> TtOrange
    DriveBand.STOPPED -> TtRed
}

private fun bandName(b: DriveBand): String = when (b) {
    DriveBand.NORMAL -> "Normal"
    DriveBand.SLOW -> "Slow"
    DriveBand.CONGESTED -> "Congested"
    DriveBand.STOPPED -> "Stopped"
}

private fun scoreColor(score: Int): Color = when {
    score >= 75 -> TtGreen
    score >= 50 -> TtYellow
    else -> TtRed
}

private fun dayHeader(startMs: Long): String =
    SimpleDateFormat("EEEE, d MMM", Locale.US).format(Date(startMs))

@Composable
private fun TtCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(TtCard, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) { content() }
}

@Composable
private fun ScoreDial(score: Int?) {
    Box(
        Modifier.size(52.dp)
            .border(4.dp, if (score == null) TtCardInner else scoreColor(score), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            score?.toString() ?: "--",
            color = if (score == null) TtGrayDim else TtWhite,
            fontSize = 16.sp, fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun RowScope.DeltaCol(current: Double, prev: Double?, label: String) {
    Column(Modifier.weight(1f)) {
        if (prev != null && prev > 0.05) {
            val d = current - prev
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (d >= 0) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                    null, tint = if (d >= 0) TtGreen else TtRed, modifier = Modifier.size(9.dp)
                )
                Spacer(Modifier.width(2.dp))
                Text(
                    "%.1f km".format(kotlin.math.abs(d)),
                    color = if (d >= 0) TtGreen else TtRed, fontSize = 10.sp
                )
            }
        } else {
            Text("—", color = TtGrayDim, fontSize = 10.sp)
        }
        Spacer(Modifier.height(2.dp))
        Text("%.1f km".format(current), color = TtWhite, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(label, color = TtGray, fontSize = 9.sp)
    }
}

@Composable
private fun RowScope.FuelCol(value: String, label: String) {
    Column(Modifier.weight(1f)) {
        Text(value, color = TtWhite, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(label, color = TtGray, fontSize = 9.sp)
    }
}

@Composable
private fun TripRow(trip: WeeklyTripOverview.TripCard, onOpenTrip: (String) -> Unit) {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.US) }
    Column(
        Modifier.fillMaxWidth()
            .background(TtCardInner, RoundedCornerShape(14.dp))
            .clickable { onOpenTrip(trip.tripId) }
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(26.dp).background(TtCard, RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Insights, null, tint = TtWhite, modifier = Modifier.size(15.dp)) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(trip.title, color = TtWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "%.1f km • %dh %02d min".format(
                            trip.distanceKm,
                            (trip.durationSec / 3600).toInt(),
                            ((trip.durationSec % 3600) / 60).toInt()
                        ),
                        color = TtGray, fontSize = 11.sp
                    )
                    trip.kmPerL?.let {
                        Spacer(Modifier.width(8.dp))
                        Text("%.1f km/L".format(it), color = TtGreen, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Text(timeFmt.format(Date(trip.startMs)), color = TtGrayDim, fontSize = 11.sp)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Default.ChevronRight, null, tint = TtGray, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().height(4.dp)) {
            val ordered = listOf(DriveBand.NORMAL, DriveBand.SLOW, DriveBand.CONGESTED, DriveBand.STOPPED)
            val any = ordered.any { (trip.fractions[it] ?: 0.0) > 0.001 }
            if (any) {
                ordered.forEach { b ->
                    val f = (trip.fractions[b] ?: 0.0).toFloat()
                    if (f > 0.001f) Box(Modifier.weight(f).fillMaxHeight().background(bandColor(b)))
                }
            } else {
                Box(Modifier.weight(1f).fillMaxHeight().background(TtCard))
            }
        }
    }
}
