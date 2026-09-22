package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import android.content.Context
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.data.db.entities.AiAnalysisEntity
import com.example.data.db.entities.RawLogEntity
import com.example.data.db.entities.TelemetrySampleEntity
import com.example.data.db.entities.instantMs
import com.example.data.db.entities.TripEntity
import com.example.ui.components.TrendChart
import com.example.ui.components.TrendLine
import com.example.ui.theme.*
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

enum class TripDetailTab {
    OVERVIEW,
    TRENDS,
    AI_DOCTOR,
    RAW_LOGS,
    EXPORT
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailScreen(
    tripId: String,
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val tripRepo = viewModel.recordingManager.tripRepository

    var trip by remember { mutableStateOf<TripEntity?>(null) }
    var samples by remember { mutableStateOf<List<TelemetrySampleEntity>>(emptyList()) }
    var rawLogs by remember { mutableStateOf<List<RawLogEntity>>(emptyList()) }
    var aiAnalysis by remember { mutableStateOf<AiAnalysisEntity?>(null) }
    var isAnalyzing by remember { mutableStateOf(false) }

    var selectedTab by remember { mutableStateOf(TripDetailTab.OVERVIEW) }
    var rawFilter by remember { mutableStateOf("ALL") }
    // Pipeline task 1 (owner 2026-09-16): multi-signal overlay. TAP ORDER matters -
    // first picked owns the left axis, second the right axis, 3rd/4th are scaled to fit.
    var selectedTrendPids by remember { mutableStateOf(listOf("010C")) }

    // Owner field report 2026-09-18 ("Altitude"): WHY a blank altitude column, from the permission
    // policy rather than from a guess. Collected here and passed down - the sub-views below do not
    // hold the ViewModel, and a `val` declared in one @Composable is invisible in another.
    val bgLocationState by viewModel.backgroundLocationState.collectAsState()
    val altitudeBlankReason =
        com.example.service.BackgroundLocationPolicy.altitudeBlankReason(
            bgLocationState, trip?.startTimestamp
        )

    // "Log fuel" for this trip: fuel rate integrated over the stored samples.
    val fuelSummary = remember(samples) {
        com.example.analysis.TripFuelSummary.summarize(
            samples.map { com.example.analysis.TripFuelSummary.SamplePoint(it.pid, it.instantMs, it.numericValue) }
        )
    }

    // OWNER FIX (2026-09-16 screenshots): opening a trip flashed the definitive
    // "No stored telemetry samples - nothing to integrate" zero-card for the whole
    // DB-load window, then swapped to real data. Until the load finishes the screen
    // now says LOADING, so an empty state always means a genuinely empty trip.
    var samplesLoaded by remember(tripId) { mutableStateOf(false) }

    // Car-pool ledger for this trip (owner 2026-09-19): riders, what they paid, and the trip's
    // effective cost = pump fuel cost minus earnings. One entry per trip, edited in place.
    val carpoolTick by viewModel.carpoolRepository.changeTick.collectAsState()
    val carpoolEntry = remember(tripId, carpoolTick) {
        viewModel.carpoolRepository.forTrip(tripId).firstOrNull()
    }
    var showCarpool by remember { mutableStateOf(false) }
    if (showCarpool) {
        CarpoolDialog(
            existing = carpoolEntry,
            defaultDistanceKm = fuelSummary.distanceKm,
            defaultWhenMs = trip?.startTimestamp ?: System.currentTimeMillis(),
            onDismiss = { showCarpool = false },
            onSave = { d, riders, whenMs ->
                coroutineScope.launch {
                    viewModel.saveCarpool(
                        com.example.data.CarpoolCodec.CarpoolEntry(
                            idMs = carpoolEntry?.idMs ?: whenMs,
                            tripId = tripId,
                            dateUtc = com.example.data.RecordTime.stamp(whenMs),
                            distanceKm = d,
                            riders = riders
                        )
                    )
                    showCarpool = false
                }
            }
        )
    }
    LaunchedEffect(tripId) {
        samplesLoaded = false
        trip = tripRepo.getTripById(tripId)
        samples = tripRepo.getSamplesForTrip(tripId)
        rawLogs = tripRepo.getRawLogsForTrip(tripId)
        aiAnalysis = tripRepo.getAnalysisForTrip(tripId)
        samplesLoaded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(trip?.title ?: "Trip Details", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                        Text(
                            text = "Session: $tripId • ${trip?.vehicleName ?: ""}",
                            color = TextSecondaryDark,
                            fontSize = 13.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                isAnalyzing = true
                                try {
                                    tripRepo.runAiCarDoctorAnalysis(tripId)
                                    trip = tripRepo.getTripById(tripId)
                                    aiAnalysis = tripRepo.getAnalysisForTrip(tripId)
                                    Toast.makeText(context, "AI Car Doctor review updated", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Analysis error: ${e.message}", Toast.LENGTH_SHORT).show()
                                } finally {
                                    isAnalyzing = false
                                }
                            }
                        }
                    ) {
                        if (isAnalyzing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = CyberCyan)
                        } else {
                            Icon(Icons.Default.AutoAwesome, contentDescription = "Run AI Doctor", tint = CyberCyan)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkCanvas)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkCanvas)
                .padding(padding)
        ) {
            // Tab Strip
            ScrollableTabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = DarkSurface,
                contentColor = CyberCyan,
                edgePadding = 12.dp
            ) {
                Tab(
                    selected = selectedTab == TripDetailTab.OVERVIEW,
                    onClick = { selectedTab = TripDetailTab.OVERVIEW },
                    text = { Text("Overview") }
                )
                Tab(
                    selected = selectedTab == TripDetailTab.TRENDS,
                    onClick = { selectedTab = TripDetailTab.TRENDS },
                    text = { Text("Trends") }
                )
                Tab(
                    selected = selectedTab == TripDetailTab.AI_DOCTOR,
                    onClick = { selectedTab = TripDetailTab.AI_DOCTOR },
                    text = { Text("Car Doctor") }
                )
                Tab(
                    selected = selectedTab == TripDetailTab.RAW_LOGS,
                    onClick = { selectedTab = TripDetailTab.RAW_LOGS },
                    text = { Text("Raw Logs") }
                )
                Tab(
                    selected = selectedTab == TripDetailTab.EXPORT,
                    onClick = { selectedTab = TripDetailTab.EXPORT },
                    text = { Text("Export") }
                )
            }

