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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
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
 * DRIVING HISTORY — 100 % replication of the owner's attached reference screen 1
 * (OBDeleven Trip tracker "Driving history / 7-day overview", attached 2026-09-13 as
 * 4ce2480c….webp). Element-for-element: black canvas; "Driving history" bar with gear;
 * header ("7-day overview", "Weekly driving score" + green Good chip, tri-colour gradient
 * score ring + chevron); "Driving stats" 3-column divided card; "Weekly fuel usage" card
 * (Estimated chip, info icon, thumbs, Overall costs / Fuel used columns, Price-per-l +
 * L/100km footer); "Weekly driving breakdown" card (total time, 7 stacked bars, right hour
 * ticks, proportional gradient strip, legend with percentages); "Weekly trips" day-grouped
 * cards (gradient score-ring avatar, title, km • min, cost row, time, blue chevron);
 * bottom week bar (‹ circle, range label, » jump-now, › circle).
 * All values remain this car's own recordings (real or simulated EA211).
 */

private val TtBlack = Color(0xFF000000)
private val TtCard = Color(0xFF1C1C1E)
private val TtInner = Color(0xFF2C2C2E)
private val TtWhite = Color(0xFFFFFFFF)
private val TtGray = Color(0xFF8E8E93)
private val TtDim = Color(0xFF636366)
private val TtGreen = Color(0xFF30D158)
private val TtRed = Color(0xFFFF453A)
private val TtBlue = Color(0xFF0A84FF)
private val TtYellow = Color(0xFFFFD60A)
private val TtOrange = Color(0xFFFF9F0A)
private val TtLink = Color(0xFF409CFF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripsOverviewScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onOpenTrip: (String) -> Unit,
    onOpenSettings: () -> Unit = {}
) {
    val repo = viewModel.recordingManager.tripRepository
    var weekOffset by remember { mutableStateOf(0) }
    var overview by remember { mutableStateOf<WeeklyTripOverview.WeekOverview?>(null) }
    var weekFuel by remember { mutableStateOf(Triple(0.0, 0.0, 0.0)) } // litres, cost, price
    var loading by remember { mutableStateOf(true) }

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
                    tripId = t.id, title = t.title, startMs = t.startTimestamp,
                    summary = TripFuelSummary.summarize(
                        samples.map { TripFuelSummary.SamplePoint(it.pid, it.timestamp, it.numericValue) }
                    )
                )
            }
        }
        overview = WeeklyTripOverview.overview(inputs, now, weekOffset)
        val ws = WeeklyTripOverview.weekStartMs(now, weekOffset)
        val inWeek = inputs.filter { it.startMs in ws until ws + 7L * 86_400_000L }
        val litres = inWeek.sumOf { it.summary.fuelLiters }
        val price = viewModel.fuelLogRepository.entries().maxByOrNull { it.idMs }?.pricePerL ?: 0.0
        weekFuel = Triple(litres, litres * price, price)
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Driving history", color = TtWhite, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = TtWhite) } },
                actions = { IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Settings", tint = TtWhite) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = TtBlack)
            )
        },
        containerColor = TtBlack
    ) { pad ->
        val ov = overview
        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            if (loading) {
                item { Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) { CircularProgressIndicator(color = TtWhite, strokeWidth = 2.dp) } }
            } else if (ov == null) {
                item { }
            } else {
                // ── HEADER ─────────────────────────────────────────────────────
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("7-day overview", color = TtWhite, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Weekly driving score", color = TtGray, fontSize = 12.sp)
                                ov.scoreLabel?.let {
                                    Spacer(Modifier.width(8.dp))
                                    Box(Modifier.background(TtGreen, RoundedCornerShape(4.dp)).padding(horizontal = 7.dp, vertical = 2.dp)) {
                                        Text(it, color = TtBlack, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                        GradientRing(score = ov.score, size = 56.dp, stroke = 5.dp, numberSp = 18)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.ExpandMore, null, tint = TtGray, modifier = Modifier.size(18.dp))
                    }
                }

                // ── DRIVING STATS ──────────────────────────────────────────────
                item {
                    TtCard {
                        Text("Driving stats", color = TtGray, fontSize = 12.sp)
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth()) {
                            StatCol("%.1f".format(ov.totals.totalKm), "km", "Total distance", Modifier.weight(1f))
                            Box(Modifier.width(1.dp).height(34.dp).background(TtInner))
                            StatCol("%.1f".format(ov.totals.dailyAvgKm), "km", "Daily average", Modifier.weight(1f))
                            Box(Modifier.width(1.dp).height(34.dp).background(TtInner))
                            StatCol("%.1f".format(ov.totals.topDayKm), "km", "Top daily distance", Modifier.weight(1f))
                        }
                    }
                }

                // ── WEEKLY FUEL USAGE ──────────────────────────────────────────
                item {
                    TtCard {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Weekly fuel usage", color = TtWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.background(TtInner, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                Text("Estimated", color = TtGray, fontSize = 10.sp)
                            }
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Default.Info, null, tint = TtGray, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.weight(1f))
                            Icon(Icons.Default.ThumbDownOffAlt, null, tint = TtGray, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(14.dp))
                            Icon(Icons.Default.ThumbUpOffAlt, null, tint = TtGray, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Payments, null, tint = TtGray, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text("%.2f".format(weekFuel.second), color = TtWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                    Text("Overall costs", color = TtGray, fontSize = 10.sp)
                                }
                            }
                            Box(Modifier.width(1.dp).height(34.dp).background(TtInner))
                            Row(Modifier.weight(1f).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.LocalGasStation, null, tint = TtGray, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text("%.1f l".format(weekFuel.first), color = TtWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                    Text("Fuel used", color = TtGray, fontSize = 10.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = TtInner, thickness = 1.dp)
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Price per l  ", color = TtGray, fontSize = 11.sp)
                            Text("%.2f".format(weekFuel.third), color = TtWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.Payments, null, tint = TtGray, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.weight(1f))
                            val l100 = if (weekFuel.first > 0.05 && ov.totals.totalKm > 0.5) weekFuel.first / ov.totals.totalKm * 100.0 else null
                            Text(
                                l100?.let { "%.1f L/100km".format(it) } ?: "-- L/100km",
                                color = TtWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // ── WEEKLY DRIVING BREAKDOWN ───────────────────────────────────
                item {
                    TtCard {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Weekly driving breakdown", color = TtWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text("%dh %02dmin".format((ov.totalMinutes / 60).toInt(), (ov.totalMinutes % 60).toInt()), color = TtWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(14.dp))
                        val maxMin = ov.days.maxOf { it.minutes.values.sum() }
                        val tickTop = ((maxMin / 60.0).coerceAtLeast(1.0)).let { h -> if (h <= 5) 5.0 else kotlin.math.ceil(h) }
                        Row(Modifier.fillMaxWidth().height(150.dp)) {
                            Row(Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ov.days.forEach { day ->
                                    val total = day.minutes.values.sum()
                                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                                        if (total > 0.01) {
                                            Column(Modifier.fillMaxWidth(0.62f).height((132.dp * (total / (tickTop * 60.0)).toFloat()).coerceAtLeast(2.dp))) {
                                                listOf(DriveBand.STOPPED, DriveBand.CONGESTED, DriveBand.SLOW, DriveBand.NORMAL).forEach { b ->
                                                    val frac = if (total > 0) (day.minutes[b] ?: 0.0) / total else 0.0
                                                    if (frac > 0.001) Box(Modifier.fillMaxWidth().weight(frac.toFloat()).background(bandColor(b), RoundedCornerShape(3.dp)))
                                                }
                                            }
                                        } else Box(Modifier.weight(1f))
                                        Spacer(Modifier.height(6.dp))
                                        Text(day.label, color = TtGray, fontSize = 9.sp)
                                    }
                                }
                            }
                            Column(Modifier.width(22.dp).fillMaxHeight().padding(bottom = 16.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.SpaceBetween) {
                                for (t in tickTop.toInt() downTo 0) Text("${t}h", color = TtDim, fontSize = 8.sp)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        BandStrip(fractions = DriveBand.values().map { (ov.bandPercents[it] ?: 0.0) / 100.0 })
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            DriveBand.values().forEach { b ->
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(7.dp).background(bandColor(b), CircleShape))
                                        Spacer(Modifier.width(5.dp))
                                        Text(bandName(b), color = TtGray, fontSize = 11.sp)
                                    }
                                    Text("%.0f%%".format(ov.bandPercents[b] ?: 0.0), color = TtWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp))
                                }
                            }
                        }
                    }
                }

                // ── WEEKLY TRIPS ───────────────────────────────────────────────
                item { Text("Weekly trips", color = TtWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                if (ov.trips.isEmpty()) {
                    item {
                        TtCard {
                            Text("NO DRIVES THIS WEEK (${ov.rangeLabel}). Drive with the app open - or start a simulated EA211 session - and the trip lands here automatically. ‹ › browse older weeks.", color = TtGray, fontSize = 12.sp)
                        }
                    }
                } else {
                    ov.trips.groupBy { it.startMs / 86_400_000L }.forEach { (_, dayTrips) ->
                        item { Text(dayHeader(dayTrips.first().startMs), color = TtGray, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp)) }
                        items(dayTrips) { trip -> TripRow(trip, weekFuel.third, onOpenTrip) }
                    }
                }

                // ── WEEK BAR ───────────────────────────────────────────────────
                item {
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).background(TtInner, CircleShape).clickable { weekOffset-- }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.ChevronLeft, "Previous week", tint = TtWhite, modifier = Modifier.size(20.dp))
                        }
                        Text(ov.rangeLabel, color = TtWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.TextAlign.Center)
                        Icon(Icons.Default.KeyboardDoubleArrowRight, "Jump to this week", tint = TtWhite, modifier = Modifier.size(20.dp).clickable { weekOffset = 0 })
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.size(40.dp).background(TtInner, CircleShape).clickable(enabled = weekOffset < 0) { if (weekOffset < 0) weekOffset++ }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.ChevronRight, "Next week", tint = if (weekOffset < 0) TtWhite else TtDim, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

