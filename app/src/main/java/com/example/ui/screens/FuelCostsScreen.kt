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
                    FuelStatChip("₹/KM", stats.costPerKm?.let { String.format("%.2f", it) } ?: "--", CyberCyan, Modifier.weight(1f))
                    FuelStatChip("30-DAY ₹", stats.cost30d?.let { String.format("%.0f", it) } ?: "--", ElectricAmber, Modifier.weight(1f))
                    FuelStatChip("₹/L", stats.lastPricePerL?.let { String.format("%.1f", it) } ?: "--", TextSecondaryDark, Modifier.weight(1f))
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
            items(entries, key = { it.idMs }) { entry ->
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.display, color = Color.White, fontSize = 12.sp)
                            entry.odometerKm?.let {
                                Text(
                                    String.format("odometer %.0f km", it),
                                    color = TextSecondaryDark, fontSize = 10.sp
                                )
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

    if (showAdd) {
        RefuelDialog(
            onDismiss = { showAdd = false },
            onSave = { liters, price, odo, station, grade, note ->
                val now = System.currentTimeMillis()
                val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .format(Date(now))
                repo.add(
                    FuelLogCodec.FuelEntry(
                        idMs = now, dateUtc = utc, liters = liters, pricePerL = price,
                        odometerKm = odo, station = station, grade = grade, note = note
                    )
                )
                if (grade != FuelLogCodec.GRADE_UNKNOWN) viewModel.tagFuelGrade(grade)
                odo?.let { viewModel.maintenanceRepository.setCurrentOdometerKm(it) }
                refresh++
                showAdd = false
            }
        )
    }
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
    onSave: (Double, Double, Double?, String, String, String) -> Unit
) {
    var liters by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var odo by remember { mutableStateOf("") }
    var station by remember { mutableStateOf("") }
    var grade by remember { mutableStateOf(FuelLogCodec.GRADE_UNKNOWN) }
    val grades = listOf(FuelLogCodec.GRADE_UNKNOWN, FuelLogCodec.GRADE_X95, FuelLogCodec.GRADE_REGULAR)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log a refuel") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(value = liters, onValueChange = { liters = it }, label = { Text("Litres") }, singleLine = true)
                TextField(value = price, onValueChange = { price = it }, label = { Text("Price ₹/L") }, singleLine = true)
                TextField(value = odo, onValueChange = { odo = it }, label = { Text("Odometer km (optional)") }, singleLine = true)
                TextField(value = station, onValueChange = { station = it }, label = { Text("Station (optional)") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    grades.forEach { g ->
                        FilterChip(
                            selected = grade == g,
                            onClick = { grade = g },
                            label = { Text(g, fontSize = 11.sp) }
                        )
                    }
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
                        onSave(l, p, odo.toDoubleOrNull(), station.trim(), grade, "")
                    }
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
