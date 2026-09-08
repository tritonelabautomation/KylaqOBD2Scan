package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.TripEstimator
import com.example.engine.TripSplitter
import com.example.ui.components.XyPlot
import com.example.ui.components.XySeries
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.ElectricAmber
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.ResearchPurple
import com.example.ui.theme.TextSecondaryDark
import com.example.ui.viewmodel.MainViewModel
import java.util.Calendar
import java.util.Date

/**
 * Reports & analytics (VehIQ Reports, free): year-to-date spend, running cost per km, efficiency
 * and spend trends, budget-vs-actuals, the trip estimator and the shared-drive splitter.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val settings = viewModel.settingsRepository
    var refresh by remember { mutableStateOf(0) }

    val fuel = remember(refresh) { viewModel.fuelLogRepository.entries() }
    val services = remember(refresh) { viewModel.maintenanceRepository.logs() }
    val expenses = remember(refresh) { viewModel.expenseRepository.entries() }
    val fuelStats = remember(refresh) { viewModel.fuelLogRepository.stats() }

    val cal = Calendar.getInstance()
    val yearStart = remember {
        Calendar.getInstance().apply { set(Calendar.MONTH, 0); set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
    }
    val ytdSpend = remember(fuel, services, expenses) {
        fuel.filter { it.idMs >= yearStart }.sumOf { it.totalCost } +
            services.filter { it.dateMs >= yearStart }.sumOf { it.cost ?: 0.0 } +
            expenses.filter { it.idMs >= yearStart }.sumOf { it.amount }
    }

    val monthlySpend = remember(fuel, services, expenses) {
        val buckets = sortedMapOf<String, Double>()
        fun add(ts: Long, amount: Double) {
            cal.time = Date(ts)
            val key = String.format("%04d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
            buckets[key] = (buckets[key] ?: 0.0) + amount
        }
        fuel.forEach { add(it.idMs, it.totalCost) }
        services.forEach { add(it.dateMs, it.cost ?: 0.0) }
        expenses.forEach { add(it.idMs, it.amount) }
        buckets.toList().takeLast(8)
    }

    val efficiencyTrend = remember(fuel) {
        val chrono = fuel.sortedBy { it.idMs }
        val pts = mutableListOf<Pair<Float, Float>>()
        for (i in 1 until chrono.size) {
            val a = chrono[i - 1].odometerKm
            val b = chrono[i].odometerKm
            if (a != null && b != null && b > a && chrono[i].liters > 0.5) pts.add(b.toFloat() to ((b - a) / chrono[i].liters).toFloat())
        }
        pts
    }

    var budget by remember { mutableStateOf(settings.monthlyBudget()?.let { String.format("%.0f", it) } ?: "") }
    val budgetValue = budget.toDoubleOrNull()
    val thisMonthSpend = monthlySpend.lastOrNull()?.second ?: 0.0

    // Trip estimator state
    var estDistance by remember { mutableStateOf("32") }
    var estimate by remember { mutableStateOf<TripEstimator.Estimate?>(null) }

    // Splitter state
    var membersText by remember { mutableStateOf("Me, Friend") }
    var paymentsText by remember { mutableStateOf("Me: 3000") }
    var transfers by remember { mutableStateOf<List<TripSplitter.Transfer>>(emptyList()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reports & Analytics", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = CyberCyan) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric("YTD SPEND", String.format("₹%.0f", ytdSpend), ElectricAmber, Modifier.weight(1f))
                Metric("COST/KM", fuelStats.costPerKm?.let { String.format("₹%.2f", it) } ?: "--", CyberCyan, Modifier.weight(1f))
                Metric("AVG", fuelStats.avgKmPerL?.let { String.format("%.1f km/L", it) } ?: "--", NeonEmerald, Modifier.weight(1f))
            }
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Monthly spend trend", color = TextSecondaryDark, fontSize = 12.sp)
                    if (monthlySpend.size >= 2) {
                        XyPlot(
                            series = listOf(XySeries("₹", ElectricAmber, monthlySpend.mapIndexed { i, p -> i.toFloat() to p.second.toFloat() })),
                            xLabel = "month →", yLabel = "₹", height = 150.dp
                        )
                    } else Text("Not enough data yet.", color = TextSecondaryDark, fontSize = 11.sp)
                }
            }
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Efficiency trend (km/L per tank)", color = TextSecondaryDark, fontSize = 12.sp)
                    if (efficiencyTrend.size >= 2) {
                        XyPlot(
                            series = listOf(XySeries("km/L", NeonEmerald, efficiencyTrend)),
                            xLabel = "odometer km", yLabel = "km/L", height = 150.dp
                        )
                    } else Text("Log fill-ups with odometer readings.", color = TextSecondaryDark, fontSize = 11.sp)
                }
            }
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Budget vs actual (this month)", color = TextSecondaryDark, fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = budget, onValueChange = { budget = it }, label = { Text("Monthly budget ₹") }, singleLine = true, modifier = Modifier.weight(1f))
                        TextButton(onClick = { budgetValue?.let { settings.setMonthlyBudget(it) } }) { Text("Save") }
                    }
                    budgetValue?.let { b ->
                        val fraction = if (b > 0) (thisMonthSpend / b).coerceIn(0.0, 1.5) else 0.0
                        LinearProgressIndicator(
                            progress = { (fraction / 1.5).toFloat() },
                            modifier = Modifier.fillMaxWidth().height(10.dp),
                            color = if (fraction > 1) ResearchPurple else NeonEmerald,
                        )
                        Text(
                            String.format("spent ₹%.0f of ₹%.0f (%.0f%%)", thisMonthSpend, b, fraction * 100),
                            color = if (fraction > 1) ResearchPurple else TextSecondaryDark, fontSize = 11.sp
                        )
                    }
                }
            }
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Trip estimator", color = TextSecondaryDark, fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = estDistance, onValueChange = { estDistance = it }, label = { Text("Distance km") }, singleLine = true, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            estimate = TripEstimator.estimate(
                                distanceKm = estDistance.toDoubleOrNull() ?: 0.0,
                                kmPerL = fuelStats.avgKmPerL ?: 15.0,
                                pricePerL = fuelStats.lastPricePerL ?: 105.0
                            )
                        }) { Text("Estimate") }
                    }
                    estimate?.let { e ->
                        Text(
                            String.format(
                                "fuel ₹%.0f + tolls ₹%.0f = ₹%.0f · %.1f h drive · rideshare would cost ₹%.0f (you save ₹%.0f)",
                                e.fuelCost, e.tollCost, e.totalCost, e.driveHours, e.rideshareCost, e.savingVsRideshare
                            ),
                            color = NeonEmerald, fontSize = 11.sp
                        )
                    } ?: Text("Uses your measured km/L and last fuel price.", color = TextSecondaryDark, fontSize = 10.sp)
                }
            }
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Split a shared drive", color = TextSecondaryDark, fontSize = 12.sp)
                    OutlinedTextField(value = membersText, onValueChange = { membersText = it }, label = { Text("Members (comma separated)") }, singleLine = true)
                    OutlinedTextField(value = paymentsText, onValueChange = { paymentsText = it }, label = { Text("Payments \"Name: amount\" per line") })
                    TextButton(onClick = {
                        val members = membersText.split(',').map { it.trim() }.filter { it.isNotBlank() }
                        val payments = paymentsText.lines().mapNotNull { line ->
                            val parts = line.split(':')
                            if (parts.size == 2) parts[0].trim() to (parts[1].trim().toDoubleOrNull() ?: 0.0) else null
                        }
                        transfers = TripSplitter.settle(members, payments)
                    }) { Text("Settle up") }
                    transfers.forEach { t ->
                        Text("${t.from} → ${t.to} : ₹%.0f".format(t.amount), color = CyberCyan, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Metric(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(vertical = 10.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
    ) {
        Text(label, color = TextSecondaryDark, fontSize = 9.sp)
        Text(value, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}