// ── replicated atoms ─────────────────────────────────────────────────────────

private fun bandColor(b: DriveBand): Color = when (b) {
    DriveBand.NORMAL -> TtBlue; DriveBand.SLOW -> TtYellow; DriveBand.CONGESTED -> TtOrange; DriveBand.STOPPED -> TtRed
}

private fun bandName(b: DriveBand): String = when (b) {
    DriveBand.NORMAL -> "Normal"; DriveBand.SLOW -> "Slow"; DriveBand.CONGESTED -> "Congested"; DriveBand.STOPPED -> "Stopped"
}

private fun dayHeader(startMs: Long): String = SimpleDateFormat("EEEE, MMM d", Locale.US).format(Date(startMs))

/** Tri-colour gradient score ring exactly like the reference (green→yellow→blue arcs). */
@Composable
fun GradientRing(score: Int?, size: Dp, stroke: Dp, numberSp: Int) {
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val sw = stroke.toPx()
            val rect = androidx.compose.ui.geometry.Rect(sw / 2, sw / 2, this.size.width - sw / 2, this.size.height - sw / 2)
            val segs = listOf(TtGreen to 150f, TtYellow to 110f, TtBlue to 100f)
            var start = -90f
            segs.forEach { (c, sweep) ->
                drawArc(c, start, sweep, useCenter = false, style = Stroke(width = sw, cap = StrokeCap.Round), topLeft = androidx.compose.ui.geometry.Offset(sw / 2, sw / 2), size = Size(rect.width, rect.height))
                start += sweep
            }
        }
        Text(score?.toString() ?: "--", color = if (score == null) TtDim else TtWhite, fontSize = numberSp.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TtCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(TtCard, RoundedCornerShape(14.dp)).padding(14.dp)) { content() }
}

