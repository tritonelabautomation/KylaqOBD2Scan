package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.FuelLogCodec
import com.example.ui.components.XyPlot
import com.example.ui.components.XySeries
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.ElectricAmber
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.TextSecondaryDark
import com.example.ui.theme.WarningRed
import com.example.ui.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Fuelio/VehIQ-style fill-up journal: manual fuel logs with price, station and grade, plus the
 * cost & efficiency statistics they unlock (km/L from odometer deltas, ₹/km, 30-day spend) and a
 * per-tank efficiency chart. Logging a grade (X95 / regular) also stamps the live OBD tank
 * segment so the X95 comparison gets ground truth instead of heuristics.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FuelCostsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val repo = viewModel.fuelLogRepository
    var refresh by remember { mutableStateOf(0) }
    val entries = remember(refresh) { repo.entries() }
    val stats = remember(refresh) { repo.stats() }
    var showAdd by remember { mutableStateOf(false) }
    val cur by viewModel.settingsRepository.currencySymbol.collectAsState()

    LaunchedEffect(Unit) { if (viewModel.takeQuickAdd("fuel")) showAdd = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fuel & Costs", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = CyberCyan)
                    }
                },
                actions = {
                    IconButton(onClick = { showAdd = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Log refuel", tint = NeonEmerald)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FuelStatChip("AVG", stats.avgKmPerL?.let { String.format("%.1f km/L", it) } ?: "--", NeonEmerald, Modifier.weight(1f))
                    FuelStatChip("LAST", stats.lastKmPerL?.let { String.format("%.1f km/L", it) } ?: "--", NeonEmerald, Modifier.weight(1f))
                    FuelStatChip("$cur/L", stats.lastPricePerL?.let { String.format("%.1f", it) } ?: "--", TextSecondaryDark, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FuelStatChip("$cur/KM", stats.costPerKm?.let { String.format("%.2f", it) } ?: "--", CyberCyan, Modifier.weight(1f))
                    FuelStatChip("30-DAY $cur", stats.cost30d?.let { String.format("%.0f", it) } ?: "--", ElectricAmber, Modifier.weight(1f))
                    FuelStatChip("MONTH $cur", stats.costThisMonth?.let { String.format("%.0f", it) } ?: "--", ElectricAmber, Modifier.weight(1f))
                }
                val stationStats = repo.stationStats()
                if (stationStats.isNotEmpty()) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "STATION INTELLIGENCE (from your own log)",
                                color = TextSecondaryDark, fontSize = 11.sp, fontWeight = FontWeight.Bold
                            )
                            stationStats.take(4).forEach { st ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(st.name, color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                    Text("${st.visits}x", color = TextSecondaryDark, fontSize = 10.sp)
                                    Text(String.format("avg %.1f", st.avgPrice), color = CyberCyan, fontSize = 10.sp)
                                    Text(String.format("best %.1f", st.bestPrice), color = NeonEmerald, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
            item {
                val chronological = entries.sortedBy { it.idMs }
                val points = mutableListOf<Pair<Float, Float>>()
                for (i in 1 until chronological.size) {
                    val prevOdo = chronological[i - 1].odometerKm
                    val odo = chronological[i].odometerKm
                    if (prevOdo != null && odo != null && odo > prevOdo && chronological[i].liters > 0.5f) {
                        points.add(odo.toFloat() to ((odo - prevOdo) / chronological[i].liters).toFloat())
                    }
                }
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Efficiency per tank (odometer-based, like Fuelio)",
                            color = TextSecondaryDark, fontSize = 12.sp
                        )
                        if (points.size >= 2) {
                            XyPlot(
                                series = listOf(XySeries("km/L", NeonEmerald, points)),
                                xLabel = "odometer km",
                                yLabel = "km/L",
                                height = 170.dp
                            )
                        } else {
                            Text(
                                "Log at least two fill-ups WITH odometer to draw the curve.",
                                color = TextSecondaryDark, fontSize = 12.sp,
                                modifier = Modifier.padding(vertical = 18.dp)
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    "FILL-UP LOG (${entries.size})",
                    color = TextSecondaryDark, fontSize = 11.sp, fontWeight = FontWeight.Bold
                )
            }
            if (entries.isEmpty()) {
                item {
                    Text(
                        "No fill-ups logged yet. Tap + to record your next refuel — grade, litres, " +
                            "price and odometer. OBD log fuel stays independent; this journal adds " +
                            "money and ground-truth grade.",
                        color = TextSecondaryDark, fontSize = 12.sp
                    )
                }
            }
            val chronologicalForPrev = entries.sortedBy { it.idMs }
            val prevOdoById = mutableMapOf<Long, Double?>()
            chronologicalForPrev.forEachIndexed { idx, e -> prevOdoById[e.idMs] = if (idx > 0) chronologicalForPrev[idx - 1].odometerKm else null }
            val intervalById = stats.intervals.toMap()
            val descending = entries.sortedByDescending { it.idMs }
            var currentMonth = ""
            descending.forEach { entry ->
                val monthCal = java.util.Calendar.getInstance().apply { timeInMillis = entry.idMs }
                val monthKey = String.format(java.util.Locale.US, "%04d-%02d", monthCal.get(java.util.Calendar.YEAR), monthCal.get(java.util.Calendar.MONTH) + 1)
                if (monthKey != currentMonth) {
                    currentMonth = monthKey
                    item(key = "month-$monthKey") {
                        Text(
                            monthLabel(entry.idMs),
                            color = TextSecondaryDark, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
                item(key = entry.idMs) {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.LocalGasStation,
                                contentDescription = null,
                                tint = when (entry.grade) {
                                    FuelLogCodec.GRADE_X95 -> NeonEmerald
                                    FuelLogCodec.GRADE_REGULAR -> ElectricAmber
                                    else -> TextSecondaryDark
                                },
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row {
                                    Text(entry.dateUtc.take(10), color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                    val prev = prevOdoById[entry.idMs]
                                    if (entry.odometerKm != null && prev != null && entry.odometerKm > prev) {
                                        Text(String.format("+%.0f km", entry.odometerKm - prev), color = TextSecondaryDark, fontSize = 10.sp)
                                    }
                                }
                                Text(
                                    String.format(java.util.Locale.US, "%.2f L  ·  ₹%.2f/L  ·  ₹%.0f", entry.liters, entry.pricePerL, entry.totalCost),
                                    color = TextSecondaryDark, fontSize = 10.sp
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val kmL = intervalById[entry.idMs]
                                    Text(
                                        if (kmL != null) String.format("km/L: %.2f", kmL) else "km/L: --",
                                        color = if (kmL != null) NeonEmerald else TextSecondaryDark,
                                        fontSize = 11.sp, fontWeight = FontWeight.Bold
                                    )
                                    if (kmL != null && kmL > 0.1) {
                                        Text(String.format("₹%.2f/km", entry.pricePerL / kmL), color = CyberCyan, fontSize = 10.sp)
                                    }
                                    if (entry.partial) {
                                        Text("PARTIAL", color = ElectricAmber, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                if (entry.station.isNotBlank() || entry.note.isNotBlank()) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (entry.station.isNotBlank()) {
                                            Icon(Icons.Default.Place, contentDescription = null, tint = TextSecondaryDark, modifier = Modifier.size(12.dp))
                                            Text(entry.station, color = TextSecondaryDark, fontSize = 10.sp)
                                        }
                                        if (entry.note.isNotBlank()) {
                                            Icon(Icons.Default.Note, contentDescription = "Note", tint = TextSecondaryDark, modifier = Modifier.size(12.dp))
                                        }
                                    }
                                }
                            }
                            IconButton(onClick = { repo.delete(entry.idMs); refresh++ }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = WarningRed, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        RefuelDialog(
            onDismiss = { showAdd = false },
            recentStations = entries.map { it.station }.filter { it.isNotBlank() }.distinct().take(4),
            onSave = { liters, price, odo, station, grade, note, partial, dateStr, timeStr ->
                val cal = java.util.Calendar.getInstance()
                val dp = dateStr.split('-')
                val tp = timeStr.split(':')
                if (dp.size == 3 && tp.size == 2) {
                    cal.set(dp[0].toIntOrNull() ?: cal.get(java.util.Calendar.YEAR),
                        (dp[1].toIntOrNull() ?: 1) - 1, dp[2].toIntOrNull() ?: 1,
                        tp[0].toIntOrNull() ?: 12, tp[1].toIntOrNull() ?: 0, 0)
                }
                val ms = cal.timeInMillis
                val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .format(Date(ms))
                repo.add(
                    FuelLogCodec.FuelEntry(
                        idMs = ms, dateUtc = utc, liters = liters, pricePerL = price,
                        odometerKm = odo, station = station, grade = grade, note = note,
                        partial = partial
                    )
                )
                viewModel.triggerCloudBackupIfEnabled()
                if (grade != FuelLogCodec.GRADE_UNKNOWN) viewModel.tagFuelGrade(grade)
                odo?.let { viewModel.maintenanceRepository.setCurrentOdometerKm(it) }
                refresh++
                showAdd = false
            }
        )
    }
}

private fun monthLabel(idMs: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = idMs }
    return String.format("%s %d",
        cal.getDisplayName(java.util.Calendar.MONTH, java.util.Calendar.LONG, java.util.Locale.US),
        cal.get(java.util.Calendar.YEAR))
}

@Composable
private fun FuelStatChip(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = TextSecondaryDark, fontSize = 9.sp)
        Text(value, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RefuelDialog(
    onDismiss: () -> Unit,
    recentStations: List<String>,
    onSave: (Double, Double, Double?, String, String, String, Boolean, String, String) -> Unit
) {
    var liters by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var total by remember { mutableStateOf("") }
    var odo by remember { mutableStateOf("") }
    var station by remember { mutableStateOf("") }
    var grade by remember { mutableStateOf(FuelLogCodec.GRADE_UNKNOWN) }
    var note by remember { mutableStateOf("") }
    var partial by remember { mutableStateOf(false) }
    val nowCal = java.util.Calendar.getInstance()
    var dateStr by remember {
        mutableStateOf(String.format(java.util.Locale.US, "%04d-%02d-%02d",
            nowCal.get(java.util.Calendar.YEAR), nowCal.get(java.util.Calendar.MONTH) + 1, nowCal.get(java.util.Calendar.DAY_OF_MONTH)))
    }
    var timeStr by remember {
        mutableStateOf(String.format(java.util.Locale.US, "%02d:%02d",
            nowCal.get(java.util.Calendar.HOUR_OF_DAY), nowCal.get(java.util.Calendar.MINUTE)))
    }
    val grades = listOf(FuelLogCodec.GRADE_UNKNOWN, FuelLogCodec.GRADE_X95, FuelLogCodec.GRADE_REGULAR)

    // VehIQ's receipt scanner, free: pick a receipt photo, Gemini extracts litres/price/station
    // and prefills the form. Disabled (with a hint) when no API key is configured.
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    val voiceEntry = com.example.ui.components.rememberVoiceLauncher { transcript ->
        com.example.data.VoiceParse.liters(transcript)?.let { l ->
            liters = String.format(java.util.Locale.US, "%.2f", l)
            price.toDoubleOrNull()?.let { p -> if (p > 0) total = String.format(java.util.Locale.US, "%.0f", l * p) }
        }
        com.example.data.VoiceParse.price(transcript)?.let { p ->
            price = String.format(java.util.Locale.US, "%.2f", p)
            liters.toDoubleOrNull()?.let { l -> total = String.format(java.util.Locale.US, "%.0f", l * p) }
        }
    }
    val pickReceipt = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) {
            scanning = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            val scan = bytes?.let { com.example.ai.GeminiTextClient.scanReceipt(it) }
            if (scan != null) {
                scan.liters?.let { liters = String.format(java.util.Locale.US, "%.2f", it) }
                scan.pricePerL?.let { price = String.format(java.util.Locale.US, "%.2f", it) }
                scan.vendor?.let { station = it }
            }
            scanning = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log a refuel") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // VehIQ "Adding Fuel": fill any two of litres / price / total - the third computes.
                TextField(
                    value = liters,
                    onValueChange = {
                        liters = it
                        val l = it.toDoubleOrNull()
                        val p = price.toDoubleOrNull()
                        if (l != null && p != null && p > 0) total = String.format(java.util.Locale.US, "%.0f", l * p)
                    },
                    label = { Text("Litres") }, singleLine = true
                )
                TextField(
                    value = price,
                    onValueChange = {
                        price = it
                        val p = it.toDoubleOrNull()
                        val l = liters.toDoubleOrNull()
                        val t = total.toDoubleOrNull()
                        if (p != null && p > 0) {
                            if (l != null) total = String.format(java.util.Locale.US, "%.0f", l * p)
                            else if (t != null) liters = String.format(java.util.Locale.US, "%.2f", t / p)
                        }
                    },
                    label = { Text("Price ₹/L") }, singleLine = true
                )
                TextField(
                    value = total,
                    onValueChange = {
                        total = it
                        val t = it.toDoubleOrNull()
                        val p = price.toDoubleOrNull()
                        val l = liters.toDoubleOrNull()
                        if (t != null && p != null && p > 0 && l == null) {
                            liters = String.format(java.util.Locale.US, "%.2f", t / p)
                        }
                    },
                    label = { Text("Total ₹ (auto)") }, singleLine = true
                )
                TextField(value = odo, onValueChange = { odo = it }, label = { Text("Odometer km (optional)") }, singleLine = true)
                TextField(value = station, onValueChange = { station = it }, label = { Text("Station (optional)") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextField(value = dateStr, onValueChange = { dateStr = it }, label = { Text("Date") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextField(value = timeStr, onValueChange = { timeStr = it }, label = { Text("Time") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                if (recentStations.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        recentStations.take(3).forEach { st ->
                            FilterChip(selected = station == st, onClick = { station = st }, label = { Text(st.take(14), fontSize = 10.sp) })
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Missed previous fill-up (partial tank)", color = TextSecondaryDark, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Switch(checked = partial, onCheckedChange = { partial = it })
                }
                TextField(value = note, onValueChange = { note = it }, label = { Text("Note (optional)") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    grades.forEach { g ->
                        FilterChip(
                            selected = grade == g,
                            onClick = { grade = g },
                            label = { Text(g, fontSize = 11.sp) }
                        )
                    }
                }
                OutlinedButton(
                    onClick = { scanning = true; pickReceipt.launch("image/*") },
                    enabled = !scanning && com.example.ai.GeminiTextClient.isConfigured()
                ) {
                    Icon(Icons.Default.DocumentScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (scanning) "Scanning receipt..." else "Scan receipt (AI)", fontSize = 12.sp)
                }
                if (!com.example.ai.GeminiTextClient.isConfigured()) {
                    Text(
                        "Receipt scan needs a Gemini key: set GEMINI_API_KEY in .env and rebuild.",
                        fontSize = 10.sp, color = TextSecondaryDark
                    )
                }
                TextButton(onClick = { runCatching { voiceEntry.launch(com.example.ui.components.voiceIntent()) } }) {
                    Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Voice: \"filled 35 litres at 108 rupees per litre\"", fontSize = 11.sp)
                }
                Text(
                    "Grade stamps the live OBD tank segment for the X95-vs-regular comparison.",
                    fontSize = 10.sp, color = TextSecondaryDark
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val l = liters.toDoubleOrNull()
                    val p = price.toDoubleOrNull()
                    if (l != null && p != null && l > 0) {
                        onSave(l, p, odo.toDoubleOrNull(), station.trim(), grade, note.trim(), partial, dateStr, timeStr)
                    }
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