            when (selectedTab) {
                TripDetailTab.OVERVIEW -> {
                    if (!samplesLoaded) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(strokeWidth = 2.dp)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Loading trip data…",
                                color = TextSecondaryDark,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        // ONE scrolling list for the whole tab (owner 2026-09-16: "unable
                        // to see Trip summary etc"): the fuel card used to sit in a
                        // non-scrolling Column ABOVE the overview's inner LazyColumn - on
                        // trips with a tall card (7 insight blocks) the card ate the whole
                        // viewport, clipped its own last block and left the inner list
                        // zero height. The card is now the first ITEM of the overview
                        // list: everything scrolls together, every card gets its full
                        // content height, nothing is pinned or clipped.
                        TripOverviewView(
                            trip = trip, sampleCount = samples.size, rawCount = rawLogs.size, analysis = aiAnalysis,
                            summary = fuelSummary,
                            pricePerL = viewModel.fuelLogRepository.entries().maxByOrNull { it.idMs }?.pricePerL ?: 0.0,
                            speedPoints = samples.filter { it.pid.takeLast(2) == "0D" }
                                .map { it.instantMs to (it.numericValue ?: 0.0) },
                            altitudeBlankReason = altitudeBlankReason,
                            carpoolSlot = {
                                CarpoolCard(
                                    entry = carpoolEntry,
                                    fuelLiters = fuelSummary.fuelLiters,
                                    pricePerL = viewModel.fuelLogRepository.entries()
                                        .maxByOrNull { it.idMs }?.pricePerL ?: 0.0,
                                    onAdd = { showCarpool = true },
                                    onDelete = {
                                        carpoolEntry?.let {
                                            viewModel.deleteCarpool(it.idMs)
                                        }
                                    }
                                )
                            }
                        )
                    }
                }
                TripDetailTab.TRENDS -> {
                    TripTrendsView(
                        samples = samples,
                        selectedPids = selectedTrendPids,
                        // Only the Altitude channel gets the permission explanation: naming it while
                        // the owner is looking at torque or coolant would be a lie about that series.
                        altitudeEmptyHint =
                            if (selectedTrendPids.contains(
                                    com.example.analysis.TripTrendAnalyzer.PID_ALTITUDE_GPS
                                ) && samples.none { it.altitudeM != null }
                            ) {
                                com.example.service.BackgroundLocationPolicy.altitudeBlankReason(
                                    bgLocationState, samples.minOfOrNull { it.instantMs }
                                )
                            } else {
                                null
                            },
                        onTogglePid = { pid ->
                            selectedTrendPids = when {
                                pid in selectedTrendPids ->
                                    if (selectedTrendPids.size > 1) selectedTrendPids - pid
                                    else selectedTrendPids // keep at least one signal
                                selectedTrendPids.size < 4 -> selectedTrendPids + pid
                                // Owner 2026-09-17: "gear display not working bro" - at the
                                // 4-overlay cap a 5th tap used to be SILENTLY DROPPED, which
                                // read as a dead chip. Now the newest signal swaps out so
                                // every tap visibly does something.
                                else -> selectedTrendPids.dropLast(1) + pid
                            }
                        }
                    )
                }
                TripDetailTab.AI_DOCTOR -> {
                    TripDoctorView(
                        analysis = aiAnalysis,
                        isAnalyzing = isAnalyzing,
                        onAnalyze = {
                            coroutineScope.launch {
                                isAnalyzing = true
                                try {
                                    tripRepo.runAiCarDoctorAnalysis(tripId)
                                    trip = tripRepo.getTripById(tripId)
                                    aiAnalysis = tripRepo.getAnalysisForTrip(tripId)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                } finally {
                                    isAnalyzing = false
                                }
                            }
                        }
                    )
                }
                TripDetailTab.RAW_LOGS -> {
                    TripRawLogsView(
                        rawLogs = rawLogs,
                        selectedFilter = rawFilter,
                        onSelectFilter = { rawFilter = it }
                    )
                }
                TripDetailTab.EXPORT -> {
                    TripExportView(
                        tripId = tripId,
                        trip = trip,
                        context = context,
                        summary = fuelSummary
                    )
                }
            }
        }
    }
}