@Composable
private fun StatCol(value: String, unit: String, label: String, mod: Modifier) {
    Column(mod.padding(horizontal = 8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = TtWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(3.dp))
            Text(unit, color = TtGray, fontSize = 10.sp, modifier = Modifier.padding(bottom = 2.dp))
        }
        Spacer(Modifier.height(3.dp))
        Text(label, color = TtGray, fontSize = 10.sp)
    }
}

@Composable
fun BandStrip(fractions: List<Double>) {
    Row(Modifier.fillMaxWidth().height(4.dp)) {
        val colors = listOf(TtBlue, TtYellow, TtOrange, TtRed)
        val any = fractions.any { it > 0.001 }
        if (any) {
            fractions.forEachIndexed { i, f -> if (f > 0.001) Box(Modifier.weight(f.toFloat()).fillMaxHeight().background(colors[i])) }
        } else Box(Modifier.weight(1f).fillMaxHeight().background(TtInner)
        )
    }
}

@Composable
private fun TripRow(trip: WeeklyTripOverview.TripCard, price: Double, onOpenTrip: (String) -> Unit) {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.US) }
    Column(Modifier.fillMaxWidth().background(TtInner, RoundedCornerShape(14.dp)).clickable { onOpenTrip(trip.tripId) }.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GradientRing(score = trip.score, size = 42.dp, stroke = 3.5.dp, numberSp = 13)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(trip.title, color = TtWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text("%.1f km • %d min".format(trip.distanceKm, (trip.durationSec / 60).toInt()), color = TtGray, fontSize = 11.sp)
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("%.2f".format((trip.kmPerL ?: 0.0).let { kmL -> if (kmL > 0 && price > 0) trip.distanceKm / kmL * price else 0.0 }), color = TtGray, fontSize = 11.sp)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.Payments, null, tint = TtGray, modifier = Modifier.size(12.dp))
                }
            }
            Text(timeFmt.format(Date(trip.startMs)), color = TtGray, fontSize = 11.sp)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ChevronRight, null, tint = TtLink, modifier = Modifier.size(18.dp))
        }
    }
}
