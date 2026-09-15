package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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

    // "Log fuel" for this trip: fuel rate integrated over the stored samples.
    val fuelSummary = remember(samples) {
        com.example.analysis.TripFuelSummary.summarize(
            samples.map { com.example.analysis.TripFuelSummary.SamplePoint(it.pid, it.timestamp, it.numericValue) }
        )
    }

    LaunchedEffect(tripId) {
        trip = tripRepo.getTripById(tripId)
        samples = tripRepo.getSamplesForTrip(tripId)
        rawLogs = tripRepo.getRawLogsForTrip(tripId)
        aiAnalysis = tripRepo.getAnalysisForTrip(tripId)
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
                            fontSize = 11.sp
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
                    Column {
                        TripFuelLogCard(fuelSummary)
                        TripOverviewView(
                            trip = trip, sampleCount = samples.size, rawCount = rawLogs.size, analysis = aiAnalysis,
                            summary = fuelSummary,
                            pricePerL = viewModel.fuelLogRepository.entries().maxByOrNull { it.idMs }?.pricePerL ?: 0.0,
                            speedPoints = samples.filter { it.pid.takeLast(2) == "0D" }
                                .map { it.timestamp to (it.numericValue ?: 0.0) }
                        )
                    }
                }
                TripDetailTab.TRENDS -> {
                    TripTrendsView(
                        samples = samples,
                        selectedPids = selectedTrendPids,
                        onTogglePid = { pid ->
                            selectedTrendPids = when {
                                pid in selectedTrendPids ->
                                    if (selectedTrendPids.size > 1) selectedTrendPids - pid
                                    else selectedTrendPids // keep at least one signal
                                selectedTrendPids.size < 4 -> selectedTrendPids + pid
                                else -> selectedTrendPids // 4-way overlay cap
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
                        context = context
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
    speedPoints: List<Pair<Long, Double>>
) {
    if (trip == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = CyberCyan)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            // Replicated OBDeleven trip-detail cards (owner reference screen 2, 2026-09-13)
            TrackerSummaryCards(
                summary = summary,
                pricePerL = pricePerL,
                speedPoints = speedPoints,
                maxAltitudeM = trip.maxAltitudeM,
                minAltitudeM = trip.minAltitudeM
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
                        Text("TELEMETRY HEALTH SCORE", color = TextSecondaryDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = "${trip.healthScore} / 100",
                            color = if (trip.healthScore >= 80) NeonEmerald else ElectricAmber,
                            fontSize = 32.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black
                        )
                        Text("Status: ${analysis?.overallHealth ?: "NORMAL"}", color = CyberCyan, fontSize = 12.sp)
                    }

                    Surface(
                        color = (if (trip.healthScore >= 80) NeonEmerald else ElectricAmber).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = trip.status,
                            color = if (trip.healthScore >= 80) NeonEmerald else ElectricAmber,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
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
                    Text("SESSION PARAMETERS", color = TextSecondaryDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    DetailRow("Vehicle Profile", trip.vehicleName)
                    DetailRow("Adapter Used", trip.adapterName)
                    DetailRow("Protocol", trip.protocolName)
                    DetailRow("Detected ECUs", trip.detectedEcus)
                    DetailRow("Duration", "${trip.durationSeconds / 60}m ${trip.durationSeconds % 60}s")
                    DetailRow("Samples Recorded", "$sampleCount samples")
                    DetailRow("Raw CAN Frames", "$rawCount frames")
                    DetailRow("Start Time (UTC)", trip.startTimeUtc)
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
            Text(title, color = TextSecondaryDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(value, color = accentColor, fontSize = 22.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text(unit, color = TextSecondaryDark, fontSize = 11.sp, modifier = Modifier.padding(bottom = 2.dp))
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
        Text(label, color = TextSecondaryDark, fontSize = 12.sp)
        Text(value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** pid -> name -> unit -> series colour (chip fill + line + legend dot all match). */
private data class TrendChannel(val pid: String, val name: String, val unit: String, val color: Color)

@Composable
private fun TripTrendsView(
    samples: List<TelemetrySampleEntity>,
    selectedPids: List<String>,
    onTogglePid: (String) -> Unit
) {
    // Pipeline task 1 (owner 2026-09-16: "let me add multiple signals the same trend see
    // the behaviour w.r.t other signal"): chips now TOGGLE (up to 4 at once) and every
    // selected signal draws on the same time axis with its own colour. First picked keeps
    // the labelled left axis + envelope/area treatment; second gets a labelled right axis;
    // 3rd/4th are scaled to fit and flagged "fit" in the legend - exact values via the
    // crosshair bubble, which lists every overlaid signal with its own unit.
    val channels = listOf(
        TrendChannel("010C", "Engine RPM", "rpm", CyberCyan),
        TrendChannel("010D", "Speed", "km/h", NeonEmerald),
        TrendChannel("0105", "Coolant", "\u00b0C", WarningRed),
        TrendChannel("010B", "MAP / Boost", "kPa", ResearchPurple),
        TrendChannel("0142", "Voltage", "V", ElectricAmber),
        TrendChannel("0111", "Throttle", "%", Color(0xFFFF6EC7)),
        TrendChannel("0104", "Load", "%", Color(0xFF64FFDA))
    )

    fun pointsFor(pid: String): List<Pair<Long, Double>> = samples
        .filter { it.pid.equals(pid.removePrefix("01"), ignoreCase = true) || it.pid.equals(pid, ignoreCase = true) }
        .mapNotNull { smp -> smp.numericValue?.let { smp.timestamp to it } }
        .sortedBy { it.first }

    // Selection order = axis priority; thin channels drop out here (chart re-checks too).
    val lines = selectedPids.mapNotNull { pid ->
        val ch = channels.firstOrNull { it.pid == pid } ?: return@mapNotNull null
        val pts = pointsFor(pid)
        if (pts.size < 2) null else TrendLine(points = pts, name = ch.name, unit = ch.unit, color = ch.color)
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
                        fontSize = 11.sp,
                        color = if (isSelected) Color.White else TextSecondaryDark,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }
        Text(
            "Tap to overlay up to 4 signals \u00b7 first picked = left axis \u00b7 second = right axis \u00b7 drag on chart to read values",
            color = TextSecondaryDark,
            fontSize = 10.sp
        )

        // Trend chart (owner 2026-09-16 redesign + pipeline task 1 multi-signal overlay):
        // fills the remaining screen height; bucket-mean lines, volatility envelope on the
        // primary, real axes with units, HH:mm ticks, mean reference, min/max markers and
        // a crosshair whose bubble lists every overlaid signal.
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .heightIn(min = 260.dp),
            shape = RoundedCornerShape(14.dp),
            color = DarkSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, CyberCyan.copy(alpha = 0.2f))
        ) {
            TrendChart(
                lines = lines,
                modifier = Modifier.fillMaxSize().padding(10.dp)
            )
        }

        // Time context for the PRIMARY signal (which window the trend covers).
        if (primary != null && primary.points.size >= 2) {
            val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
            val pts = primary.points
            val spanSec = (pts.last().first - pts.first().first) / 1000L
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    timeFmt.format(Date(pts.first().first)),
                    color = TextSecondaryDark, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                )
                Text(
                    "${primary.name}: ${spanSec / 60}m ${spanSec % 60}s span \u00b7 ${pts.size} samples",
                    color = TextSecondaryDark, fontSize = 10.sp
                )
                Text(
                    timeFmt.format(Date(pts.last().first)),
                    color = TextSecondaryDark, fontSize = 10.sp, fontFamily = FontFamily.Monospace
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
                    Text("MIN: ${String.format(java.util.Locale.US, "%.1f", numericValues.minOrNull() ?: 0.0)} ${primary.unit}", color = TextSecondaryDark, fontSize = 12.sp)
                    Text("AVG: ${String.format(java.util.Locale.US, "%.1f", numericValues.average())} ${primary.unit}", color = primary.color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("MAX: ${String.format(java.util.Locale.US, "%.1f", numericValues.maxOrNull() ?: 0.0)} ${primary.unit}", color = NeonEmerald, fontSize = 12.sp)
                    Text("COUNT: ${numericValues.size}", color = TextSecondaryDark, fontSize = 12.sp)
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
                    fontSize = 13.sp,
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
        modifier = Modifier.fillMaxSize().padding(16.dp),
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
                        Text("DIAGNOSTIC HEALTH REVIEW", color = CyberCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(analysis.overallHealth, color = NeonEmerald, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Text(analysis.drivingSummary, color = Color.White, fontSize = 13.sp)
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
                    Text("RECOMMENDED ACTIONS", color = ElectricAmber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    parsedRecommendations.forEach { rec ->
                        Text("• $rec", color = Color.White, fontSize = 12.sp)
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
            Text(title, color = TextSecondaryDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(body, color = Color.White, fontSize = 13.sp)
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
                    label = { Text("[$f]", fontSize = 11.sp) },
                    modifier = Modifier.padding(end = 6.dp)
                )
            }
        }

        if (filteredLogs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No log entries match filter [$selectedFilter]", color = TextSecondaryDark, fontSize = 12.sp)
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
                            Text("[${log.category}]", color = CyberCyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            Text("${log.direction}:", color = if (log.direction == "TX") ElectricAmber else NeonEmerald, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(log.rawLine, color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
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
    context: Context
) {
    val sessionDir = File(context.filesDir, "recordings/session_$tripId")
    val txCsv = File(sessionDir, "${tripId}_transactions.csv")
    val sampleCsv = File(sessionDir, "${tripId}_samples.csv")
    val jsonFile = File(sessionDir, "$tripId.json")
    val rawFile = File(sessionDir, "${tripId}_raw.txt")
    
    val safeVehicle = trip?.vehicleName?.replace(Regex("[^a-zA-Z0-9.-]"), "_") ?: "Vehicle"
    val safeDate = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date(trip?.startTimestamp ?: System.currentTimeMillis()))
    val bundleName = "OBDLogger_${safeVehicle}_${safeDate}_$tripId.zip"
    val zipFile = File(sessionDir, bundleName)
    
    val coroutineScope = rememberCoroutineScope()
    var isZipping by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("EXPORT & SHARE TRIP DATA", color = CyberCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)

        ExportActionCard(
            title = "Export Complete ZIP Bundle",
            desc = "Contains CSVs, JSON metadata, raw logs, and diagnostic analysis.",
            file = zipFile,
            mimeType = "application/zip",
            context = context,
            isGenerating = isZipping,
            onExportClick = {
                if (isZipping) return@ExportActionCard
                isZipping = true
                coroutineScope.launch {
                    try {
                        val filesToZip = listOf(txCsv, sampleCsv, jsonFile, rawFile).filter { it.exists() }
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
                Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(desc, color = TextSecondaryDark, fontSize = 11.sp)
                Text("File size: ${if (file.exists()) "${file.length() / 1024} KB" else "Ready on generate"}", color = CyberCyan, fontSize = 10.sp)
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

@Composable
private fun TripFuelLogCard(summary: com.example.analysis.TripFuelSummary.Summary) {
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
                    Text("FUEL BURNED", color = TextSecondaryDark, fontSize = 10.sp)
                }
                Column(modifier = Modifier.padding(end = 18.dp)) {
                    Text(String.format(java.util.Locale.US, "%.1f km", summary.distanceKm), color = NeonEmerald, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("DISTANCE", color = TextSecondaryDark, fontSize = 10.sp)
                }
                Column(modifier = Modifier.padding(end = 18.dp)) {
                    Text(summary.kmPerLiter?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "--", color = ElectricAmber, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("KM/L", color = TextSecondaryDark, fontSize = 10.sp)
                }
                Column {
                    Text(summary.litersPer100Km?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "--", color = ElectricAmber, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("L/100KM", color = TextSecondaryDark, fontSize = 10.sp)
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
                fontSize = 11.sp
            )
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
                Text(
                    "Start-stop: ${startStop.stopEvents} stall(s), engine off " +
                        "${String.format(java.util.Locale.US, "%.0f", startStop.engineOffSeconds)} s • fuel saved ≈ " +
                        "${String.format(java.util.Locale.US, "%.2f", startStop.estimatedFuelSavedL)} L (estimate, $baselineNote)",
                    color = NeonEmerald,
                    fontSize = 11.sp
                )
            }
            if (startStop.restartCount > 0 && startStop.restartPeakFuelLh != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Restart fuel spike: peak " +
                        "${String.format(java.util.Locale.US, "%.1f", startStop.restartPeakFuelLh)} L/h across " +
                        "${startStop.restartCount} restart(s) — cranking enrichment; real fuel, already inside the " +
                        "trip total and kept out of the idle averages.",
                    color = ElectricAmber,
                    fontSize = 11.sp
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
                        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                            .format(java.util.Date(ts))
                } ?: ""
                Text(
                    "AC (measured from voltage ripple): ON " +
                        "${String.format(java.util.Locale.US, "%.0f", ac.acOnSeconds / 60.0)} min of " +
                        "${String.format(java.util.Locale.US, "%.0f", summary.durationSeconds / 60.0)} min" +
                        switchNote +
                        " • quiet baseline ±${String.format(java.util.Locale.US, "%.2f", ac.quietMadV ?: 0.0)} V" +
                        (if (ac.confidence < 1.8) " • weak separation - treat as a hint" else ""),
                    color = ResearchPurple,
                    fontSize = 11.sp
                )
            }
            if (summary.sampleCount == 0) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "No stored telemetry samples for this trip - nothing to integrate.",
                    color = WarningRed, fontSize = 11.sp
                )
            } else if (!summary.hasFuelSeries) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "FUEL RATE UNAVAILABLE: PIDs 015E/019D never answered on this ECU " +
                        "(common on some petrol ECUs). Fuel figures stay honest at zero - " +
                        "use refuel-log km/L in Fuel Costs instead.",
                    color = ElectricAmber, fontSize = 10.sp
                )
            } else if (!summary.hasSpeedSeries) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "SPEED UNAVAILABLE: PID 010D never answered - distance cannot be integrated.",
                    color = ElectricAmber, fontSize = 10.sp
                )
            }
            if (summary.speedHistogram.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Time by speed band: " + summary.speedHistogram.joinToString("  ") {
                        "${it.first}-${it.first + 10}: ${String.format(java.util.Locale.US, "%.0f", it.second / 60.0)}m"
                    },
                    color = TextSecondaryDark,
                    fontSize = 11.sp
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Compare this card across trips on your daily route: same distance, different " +
                    "technique, different fuel. Coasting seconds and idle minutes are the two " +
                    "biggest levers.",
                color = TextSecondaryDark,
                fontSize = 10.sp
            )
        }
    }
}