@Composable
private fun TripOverviewView(
    trip: TripEntity?,
    sampleCount: Int,
    rawCount: Int,
    analysis: AiAnalysisEntity?,
    summary: com.example.analysis.TripFuelSummary.Summary,
    pricePerL: Double,
    speedPoints: List<Pair<Long, Double>>,
    /** Why the altitude column is blank, when it is. Computed by the caller from the location grants. */
    altitudeBlankReason: String? = null,
    /** Car-pool card slot (owner 2026-09-19): rendered as an item of THIS list so it scrolls
     *  with everything else - the 2026-09-16 clipping fix forbids cards outside the list. */
    carpoolSlot: (@Composable () -> Unit)? = null
) {
    if (trip == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = CyberCyan)
        }
        return
    }

    LazyColumn(
        // OWNER READABILITY FIX (2026-09-16): edge-to-edge draws under the system
        // navigation bar, so the last card scrolled out of reach behind it. Reserve
        // the nav-bar insets so every card can be scrolled fully into view.
        modifier = Modifier.fillMaxSize().navigationBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            TripFuelLogCard(summary, trip)
        }
        carpoolSlot?.let { slot ->
            item { slot() }
        }
        item {
            // Replicated OBDeleven trip-detail cards (owner reference screen 2, 2026-09-13)
            // Owner field report 2026-09-18 ("Altitude"): a 1 h 33 min pocketed-phone drive on
            // Android 10+ has no GPS fixes at all without 'Allow all the time', and the old footnote
            // could not name that cause. The reason arrives from the caller, which owns the grants.
            TrackerSummaryCards(
                summary = summary,
                pricePerL = pricePerL,
                speedPoints = speedPoints,
                maxAltitudeM = trip.maxAltitudeM,
                minAltitudeM = trip.minAltitudeM,
                altitudeBlankReason = if (trip.maxAltitudeM == null) altitudeBlankReason else null
            )
        }
        item {
            // Health Badge Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = DarkSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, CyberCyan.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("TELEMETRY HEALTH SCORE", color = TextSecondaryDark, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = "${trip.healthScore} / 100",
                            color = if (trip.healthScore >= 80) NeonEmerald else ElectricAmber,
                            fontSize = 32.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black
                        )
                        Text("Status: ${analysis?.overallHealth ?: "NORMAL"}", color = CyberCyan, fontSize = 14.sp)
                    }

                    Surface(
                        color = (if (trip.healthScore >= 80) NeonEmerald else ElectricAmber).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = trip.status,
                            color = if (trip.healthScore >= 80) NeonEmerald else ElectricAmber,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        item {
            // Metrics Quad
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricCard("PEAK RPM", "${trip.maxRpm.toInt()}", "RPM", NeonEmerald, Modifier.weight(1f))
                MetricCard("TOP SPEED", "${trip.maxSpeedKmh.toInt()}", "km/h", CyberCyan, Modifier.weight(1f))
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricCard("MAX COOLANT", "${trip.maxCoolantC.toInt()}", "°C", ElectricAmber, Modifier.weight(1f))
                MetricCard("AVG VOLTAGE", String.format(java.util.Locale.US, "%.2f", trip.avgVoltageV), "V", NeonEmerald, Modifier.weight(1f))
            }
        }

        item {
            // Configuration & Session Details
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = DarkSurface
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("SESSION PARAMETERS", color = TextSecondaryDark, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    DetailRow("Vehicle Profile", trip.vehicleName)
                    DetailRow("Adapter Used", trip.adapterName)
                    DetailRow("Protocol", trip.protocolName)
                    DetailRow("Detected ECUs", trip.detectedEcus)
                    DetailRow("Duration", "${trip.durationSeconds / 60}m ${trip.durationSeconds % 60}s")
                    DetailRow("Samples Recorded", "$sampleCount samples")
                    DetailRow("Raw CAN Frames", "$rawCount frames")
                    // Was: DetailRow("Start Time (UTC)", trip.startTimeUtc) - the stored string
                    // printed verbatim under a label that announced UTC. For a trip recorded before
                    // 1.0.337 that string really is UTC, so the owner read a time five and a half
                    // hours behind the drive he was looking at; for one recorded after, the label
                    // contradicted the IST value beside it. Either way it was the one place in the
                    // app that showed him a zone he did not ask for.
                    //
                    // The instant comes from startTimestamp, not from the string: this row carries
                    // both, and the millis cannot have been written in the wrong zone. See
                    // RecordTime.instantOf for why that matters for history spanning the change.
                    DetailRow(
                        "Start Time (IST)",
                        com.example.data.RecordTime.instantOf(trip.startTimestamp, trip.startTimeUtc)
                            ?.let { com.example.data.RecordTime.format("yyyy-MM-dd HH:mm:ss", it) }
                            ?: "--"
                    )
                    DetailRow(
                        "End Time (IST)",
                        com.example.data.RecordTime.instantOf(trip.endTimestamp, trip.endTimeUtc)
                            ?.let { com.example.data.RecordTime.format("yyyy-MM-dd HH:mm:ss", it) }
                            ?: "--"
                    )
                    // Tank level at both ends of the drive, straight off the trip log's own
                    // columns (owner 2026-09-22). The fuel card derives the pair from the 012F
                    // rows so pre-migration trips show it too; this row prints what the LOG says,
                    // and "-- %" when the drive never recorded a level.
                    DetailRow(
                        "Fuel % (start → end)",
                        if (trip.startFuelLevelPct == null && trip.endFuelLevelPct == null) {
                            "-- % (PID 012F not recorded for this trip)"
                        } else {
                            "${trip.startFuelLevelPct?.let { String.format(java.util.Locale.US, "%.1f %%", it) } ?: "-- %"}" +
                                " → " +
                                "${trip.endFuelLevelPct?.let { String.format(java.util.Locale.US, "%.1f %%", it) } ?: "-- %"}"
                        }
                    )
                    DetailRow(
                        "Altitude (min → max)",
                        if (trip.minAltitudeM == null && trip.maxAltitudeM == null) {
                            "-- m (no GPS fix recorded)"
                        } else {
                            "${trip.minAltitudeM?.let { String.format(java.util.Locale.US, "%.0f m", it) } ?: "-- m"}" +
                                " → " +
                                "${trip.maxAltitudeM?.let { String.format(java.util.Locale.US, "%.0f m", it) } ?: "-- m"}"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    unit: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.height(85.dp),
        shape = RoundedCornerShape(12.dp),
        color = DarkSurface
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, color = TextSecondaryDark, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(value, color = accentColor, fontSize = 22.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text(unit, color = TextSecondaryDark, fontSize = 13.sp, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = TextSecondaryDark, fontSize = 14.sp)
        Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * pid -> name -> unit -> series colour (chip fill + line + legend dot all match).
 * [transform] converts stored raw values for derived channels: Torque plots PID 0162
 * (percent-of-reference) as Nm via the ECU's own 0164 reference or the factory plateau.
 */
private data class TrendChannel(
    val pid: String,
    val name: String,
    val unit: String,
    val color: Color,
    val transform: ((Double) -> Double)? = null,
    val discrete: Boolean = false
)

@Composable
private fun TripTrendsView(
    samples: List<TelemetrySampleEntity>,
    selectedPids: List<String>,
    onTogglePid: (String) -> Unit,
    /** Shown under the empty chart only when the Altitude channel is the one with nothing to draw. */
    altitudeEmptyHint: String? = null
) {
    // Pipeline task 1 (owner 2026-09-16: "let me add multiple signals the same trend see
    // the behaviour w.r.t other signal"): chips now TOGGLE (up to 4 at once) and every
    // selected signal draws on the same time axis with its own colour. First picked keeps
    // the labelled left axis + envelope/area treatment; second gets a labelled right axis;
    // 3rd/4th are scaled to fit and flagged "fit" in the legend - exact values via the
    // crosshair bubble, which lists every overlaid signal with its own unit.
    // Reference torque for the derived Nm channel: the ECU's own 0164 when it answered,
    // else the factory 178 Nm plateau (owner pipeline task 6: "Engine torque calculations").
    // 0163 first: the owner's 2026-09-16 on-car PID validation shows this ECU reports
    // its 175 Nm reference on 0163 and stays silent on 0164.
    val torqueRefNm = samples
        .firstOrNull { it.pid.equals("0163", ignoreCase = true) || it.pid.equals("63", ignoreCase = true) }
        ?.numericValue
        ?: samples.firstOrNull { it.pid.equals("0164", ignoreCase = true) || it.pid.equals("64", ignoreCase = true) }
            ?.numericValue
        ?: com.example.engine.PowertrainModel.PEAK_TORQUE_NM
    val channels = listOf(
        TrendChannel("010C", "Engine RPM", "rpm", CyberCyan),
        TrendChannel("010D", "Speed", "km/h", NeonEmerald),
        // Bright cyan, NOT WarningRed: the owner's red accent theme paints Engine RPM in
        // red, and two reds on one chart defeat the whole point of per-signal colours
        // (owner question 2026-09-16: "does it show some variation of colour for each
        // signal so I can see difference easily?").
        TrendChannel("0105", "Coolant", "\u00b0C", Color(0xFF00E5FF)),
        TrendChannel("010B", "MAP / Boost", "kPa", ResearchPurple),
        TrendChannel("0142", "Voltage", "V", ElectricAmber),
        TrendChannel("0111", "Throttle", "%", Color(0xFFFF6EC7)),
        TrendChannel("0104", "Load", "%", Color(0xFF64FFDA)),
        TrendChannel(
            "0162", "Torque", "Nm", Color(0xFF64B5F6),
            transform = { pct -> com.example.engine.PowertrainModel.torqueNmFromPercent(pct, torqueRefNm) }
        ),
        // Fuel flow (owner 2026-09-22: "fuel flow rate PID is not available in trends"). A chip
        // wired to 015E alone is EMPTY on this car - its 0180 bitmap claims 9D, not 5E - so the
        // channel is derived: whichever fuel-rate pid answered, reported in L/h.
        TrendChannel(
            com.example.analysis.TripTrendAnalyzer.PID_FUEL_RATE_LH, "Fuel Rate", "L/h",
            Color(0xFFFFD54F)
        ),
        // Tank level (PID 012F): the percentage the fuel card reports at the start and end of the
        // trip, drawn over the whole drive so a mid-trip fill is visible as a step.
        TrendChannel(
            com.example.analysis.TripTrendAnalyzer.PID_FUEL_LEVEL, "Fuel Level", "%",
            Color(0xFFA1887F)
        ),
        // Derived channels (owner 2026-09-16/17): computed from stored rpm/torque/speed,
        // available on EVERY trip ever recorded. Kept next to Torque so they are found
        // without scrolling the whole chip row.
        TrendChannel(com.example.analysis.TripTrendAnalyzer.PID_POWER_KW, "Power (2\u03c0NT/60)", "kW", Color(0xFFEEFF41)),
        TrendChannel(com.example.analysis.TripTrendAnalyzer.PID_GEAR, "Gear (est)", "gear", Color(0xFFB0BEC5), discrete = true),
        // GPS altitude: not an OBD PID - extracted from the per-sample altitude stamp.
        // Deep-orange 200: distinct from every other channel colour, incl. the red
        // accent theme and the cyan coolant line.
        TrendChannel(com.example.analysis.TripTrendAnalyzer.PID_ALTITUDE_GPS, "Altitude (GPS)", "m", Color(0xFFFFAB91))
    )

    fun pointsFor(ch: TrendChannel): List<Pair<Long, Double>> =
        if (ch.pid == com.example.analysis.TripTrendAnalyzer.PID_POWER_KW) {
            com.example.analysis.TripTrendAnalyzer.powerPoints(
                samples,
                timestamp = { it.instantMs },
                pid = { it.pid },
                value = { it.numericValue }
            )
        } else if (ch.pid == com.example.analysis.TripTrendAnalyzer.PID_GEAR) {
            com.example.analysis.TripTrendAnalyzer.gearPoints(
                samples,
                timestamp = { it.instantMs },
                pid = { it.pid },
                value = { it.numericValue }
            )
        } else if (ch.pid == com.example.analysis.TripTrendAnalyzer.PID_ALTITUDE_GPS) {
            com.example.analysis.TripTrendAnalyzer.altitudePoints(
                samples,
                timestamp = { it.instantMs },
                altitudeM = { it.altitudeM }
            )
        } else if (ch.pid == com.example.analysis.TripTrendAnalyzer.PID_FUEL_RATE_LH) {
            com.example.analysis.TripTrendAnalyzer.fuelRatePoints(
                samples,
                timestamp = { it.instantMs },
                pid = { it.pid },
                value = { it.numericValue }
            )
        } else {
            samples
                .filter { it.pid.equals(ch.pid.removePrefix("01"), ignoreCase = true) || it.pid.equals(ch.pid, ignoreCase = true) }
                .mapNotNull { smp -> smp.numericValue?.let { smp.instantMs to (ch.transform?.invoke(it) ?: it) } }
                .sortedBy { it.first }
        }

    // Selection order = axis priority; thin channels drop out here (chart re-checks too).
    val lines = selectedPids.mapNotNull { pid ->
        val ch = channels.firstOrNull { it.pid == pid } ?: return@mapNotNull null
        val pts = pointsFor(ch)
        if (pts.size < 2) null else TrendLine(points = pts, name = ch.name, unit = ch.unit, color = ch.color, discrete = ch.discrete)
    }
    val primary = lines.firstOrNull()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Channel chips: selected chip fills with THAT series' colour so chip, legend dot
        // and plotted line are unmistakably the same signal.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            channels.forEach { ch ->
                val isSelected = ch.pid in selectedPids
                Surface(
                    modifier = Modifier.clickable { onTogglePid(ch.pid) },
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) ch.color else DarkSurface,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isSelected) ch.color else DarkBorder
                    )
                ) {
                    Text(
                        ch.name,
                        fontSize = 13.sp,
                        color = if (isSelected) Color.White else TextSecondaryDark,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }
        Text(
            "Tap to overlay up to 4 signals \u00b7 first picked = left axis \u00b7 second = right axis \u00b7 a 5th tap swaps the newest out \u00b7 pinch to zoom \u00b7 drag to read values \u00b7 each signal is polled every ~4-9 s (one loop rotates ~50 PIDs at ~11 Hz), so a crosshair bubble lists each signal's NEAREST sample - values inside one bubble can be seconds apart in time",
            color = TextSecondaryDark,
            fontSize = 12.sp
        )

        // Trend chart (owner 2026-09-16 redesign + pipeline task 1 multi-signal overlay):
        // fills the remaining screen height; bucket-mean lines, volatility envelope on the
        // primary, real axes with units, HH:mm ticks, mean reference, min/max markers and
        // a crosshair whose bubble lists every overlaid signal.
        // Owner 2026-09-17 screenshot: with nothing drawable the weight(1f) chart card
        // still stretched to full remaining height = a ~1200px bordered void. Wrap it.
        val hasDrawableLines = lines.any { it.points.size >= 2 }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (hasDrawableLines) Modifier.weight(1f) else Modifier)
                .heightIn(min = if (hasDrawableLines) 260.dp else 0.dp),
            shape = RoundedCornerShape(14.dp),
            color = DarkSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, CyberCyan.copy(alpha = 0.2f))
        ) {
            TrendChart(
                emptyHint = altitudeEmptyHint,
                lines = lines,
                modifier = Modifier.fillMaxSize().padding(10.dp)
            )
        }

        // Time context for the PRIMARY signal (which window the trend covers).
        if (primary != null && primary.points.size >= 2) {
            val timeFmt = remember { com.example.data.RecordTime.formatter("HH:mm:ss") }
            val pts = primary.points
            val spanSec = (pts.last().first - pts.first().first) / 1000L
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    timeFmt.format(Date(pts.first().first)),
                    color = TextSecondaryDark, fontSize = 12.sp, fontFamily = FontFamily.Monospace
                )
                Text(
                    "${primary.name}: ${spanSec / 60}m ${spanSec % 60}s span \u00b7 ${pts.size} samples",
                    color = TextSecondaryDark, fontSize = 12.sp
                )
                Text(
                    timeFmt.format(Date(pts.last().first)),
                    color = TextSecondaryDark, fontSize = 12.sp, fontFamily = FontFamily.Monospace
                )
            }
        }

        // Stats summary for the PRIMARY signal (overlays keep the chart clean; their
        // numbers live in the crosshair bubble).
        if (primary != null) {
            val numericValues = primary.points.map { it.second }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = DarkSurface
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val statFmt = if (primary.discrete) "%.0f" else "%.1f"
                    Text("MIN: ${String.format(java.util.Locale.US, statFmt, numericValues.minOrNull() ?: 0.0)} ${primary.unit}", color = TextSecondaryDark, fontSize = 14.sp)
                    Text("AVG: ${String.format(java.util.Locale.US, "%.1f", numericValues.average())} ${primary.unit}", color = primary.color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("MAX: ${String.format(java.util.Locale.US, statFmt, numericValues.maxOrNull() ?: 0.0)} ${primary.unit}", color = NeonEmerald, fontSize = 14.sp)
                    Text("COUNT: ${numericValues.size}", color = TextSecondaryDark, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun TripDoctorView(
    analysis: AiAnalysisEntity?,
    isAnalyzing: Boolean,
    onAnalyze: () -> Unit
) {
    if (analysis == null) {
        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(48.dp))
                Text("AI Car Doctor Analysis", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                Text(
                    "Deterministic on-device intelligence evaluates your vehicle telemetry for thermal, electrical, and powertrain health.",
                    color = TextSecondaryDark,
                    fontSize = 14.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Button(
                    onClick = onAnalyze,
                    enabled = !isAnalyzing,
                    colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = Color.Black)
                ) {
                    Text(if (isAnalyzing) "Analyzing..." else "Run Car Doctor Review")
                }
            }
        }
        return
    }

    val parsedRecommendations = remember(analysis.recommendedChecks) {
        val list = mutableListOf<String>()
        try {
            val recs = JSONArray(analysis.recommendedChecks)
            for (i in 0 until recs.length()) {
                list.add(recs.getString(i))
            }
        } catch (e: Exception) {
            list.add("Check cooling system and battery health periodically.")
        }
        list
    }

    LazyColumn(
        // OWNER READABILITY FIX (2026-09-16): edge-to-edge draws under the system
        // navigation bar, so the last card scrolled out of reach behind it. Reserve
        // the nav-bar insets so every card can be scrolled fully into view.
        modifier = Modifier.fillMaxSize().navigationBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            // Overall Health Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = DarkSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, CyberCyan.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("DIAGNOSTIC HEALTH REVIEW", color = CyberCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(analysis.overallHealth, color = NeonEmerald, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Text(analysis.drivingSummary, color = Color.White, fontSize = 14.sp)
                }
            }
        }

        item {
            DoctorSection("Engine Powertrain Behavior", analysis.engineBehavior)
        }

        item {
            DoctorSection("Thermal & Cooling Management", analysis.temperatureBehavior)
        }

        item {
            DoctorSection("Electrical & Alternator Circuit", analysis.voltageBehavior)
        }

        item {
            DoctorSection("Intake & Boost Dynamics", analysis.throttleLoadBehavior)
        }

        item {
            // Recommendations
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = DarkSurface
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("RECOMMENDED ACTIONS", color = ElectricAmber, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    parsedRecommendations.forEach { rec ->
                        Text("• $rec", color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DoctorSection(title: String, body: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = DarkSurface
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = TextSecondaryDark, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(body, color = Color.White, fontSize = 14.sp)
        }
    }
}

@Composable
private fun TripRawLogsView(
    rawLogs: List<RawLogEntity>,
    selectedFilter: String,
    onSelectFilter: (String) -> Unit
) {
    val filters = listOf("ALL", "ELM", "OBD", "CAN_7E8", "CAN_7E9", "ISO_TP", "PID", "ERROR")
    val filteredLogs = if (selectedFilter == "ALL") rawLogs else rawLogs.filter { it.category.equals(selectedFilter, ignoreCase = true) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Filter Chips
        ScrollableTabRow(
            selectedTabIndex = filters.indexOf(selectedFilter).coerceAtLeast(0),
            containerColor = Color.Transparent,
            contentColor = CyberCyan,
            edgePadding = 0.dp,
            divider = {}
        ) {
            filters.forEach { f ->
                FilterChip(
                    selected = selectedFilter == f,
                    onClick = { onSelectFilter(f) },
                    label = { Text("[$f]", fontSize = 13.sp) },
                    modifier = Modifier.padding(end = 6.dp)
                )
            }
        }

        if (filteredLogs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No log entries match filter [$selectedFilter]", color = TextSecondaryDark, fontSize = 14.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filteredLogs) { log ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = DarkSurface
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("[${log.category}]", color = CyberCyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Text("${log.direction}:", color = if (log.direction == "TX") ElectricAmber else NeonEmerald, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(log.rawLine, color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TripExportView(
    tripId: String,
    trip: TripEntity?,
    context: Context,
    summary: com.example.analysis.TripFuelSummary.Summary
) {
    val sessionDir = File(context.filesDir, "recordings/session_$tripId")
    val txCsv = File(sessionDir, "${tripId}_transactions.csv")
    val sampleCsv = File(sessionDir, "${tripId}_samples.csv")
    val jsonFile = File(sessionDir, "$tripId.json")
    val rawFile = File(sessionDir, "${tripId}_raw.txt")
    
    val safeVehicle = trip?.vehicleName?.replace(Regex("[^a-zA-Z0-9.-]"), "_") ?: "Vehicle"
    val safeDate = com.example.data.RecordTime.format("yyyyMMdd_HHmmss", trip?.startTimestamp ?: System.currentTimeMillis())
    val bundleName = "OBDLogger_${safeVehicle}_${safeDate}_$tripId.zip"
    val zipFile = File(sessionDir, bundleName)
    
    val coroutineScope = rememberCoroutineScope()
    var isZipping by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("EXPORT & SHARE TRIP DATA", color = CyberCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)

        ExportActionCard(
            title = "Export Complete ZIP Bundle",
            desc = "Contains CSVs, JSON metadata, raw logs, and a measured analysis report (AC with/without load, stall battery, torque).",
            file = zipFile,
            mimeType = "application/zip",
            context = context,
            isGenerating = isZipping,
            onExportClick = {
                if (isZipping) return@ExportActionCard
                isZipping = true
                coroutineScope.launch {
                    try {
                        // Analysis report inside the shared bundle (owner pipeline task 7,
                        // 2026-09-16: "Even with trends shared you didn't show me with &
                        // without AC load analysis etc"): measured AC state, load WITH vs
                        // WITHOUT AC, stall battery picture, torque, extremes - every
                        // section evidence-gated, regenerated fresh from stored samples.
                        val reportFile = File(sessionDir, "${tripId}_analysis.md")
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching {
                                reportFile.writeText(
                                    com.example.analysis.TripAnalysisReport.build(
                                        trip?.title ?: tripId,
                                        summary
                                    )
                                )
                            }
                        }
                        val filesToZip = listOf(txCsv, sampleCsv, jsonFile, rawFile, reportFile).filter { it.exists() }
                        if (filesToZip.isEmpty()) {
                            Toast.makeText(context, "No trip data available to zip", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            com.example.data.ZipExporter.createTripZip(zipFile, filesToZip)
                        }
                        shareFileSafely(context, zipFile, "application/zip")
                    } catch (e: Exception) {
                        Toast.makeText(context, "Failed to create ZIP: ${e.message}", Toast.LENGTH_LONG).show()
                    } finally {
                        isZipping = false
                    }
                }
            }
        )

        ExportActionCard(
            title = "Export Transactions CSV",
            desc = "Individual atomic request/response frames with physical conversions.",
            file = txCsv,
            mimeType = "text/csv",
            context = context
        )

        ExportActionCard(
            title = "Export Telemetry Samples CSV",
            desc = "Synchronized multi-parameter matrix for Excel/MATLAB analysis.",
            file = sampleCsv,
            mimeType = "text/csv",
            context = context
        )

        ExportActionCard(
            title = "Export Session JSON",
            desc = "Complete structured session tree for programmatic parsing.",
            file = jsonFile,
            mimeType = "application/json",
            context = context
        )

        if (rawFile.exists()) {
            ExportActionCard(
                title = "Export Raw Terminal TXT",
                desc = "Pure ASCII ELM327 console log.",
                file = rawFile,
                mimeType = "text/plain",
                context = context
            )
        }
    }
}

@Composable
private fun ExportActionCard(
    title: String,
    desc: String,
    file: File,
    mimeType: String,
    context: Context,
    isGenerating: Boolean = false,
    onExportClick: (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = DarkSurface
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(desc, color = TextSecondaryDark, fontSize = 13.sp)
                Text("File size: ${if (file.exists()) "${file.length() / 1024} KB" else "Ready on generate"}", color = CyberCyan, fontSize = 12.sp)
            }
            if (isGenerating) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = CyberCyan)
            } else {
                IconButton(
                    onClick = {
                        if (onExportClick != null) {
                            onExportClick()
                        } else {
                            shareFileSafely(context, file, mimeType)
                        }
                    }
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Share", tint = NeonEmerald)
                }
            }
        }
    }
}

private fun shareFileSafely(context: Context, file: File, mimeType: String) {
    if (!file.exists() || file.length() == 0L) {
        Toast.makeText(context, "File does not exist or is empty", Toast.LENGTH_SHORT).show()
        return
    }

    try {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(intent, "Share ${file.name}")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    } catch (e: Exception) {
        Toast.makeText(context, "Share error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
    }
}

/**
 * OWNER READABILITY REBUILD (2026-09-16, fourth report of "UI is not letting me
 * read"): the fuel card was a wall of tight, fully-coloured bold paragraphs - no
 * hierarchy, nothing for the eye to grab, every line shouting equally. Each insight
 * is now a titled block: coloured 3dp accent bar + small-caps title in the signal
 * colour + calm near-white body at a comfortable line height, air between blocks.
 */
@Composable
private fun InsightBlock(accent: Color, title: String, body: String) {
    // height(IntrinsicSize.Min) is what makes the accent bar's fillMaxHeight() match
    // the TEXT block: inside a LazyColumn item the incoming height is unbounded, and
    // without the intrinsic recipe the bar stretched to ~1000px of empty colour
    // (owner 2026-09-16: "why unnecessary empty space FIX ASAP").
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 7.dp)
    ) {
        Box(
            modifier = Modifier.width(3.dp).fillMaxHeight()
                .background(accent, RoundedCornerShape(2.dp))
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                title,
                color = accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                body,
                color = Color(0xFFE4E9EE),
                fontSize = 13.sp,
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
private fun TripFuelLogCard(
    summary: com.example.analysis.TripFuelSummary.Summary,
    /** The trip's OWN persisted fuel percentages, for a drive whose samples no longer carry them. */
    trip: TripEntity? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                "Log fuel (integrated from PID 015E/019D)",
                color = CyberCyan,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row {
                Column(modifier = Modifier.padding(end = 18.dp)) {
                    Text(String.format(java.util.Locale.US, "%.2f L", summary.fuelLiters), color = NeonEmerald, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("FUEL BURNED", color = TextSecondaryDark, fontSize = 12.sp)
                }
                Column(modifier = Modifier.padding(end = 18.dp)) {
                    Text(String.format(java.util.Locale.US, "%.1f km", summary.distanceKm), color = NeonEmerald, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("DISTANCE", color = TextSecondaryDark, fontSize = 12.sp)
                }
                Column(modifier = Modifier.padding(end = 18.dp)) {
                    Text(summary.kmPerLiter?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "--", color = ElectricAmber, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("KM/L", color = TextSecondaryDark, fontSize = 12.sp)
                }
                Column {
                    Text(summary.litersPer100Km?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "--", color = ElectricAmber, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("L/100KM", color = TextSecondaryDark, fontSize = 12.sp)
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Avg ${String.format(java.util.Locale.US, "%.0f", summary.averageSpeedKmh)} km/h (moving " +
                    "${String.format(java.util.Locale.US, "%.0f", summary.movingAverageSpeedKmh)}), max " +
                    "${String.format(java.util.Locale.US, "%.0f", summary.maxSpeedKmh)} km/h • coasting " +
                    "${String.format(java.util.Locale.US, "%.0f", summary.coastSeconds)} s • idling " +
                    "${String.format(java.util.Locale.US, "%.0f", summary.idleSeconds)} s" +
                    // Idle start-stop stalls are standstill-with-engine-OFF: shown separately,
                    // never folded into "idling" (2026-09-15).
                    if (summary.engineOffSeconds > 0.0) {
                        " • engine off ${String.format(java.util.Locale.US, "%.0f", summary.engineOffSeconds)} s"
                    } else "",
                color = TextSecondaryDark,
                fontSize = 13.sp
            )
            // Tank level at the START and at the END of this drive (owner 2026-09-22: "Fuel
            // percentage at the start of trip & end of trip is also not available on trip logs").
            // Derived from the trip's own 012F rows, so every trip ever recorded reports it -
            // old, recovered, imported or merged - with the persisted columns as the fallback for
            // a drive whose sample rows were pruned. Blank when the ECU never answered 012F:
            // "-- %" is the truth, a plausible number would not be.
            val level = summary.fuelLevel
            val startPct = level.startPct ?: trip?.startFuelLevelPct
            val endPct = level.endPct ?: trip?.endFuelLevelPct
            if (startPct != null || endPct != null) {
                Spacer(modifier = Modifier.height(4.dp))
                val pct = { v: Double -> String.format(java.util.Locale.US, "%.1f %%", v) }
                val delta = if (startPct != null && endPct != null) endPct - startPct else null
                val whenNote = when {
                    level.startMs != null && level.endMs != null ->
                        " • read ${com.example.data.RecordTime.format("HH:mm:ss", level.startMs)}" +
                            " → ${com.example.data.RecordTime.format("HH:mm:ss", level.endMs)}"
                    else -> ""
                }
                InsightBlock(
                    accent = Color(0xFFFFD54F),
                    title = "TANK LEVEL - MEASURED (PID 012F)",
                    body = "Fuel % start → end: ${startPct?.let(pct) ?: "-- %"} → " +
                        "${endPct?.let(pct) ?: "-- %"}" +
                        (delta?.let {
                            " • ${if (it >= 0) "+" else ""}${String.format(java.util.Locale.US, "%.1f", it)} pts"
                        } ?: "") +
                        (if (level.sampleCount > 0) " • ${level.sampleCount} level row(s)" else "") +
                        whenNote +
                        if (delta != null && delta > 0.5) {
                            " • the tank ROSE during this trip: a fill happened mid-drive, or the " +
                                "sender read higher after a top-up - the integrated litres above " +
                                "are what the engine burned, not what the gauge lost"
                        } else {
                            ""
                        }
                )
            } else {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "TANK LEVEL UNAVAILABLE: PID 012F never answered on this trip, so the start " +
                        "and end fuel percentages are not recorded - nothing is estimated in " +
                        "their place.",
                    color = ElectricAmber, fontSize = 12.sp
                )
            }
            val startStop = summary.startStop
            if (startStop.stopEvents > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                // The saving is an estimate against REAL recorded data: this trip's own
                // measured warm-idle rate when there is enough evidence, otherwise the
                // 1.05 L/h model - and the card always says which one it used.
                val baselineNote = when (startStop.baselineSource) {
                    com.example.analysis.StartStopAnalyzer.Baseline.MEASURED ->
                        "baseline ${String.format(java.util.Locale.US, "%.2f", startStop.baselineIdleLh ?: 0.0)} L/h idle measured on this trip"
                    com.example.analysis.StartStopAnalyzer.Baseline.MODEL ->
                        "baseline ${String.format(java.util.Locale.US, "%.2f", com.example.analysis.StartStopAnalyzer.MODEL_IDLE_LH)} L/h warm-idle model"
                    com.example.analysis.StartStopAnalyzer.Baseline.NONE -> "no baseline"
                }
                InsightBlock(

                    accent = NeonEmerald,

                    title = "START-STOP & IDLE SAVE",

                    body = "Start-stop: ${startStop.stopEvents} stall(s), engine off " +
                        "${String.format(java.util.Locale.US, "%.0f", startStop.engineOffSeconds)} s • fuel saved ≈ " +
                        "${String.format(java.util.Locale.US, "%.2f", startStop.estimatedFuelSavedL)} L (estimate, $baselineNote)"

                )
            }
            if (startStop.restartCount > 0 && startStop.restartPeakFuelLh != null) {
                Spacer(modifier = Modifier.height(4.dp))
                InsightBlock(

                    accent = ElectricAmber,

                    title = "RESTART FUEL SPIKE",

                    body = "Restart fuel spike: peak " +
                        "${String.format(java.util.Locale.US, "%.1f", startStop.restartPeakFuelLh)} L/h across " +
                        "${startStop.restartCount} restart(s) — cranking enrichment; real fuel, already inside the " +
                        "trip total and kept out of the idle averages."

                )
            }
            // Owner pipeline task 5 (2026-09-16): "When engine start stop stopped car
            // sometime AC will be still running during that time does it consuming
            // battery" - measured answer from the stall windows themselves.
            val sb = summary.stopBattery
            if (sb.hasEvidence) {
                Spacer(modifier = Modifier.height(4.dp))
                InsightBlock(

                    accent = ElectricAmber,

                    title = "BATTERY DURING STALLS",

                    body = "Battery during ${sb.stopsWithVoltage} stall(s): mean " +
                        "${String.format(java.util.Locale.US, "%.1f", sb.meanVInStops ?: 0.0)} V (min " +
                        "${String.format(java.util.Locale.US, "%.1f", sb.minVInStops ?: 0.0)} V) vs " +
                        "${String.format(java.util.Locale.US, "%.1f", sb.meanVRunning ?: 0.0)} V charging" +
                        (sb.depressionV?.let {
                            " • ${String.format(java.util.Locale.US, "%.1f", it)} V sag under stall loads"
                        } ?: "") +
                        (if (sb.acOnStops > 0) " • ${sb.acOnStops}/${sb.acOnStopsTotal} stall(s) during measured AC-on: blower/fans ran off the battery - the belt-driven compressor cannot spin, so cooling pauses until restart" else "")

                )
            }
            // Battery extremes RECORDED for this trip (owner pipeline task 3, 2026-09-16:
            // "Voltage min max recording"): min/max of the stored 0142 samples WITH the
            // instants they happened - an 11.9 V min at the start is a starter crank, not
            // a dying battery, and the timestamps are what tell those apart. Derived from
            // the trip's own samples, so pre-migration trips show it too.
            val volts = summary.voltageExtremes
            if (volts != null) {
                Spacer(modifier = Modifier.height(4.dp))
                val vFmt = com.example.data.RecordTime.formatter("HH:mm:ss")
                InsightBlock(

                    accent = ElectricAmber,

                    title = "BATTERY EXTREMES",

                    body = "Battery extremes: " +
                        "${String.format(java.util.Locale.US, "%.1f", volts.minV)} V min at ${vFmt.format(java.util.Date(volts.minTs))}" +
                        " \u2192 " +
                        "${String.format(java.util.Locale.US, "%.1f", volts.maxV)} V max at ${vFmt.format(java.util.Date(volts.maxTs))}"

                )
            }
            // Engine torque measured on this trip (owner pipeline task 6, 2026-09-16):
            // PID 0162 percent-of-reference converted with the ECU's own 0164 reference
            // or the factory 178 Nm plateau. Independent of AC evidence - shown whenever
            // 0162 actually answered; when it never did, nothing is fabricated.
            val tMean = summary.meanTorqueNm
            val tPeak = summary.peakTorqueNm
            // The same reference the percent channel was converted with: the ECU's own 0163 (this
            // car answers 175 Nm there) or 0164, else the factory plateau. torqueRefNm from the
            // trends tab is NOT in scope here, so read it off the summary like the numbers beside it.
            val refNm = summary.torqueReferenceNm ?: com.example.engine.PowertrainModel.PEAK_TORQUE_NM
            if (tMean != null && tPeak != null) {
                Spacer(modifier = Modifier.height(4.dp))
                InsightBlock(

                    accent = Color(0xFF64B5F6),

                    title = "ENGINE TORQUE - MEASURED (PID 0162)",

                    body = "Engine torque (measured, PID 0162): mean " +
                        "${String.format(java.util.Locale.US, "%.0f", tMean)} Nm • peak " +
                        "${String.format(java.util.Locale.US, "%.0f", tPeak)} Nm while running • reference " +
                        "${String.format(java.util.Locale.US, "%.0f", refNm)} Nm" +
                        // Owner field check 2026-09-18: a city drive peaked at 186 Nm against the
                        // ECU's own 175 Nm reference and read, next to each other, like a
                        // contradiction. It is not one. 0162 decodes as `A - 125` PERCENT, whose range
                        // runs to +130%, so a momentary over-reference reading is representable and,
                        // on a turbo engine under a transient overboost, real. Say that instead of
                        // leaving two numbers that appear to disagree - and never clip the reading,
                        // because a capped peak would be a fabricated one.
                        if (tPeak > refNm) {
                            " • peak is ${String.format(java.util.Locale.US, "%.0f", tPeak / refNm * 100.0)}" +
                                "% of reference: 0162 is a percent-of-reference PID whose scale runs " +
                                "past 100%, so a brief over-reference transient is a real reading, " +
                                "not a decode error"
                        } else {
                            ""
                        }

                )
            }
            val ac = summary.ac
            if (ac.hasEvidence && ac.segments.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                // Measured AC state (owner 2026-09-16 voltage-ripple insight): the first
                // OBSERVED compressor signal on this car - J1979 has no compressor PID.
                val firstSwitch = ac.switchEvents.firstOrNull()
                val switchNote = firstSwitch?.let { (ts, on) ->
                    " • first flip ${if (on) "ON" else "OFF"} at " +
                        com.example.data.RecordTime.format("HH:mm:ss", ts)
                } ?: ""
                InsightBlock(

                    accent = ResearchPurple,

                    title = "AC - MEASURED FROM VOLTAGE RIPPLE",

                    body = "AC (measured from voltage ripple): ON " +
                        "${String.format(java.util.Locale.US, "%.0f", ac.acOnSeconds / 60.0)} min of " +
                        "${String.format(java.util.Locale.US, "%.0f", summary.durationSeconds / 60.0)} min" +
                        switchNote +
                        " • quiet baseline ±${String.format(java.util.Locale.US, "%.2f", ac.quietMadV ?: 0.0)} V" +
                        (if (ac.confidence < 1.8) " • weak separation - treat as a hint" else "")

                )
                // What the measured AC state COSTS on this trip (owner pipeline task 4,
                // 2026-09-16: "Engine load based on AC on off"): mean engine load (and
                // rpm / fuel when the ECU answers) attributed to the measured regimes,
                // engine-running samples only. Shown only when BOTH regimes carry enough
                // samples - a thin regime stays invisible instead of fabricating a delta.
                val cmp = summary.acLoad
                val d = cmp.loadDeltaPct
                if (cmp.isMeaningful && d != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    InsightBlock(

                        accent = ResearchPurple,

                        title = "AC LOAD IMPACT",

                        body = "AC load impact: ${String.format(java.util.Locale.US, "%+.1f", d)} pts mean load with AC " +
                            "(${String.format(java.util.Locale.US, "%.1f", cmp.acOn.meanLoadPct ?: 0.0)}% on vs " +
                            "${String.format(java.util.Locale.US, "%.1f", cmp.acOff.meanLoadPct ?: 0.0)}% off)" +
                            (cmp.fuelDeltaLh?.let {
                                " • ${String.format(java.util.Locale.US, "%+.2f", it)} L/h fuel"
                            } ?: "") +
                            (cmp.rpmDelta?.let {
                                " • ${String.format(java.util.Locale.US, "%+.0f", it)} rpm"
                            } ?: "")

                    )
                }
            }
            if (summary.sampleCount == 0) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "No stored telemetry samples for this trip - nothing to integrate.",
                    color = WarningRed, fontSize = 13.sp
                )
            } else if (!summary.hasFuelSeries) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "FUEL RATE UNAVAILABLE: PIDs 015E/019D never answered on this ECU " +
                        "(common on some petrol ECUs). Fuel figures stay honest at zero - " +
                        "use refuel-log km/L in Fuel Costs instead.",
                    color = ElectricAmber, fontSize = 12.sp
                )
            } else if (!summary.hasSpeedSeries) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "SPEED UNAVAILABLE: PID 010D never answered - distance cannot be integrated.",
                    color = ElectricAmber, fontSize = 12.sp
                )
            }
            if (summary.speedHistogram.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Time by speed band: " + summary.speedHistogram.joinToString("  ") {
                        "${it.first}-${it.first + 10}: ${String.format(java.util.Locale.US, "%.0f", it.second / 60.0)}m"
                    },
                    color = TextSecondaryDark,
                    fontSize = 13.sp
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Compare this card across trips on your daily route: same distance, different " +
                    "technique, different fuel. Coasting seconds and idle minutes are the two " +
                    "biggest levers.",
                color = TextSecondaryDark,
                fontSize = 12.sp
            )
        }
    }
}
