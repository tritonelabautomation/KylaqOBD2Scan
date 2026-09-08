package com.example.ui.screens

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.GeminiTextClient
import com.example.data.ExpenseCodec
import com.example.data.TripPlanCodec
import com.example.engine.TripEstimator
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.ElectricAmber
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.TextSecondaryDark
import com.example.ui.theme.WarningRed
import com.example.ui.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * VehIQ Trips without the paywall: planned quick/road trips with live cost estimates from YOUR
 * measured efficiency, budget tracking, one-tap trip expenses, completion toggle and an AI route
 * planner (Gemini when keyed, offline checklist otherwise). The estimator and splitter live in
 * Reports; GPS co-pilot data comes from the existing recording pipeline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val repo = viewModel.tripPlanRepository
    var refresh by remember { mutableStateOf(0) }
    val plans = remember(refresh) { repo.plans() }
    val stats = remember(refresh) { viewModel.fuelLogRepository.stats() }
    val cur by viewModel.settingsRepository.currencySymbol.collectAsState()

    var showAdd by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<TripPlanCodec.TripPlan?>(null) }
    var deleteTarget by remember { mutableStateOf<TripPlanCodec.TripPlan?>(null) }
    var aiTarget by remember { mutableStateOf<TripPlanCodec.TripPlan?>(null) }
    var aiText by remember { mutableStateOf<String?>(null) }
    var aiBusy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    val upcoming = plans.filter { !it.completed }
    val done = plans.filter { it.completed }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trip Planner", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = CyberCyan)
                    }
                },
                actions = {
                    IconButton(onClick = { showAdd = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Plan a trip", tint = NeonEmerald)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }, containerColor = NeonEmerald) {
                Icon(Icons.Default.Add, contentDescription = "Plan a trip")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            item {
                Text(
                    if (stats.avgKmPerL != null && stats.lastPricePerL != null) {
                        "Estimates use your measured %.1f km/L at %s%.1f/L.".format(stats.avgKmPerL, cur, stats.lastPricePerL)
                    } else {
                        "Log fuel entries with odometer readings to unlock live cost estimates."
                    },
                    color = TextSecondaryDark, fontSize = 11.sp
                )
            }
            status?.let { msg ->
                item { Text(msg, color = NeonEmerald, fontSize = 11.sp) }
            }
            item {
                Text("UPCOMING (${upcoming.size})", color = CyberCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            if (upcoming.isEmpty()) {
                item { Text("No planned trips yet - tap + to plan one.", color = TextSecondaryDark, fontSize = 12.sp) }
            }
            items(upcoming, key = { it.idMs }) { plan ->
                TripCard(
                    plan = plan, cur = cur, stats = stats,
                    onEdit = { editTarget = plan },
                    onDelete = { deleteTarget = plan },
                    onAi = {
                        aiTarget = plan
                        aiText = null
                        aiBusy = true
                        scope.launch {
                            val prompt = buildString {
                                append("Plan a road trip: ${plan.name}, from ${plan.from} to ${plan.to}, ")
                                append("distance ${String.format("%.0f", plan.distanceKm)} km, ")
                                append("car: Skoda Kylaq 1.0 TSI (petrol).")
                                plan.budget?.let { append(" Budget $cur${String.format("%.0f", it)}.") }
                                if (plan.note.isNotBlank()) append(" Note: ${plan.note}.")
                            }
                            val ai = GeminiTextClient.generate(
                                "You are a practical Indian road-trip planner. Give: 1 best departure window, " +
                                    "2-3 stops with reasons, fuel/toll budget split, and one safety tip. " +
                                    "Max 8 short lines, no markdown.",
                                prompt
                            )
                            aiText = ai ?: offlinePlan(plan)
                            aiBusy = false
                        }
                    },
                    onDone = {
                        repo.update(plan.copy(completed = true))
                        status = "Trip marked done: ${plan.name}"
                        refresh++
                    },
                    onExpense = {
                        viewModel.expenseRepository.add(
                            ExpenseCodec.ExpenseEntry(
                                idMs = System.currentTimeMillis(),
                                dateUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                                    .format(Date()),
                                category = "Trip",
                                amount = plan.budget ?: 0.0,
                                vendor = plan.name,
                                note = "Planned budget for ${plan.from} -> ${plan.to}"
                            )
                        )
                        status = "Trip budget logged to Expenses"
                        refresh++
                    }
                )
            }
            if (done.isNotEmpty()) {
                item {
                    Text("COMPLETED (${done.size})", color = TextSecondaryDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                items(done, key = { it.idMs }) { plan ->
                    TripCard(
                        plan = plan, cur = cur, stats = stats,
                        onEdit = { editTarget = plan },
                        onDelete = { deleteTarget = plan },
                        onAi = {},
                        onDone = {
                            repo.update(plan.copy(completed = false))
                            status = "Moved back to upcoming"
                            refresh++
                        },
                        onExpense = {}
                    )
                }
            }
        }
    }

    if (showAdd) {
        TripDialog(
            initial = null,
            onDismiss = { showAdd = false },
            onSave = { plan ->
                repo.add(plan)
                showAdd = false
                status = "Trip planned: ${plan.name}"
                refresh++
            }
        )
    }

    editTarget?.let { plan ->
        TripDialog(
            initial = plan,
            onDismiss = { editTarget = null },
            onSave = { updated ->
                repo.update(updated)
                editTarget = null
                status = "Trip updated"
                refresh++
            }
        )
    }

    deleteTarget?.let { plan ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete trip?") },
            text = { Text("'${plan.name}' and its plan details are removed. Logged trip expenses stay.") },
            confirmButton = {
                TextButton(onClick = { repo.remove(plan.idMs); deleteTarget = null; refresh++ }) {
                    Text("Delete", color = WarningRed)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Keep") } }
        )
    }

    aiTarget?.let { plan ->
        AlertDialog(
            onDismissRequest = { aiTarget = null },
            title = { Text("AI plan: ${plan.name}") },
            text = {
                Column {
                    if (aiBusy) {
                        Text("Planning route, stops and budget split...", color = TextSecondaryDark, fontSize = 12.sp)
                    } else {
                        Text(aiText ?: "", fontSize = 12.sp)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { aiTarget = null }) { Text("Close") } }
        )
    }
}

@Composable
private fun TripCard(
    plan: TripPlanCodec.TripPlan,
    cur: String,
    stats: com.example.data.FuelStats,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAi: () -> Unit,
    onDone: () -> Unit,
    onExpense: () -> Unit
) {
    val estimate = remember(plan.distanceKm, stats) {
        val kml = stats.avgKmPerL
        val price = stats.lastPricePerL
        if (kml != null && price != null) TripEstimator.estimate(plan.distanceKm, kml, price) else null
    }
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(plan.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${plan.from} \u2192 ${plan.to} \u00B7 ${String.format("%.0f", plan.distanceKm)} km \u00B7 " +
                            SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(plan.dateMs)),
                        color = TextSecondaryDark, fontSize = 11.sp
                    )
                }
                if (plan.completed) {
                    Icon(Icons.Default.CheckCircle, contentDescription = "Completed", tint = NeonEmerald)
                }
            }
            if (estimate != null) {
                Text(
                    "Est: %s%.0f (fuel %s%.0f + tolls %s%.0f) \u00B7 %.1f h \u00B7 rideshare %s%.0f, you save %s%.0f".format(
                        cur, estimate.totalCost, cur, estimate.fuelCost, cur, estimate.tollCost,
                        estimate.driveHours, cur, estimate.rideshareCost, cur, estimate.savingVsRideshare
                    ),
                    color = CyberCyan, fontSize = 11.sp
                )
                plan.budget?.let { b ->
                    val over = estimate.totalCost > b
                    Text(
                        if (over) "Over budget by %s%.0f".format(cur, estimate.totalCost - b)
                        else "Within budget: %s%.0f left".format(cur, b - estimate.totalCost),
                        color = if (over) WarningRed else NeonEmerald, fontSize = 11.sp
                    )
                }
            } else {
                plan.budget?.let {
                    Text("Budget: %s%.0f".format(cur, it), color = ElectricAmber, fontSize = 11.sp)
                }
            }
            if (plan.note.isNotBlank()) {
                Text(plan.note, color = TextSecondaryDark, fontSize = 10.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (!plan.completed) {
                    TextButton(onClick = onAi) { Text("AI plan", fontSize = 11.sp) }
                    TextButton(onClick = onExpense) { Text("+ Expense", fontSize = 11.sp) }
                    TextButton(onClick = onDone) { Text("Done", fontSize = 11.sp) }
                } else {
                    TextButton(onClick = onDone) { Text("Reopen", fontSize = 11.sp) }
                }
                TextButton(onClick = onEdit) { Text("Edit", fontSize = 11.sp) }
                TextButton(onClick = onDelete) { Text("Delete", fontSize = 11.sp, color = WarningRed) }
            }
        }
    }
}

@Composable
private fun TripDialog(
    initial: TripPlanCodec.TripPlan?,
    onDismiss: () -> Unit,
    onSave: (TripPlanCodec.TripPlan) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var from by remember { mutableStateOf(initial?.from ?: "") }
    var to by remember { mutableStateOf(initial?.to ?: "") }
    var distance by remember { mutableStateOf(initial?.distanceKm?.let { String.format("%.0f", it) } ?: "") }
    var budget by remember { mutableStateOf(initial?.budget?.let { String.format("%.0f", it) } ?: "") }
    var note by remember { mutableStateOf(initial?.note ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Plan a trip" else "Edit trip") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Trip name") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = from, onValueChange = { from = it }, label = { Text("From") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = to, onValueChange = { to = it }, label = { Text("To") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = distance, onValueChange = { distance = it }, label = { Text("Distance km") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = budget, onValueChange = { budget = it }, label = { Text("Budget (optional)") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Notes") }, singleLine = true)
                Text(
                    "Quick trips: name + distance is enough. Road trips: add budget for the over/under check.",
                    fontSize = 10.sp, color = TextSecondaryDark
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val km = distance.toDoubleOrNull()
                    if (name.isNotBlank() && km != null && km > 0) {
                        onSave(
                            TripPlanCodec.TripPlan(
                                idMs = initial?.idMs ?: System.currentTimeMillis(),
                                name = name.trim(),
                                from = from.trim().ifBlank { "Here" },
                                to = to.trim().ifBlank { "There" },
                                distanceKm = km,
                                dateMs = initial?.dateMs ?: System.currentTimeMillis(),
                                budget = budget.toDoubleOrNull(),
                                note = note.trim(),
                                completed = initial?.completed ?: false
                            )
                        )
                    }
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun offlinePlan(plan: TripPlanCodec.TripPlan): String = buildString {
    append("Offline planner (no Gemini key):\n")
    val breaks = (plan.distanceKm / 120.0).toInt()
    append("- Depart early morning to beat traffic; ${plan.distanceKm / 45.0} h driving expected at 45 km/h average.\n")
    append("- Take ${if (breaks < 1) 1 else breaks} rest break(s) (~every 2 h / 120 km).\n")
    append("- Fuel: ~${plan.distanceKm / 15.0} L at 15 km/L; fill before leaving the city, highway pumps are pricier.\n")
    plan.budget?.let { append("- Budget $it: reserve ~10% for tolls and snacks.\n") }
    append("- Safety: check tyre pressures (32-33 psi cold), coolant level and washer fluid before departure.")
}
