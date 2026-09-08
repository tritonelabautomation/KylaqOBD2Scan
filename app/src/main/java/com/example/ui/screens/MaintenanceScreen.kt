package com.example.ui.screens

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
import com.example.data.MaintenanceCatalog
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
 * VehIQ-style vehicle health board for the Kylaq: the Indian-market EA211 service catalogue with
 * due states computed from logged services, the current odometer and today's date. Fully offline —
 * no account, no subscription, no "unlock full report" paywall.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaintenanceScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val repo = viewModel.maintenanceRepository
    var refresh by remember { mutableStateOf(0) }
    val states = remember(refresh) { repo.dueStates() }
    var odoText by remember { mutableStateOf(repo.currentOdometerKm()?.let { String.format("%.0f", it) } ?: "") }
    var logTarget by remember { mutableStateOf<MaintenanceCatalog.ServiceItem?>(null) }
    var pickItem by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (viewModel.takeQuickAdd("service")) pickItem = true }

    val counts = states.groupingBy { it.status }.eachCount()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Maintenance & Health", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = CyberCyan)
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CountChip("${counts[MaintenanceCatalog.DueStatus.OVERDUE] ?: 0} overdue", WarningRed, Modifier.weight(1f))
                    CountChip("${counts[MaintenanceCatalog.DueStatus.DUE_SOON] ?: 0} due soon", ElectricAmber, Modifier.weight(1f))
                    CountChip("${counts[MaintenanceCatalog.DueStatus.GOOD] ?: 0} good", NeonEmerald, Modifier.weight(1f))
                    CountChip("${counts[MaintenanceCatalog.DueStatus.UNKNOWN] ?: 0} unknown", TextSecondaryDark, Modifier.weight(1f))
                }
            }
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Current odometer", color = TextSecondaryDark, fontSize = 11.sp)
                            Text(
                                "Distance-based dues are measured against this reading.",
                                color = TextSecondaryDark, fontSize = 10.sp
                            )
                        }
                        OutlinedTextField(
                            value = odoText,
                            onValueChange = { odoText = it },
                            singleLine = true,
                            modifier = Modifier.width(120.dp),
                            label = { Text("km") }
                        )
                        TextButton(
                            onClick = {
                                odoText.toDoubleOrNull()?.let {
                                    repo.setCurrentOdometerKm(it)
                                    refresh++
                                }
                            }
                        ) { Text("Save") }
                    }
                }
            }
            items(states, key = { it.item.id }) { state ->
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(state.item.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                buildString {
                                    append(state.item.category)
                                    append(" · every ")
                                    append(String.format("%.0f", state.item.intervalKm / 1000.0))
                                    append("k km")
                                    if (state.item.intervalDays > 0) {
                                        append(" / ")
                                        if (state.item.intervalDays >= 365) {
                                            append("${state.item.intervalDays / 365} y")
                                        } else {
                                            append("${state.item.intervalDays} d")
                                        }
                                    }
                                    state.last?.let { append(" · last ${it.dateUtc.take(10)}") }
                                },
                                color = TextSecondaryDark, fontSize = 10.sp
                            )
                            Text(
                                state.headline,
                                color = when (state.status) {
                                    MaintenanceCatalog.DueStatus.OVERDUE -> WarningRed
                                    MaintenanceCatalog.DueStatus.DUE_SOON -> ElectricAmber
                                    MaintenanceCatalog.DueStatus.GOOD -> NeonEmerald
                                    MaintenanceCatalog.DueStatus.UNKNOWN -> TextSecondaryDark
                                },
                                fontSize = 11.sp, fontWeight = FontWeight.Bold
                            )
                        }
                        TextButton(onClick = { logTarget = state.item }) { Text("Log") }
                    }
                }
            }
            item {
                Text(
                    "Intervals follow the Indian-market Škoda Kylaq 1.0 TSI (EA211) service plan. " +
                        "Logging a service with an odometer reading keeps every distance-based due " +
                        "date honest; time-based items age from the logged date.",
                    color = TextSecondaryDark, fontSize = 10.sp
                )
            }
        }
    }

    if (pickItem) {
        AlertDialog(
            onDismissRequest = { pickItem = false },
            title = { Text("Log which service?") },
            text = {
                Column {
                    MaintenanceCatalog.KYLAQ_ITEMS.forEach { item ->
                        TextButton(onClick = { pickItem = false; logTarget = item }) {
                            Text(item.label, fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickItem = false }) { Text("Cancel") } }
        )
    }

    logTarget?.let { item ->
        ServiceDialog(
            item = item,
            defaultOdo = odoText,
            onDismiss = { logTarget = null },
            onSave = { odo, cost ->
                val now = System.currentTimeMillis()
                val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .format(Date(now))
                repo.log(
                    MaintenanceCatalog.ServiceLog(
                        itemId = item.id, dateUtc = utc, dateMs = now,
                        odometerKm = odo, cost = cost
                    )
                )
                odo?.let { repo.setCurrentOdometerKm(it); odoText = String.format("%.0f", it) }
                logTarget = null
                refresh++
            }
        )
    }
}

@Composable
private fun CountChip(label: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
    ) {
        Text(
            label,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            maxLines = 1
        )
    }
}

@Composable
private fun ServiceDialog(
    item: MaintenanceCatalog.ServiceItem,
    defaultOdo: String,
    onDismiss: () -> Unit,
    onSave: (Double?, Double?) -> Unit
) {
    var odo by remember { mutableStateOf(defaultOdo) }
    var cost by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log service: ${item.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Recorded now (${SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date())}).", fontSize = 11.sp, color = TextSecondaryDark)
                OutlinedTextField(value = odo, onValueChange = { odo = it }, label = { Text("Odometer km") }, singleLine = true)
                OutlinedTextField(value = cost, onValueChange = { cost = it }, label = { Text("Cost ₹ (optional)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(odo.toDoubleOrNull(), cost.toDoubleOrNull()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
