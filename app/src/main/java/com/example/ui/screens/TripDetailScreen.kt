package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.example.ui.theme.*
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File

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
    var selectedTrendPid by remember { mutableStateOf("010C") } // RPM default

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
                    TripOverviewView(trip = trip, sampleCount = samples.size, rawCount = rawLogs.size, analysis = aiAnalysis)
                }
                TripDetailTab.TRENDS -> {
                    TripTrendsView(
                        samples = samples,
                        selectedPid = selectedTrendPid,
                        onSelectPid = { selectedTrendPid = it }
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
                    TripExportView(tripId = tripId, context = context)
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
    analysis: AiAnalysisEntity?
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

@Composable
private fun TripTrendsView(
    samples: List<TelemetrySampleEntity>,
    selectedPid: String,
    onSelectPid: (String) -> Unit
) {
    val pids = listOf(
        "010C" to "Engine RPM",
        "010D" to "Speed",
        "0105" to "Coolant",
        "010B" to "MAP / Boost",
        "0142" to "Voltage",
        "0111" to "Throttle",
        "0104" to "Load"
    )

    val targetSamples = samples.filter { it.pid.equals(selectedPid.removePrefix("01"), ignoreCase = true) || it.pid.equals(selectedPid, ignoreCase = true) }
    val numericValues = targetSamples.mapNotNull { it.numericValue }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // PID Selector Chips
        ScrollableTabRow(
            selectedTabIndex = pids.indexOfFirst { it.first == selectedPid }.coerceAtLeast(0),
            containerColor = Color.Transparent,
            contentColor = CyberCyan,
            edgePadding = 0.dp,
            divider = {}
        ) {
            pids.forEach { (pid, name) ->
                val isSelected = selectedPid == pid
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelectPid(pid) },
                    label = { Text(name, fontSize = 11.sp) },
                    modifier = Modifier.padding(end = 6.dp)
                )
            }
        }

        // Trend Canvas Chart
        Surface(
            modifier = Modifier.fillMaxWidth().height(220.dp),
            shape = RoundedCornerShape(14.dp),
            color = DarkSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, CyberCyan.copy(alpha = 0.2f))
        ) {
            if (numericValues.size < 2) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Insufficient sample points to render trend", color = TextSecondaryDark, fontSize = 12.sp)
                }
            } else {
                val minVal = numericValues.minOrNull() ?: 0.0
                val maxVal = numericValues.maxOrNull() ?: 1.0
                val range = if (maxVal - minVal > 0.001) maxVal - minVal else 1.0

                Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    val w = size.width
                    val h = size.height

                    // Grid lines
                    drawLine(Color(0xFF2A2D3A), Offset(0f, 0f), Offset(w, 0f), strokeWidth = 1f)
                    drawLine(Color(0xFF2A2D3A), Offset(0f, h / 2f), Offset(w, h / 2f), strokeWidth = 1f)
                    drawLine(Color(0xFF2A2D3A), Offset(0f, h), Offset(w, h), strokeWidth = 1f)

                    val path = Path()
                    val stepX = w / (numericValues.size - 1)

                    numericValues.forEachIndexed { i, v ->
                        val normY = ((v - minVal) / range).toFloat()
                        val y = h - (normY * h)
                        val x = i * stepX
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }

                    drawPath(
                        path = path,
                        color = CyberCyan,
                        style = Stroke(width = 3.dp.toPx())
                    )
                }
            }
        }

        // Stats summary
        if (numericValues.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = DarkSurface
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("MIN: ${String.format(java.util.Locale.US, "%.1f", numericValues.minOrNull() ?: 0.0)}", color = TextSecondaryDark, fontSize = 12.sp)
                    Text("AVG: ${String.format(java.util.Locale.US, "%.1f", numericValues.average())}", color = CyberCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("MAX: ${String.format(java.util.Locale.US, "%.1f", numericValues.maxOrNull() ?: 0.0)}", color = NeonEmerald, fontSize = 12.sp)
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
    context: Context
) {
    val sessionDir = File(context.filesDir, "recordings/session_$tripId")
    val txCsv = File(sessionDir, "${tripId}_transactions.csv")
    val sampleCsv = File(sessionDir, "${tripId}_samples.csv")
    val jsonFile = File(sessionDir, "$tripId.json")
    val rawFile = File(sessionDir, "${tripId}_raw.txt")
    val zipFile = File(sessionDir, "${tripId}_bundle.zip")

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
            context = context
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
    context: Context
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

            IconButton(
                onClick = {
                    shareFileSafely(context, file, mimeType)
                }
            ) {
                Icon(Icons.Default.Share, contentDescription = "Share", tint = NeonEmerald)
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
