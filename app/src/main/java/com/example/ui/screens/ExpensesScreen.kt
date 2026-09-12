package com.example.ui.screens

import android.content.Intent
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ExpenseCodec
import com.example.data.MaintenanceCatalog
import com.example.ui.components.XyPlot
import com.example.ui.components.XySeries
import com.example.ui.components.rememberVoiceLauncher
import com.example.ui.components.voiceIntent
import com.example.data.VoiceParse
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.ElectricAmber
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.ResearchPurple
import com.example.ui.theme.TextSecondaryDark
import com.example.ui.theme.WarningRed
import com.example.ui.viewmodel.MainViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Unified cost timeline (VehIQ Expenses + Fuelio cost log/stats/charts): fuel fill-ups, service
 * logs and general expenses in one chronological feed with category filters, monthly cost chart
 * and CSV export. Everything is local; nothing is paywalled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpensesScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    var filter by remember { mutableStateOf("All") }
    var showAdd by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val cur by viewModel.settingsRepository.currencySymbol.collectAsState()

    LaunchedEffect(Unit) { if (viewModel.takeQuickAdd("expense")) showAdd = true }

    val fuel = remember(refresh) { viewModel.fuelLogRepository.entries() }
    val services = remember(refresh) { viewModel.maintenanceRepository.logs() }
    val expenses = remember(refresh) { viewModel.expenseRepository.entries() }

    data class Row(val dateUtc: String, val sortKey: Long, val label: String, val amount: Double, val kind: String, val idMs: Long)

    val rows = remember(fuel, services, expenses, filter) {
        val all = mutableListOf<Row>()
        fuel.forEach { all.add(Row(it.dateUtc, it.idMs, "Fuel · ${it.liters} L @ ${it.grade}", it.totalCost, "Fuel", it.idMs)) }
        services.forEach { s ->
            val label = MaintenanceCatalog.KYLAQ_ITEMS.firstOrNull { it.id == s.itemId }?.label ?: s.itemId
            all.add(Row(s.dateUtc, s.dateMs, "Service · $label", s.cost ?: 0.0, "Service", s.dateMs))
        }
        expenses.forEach { all.add(Row(it.dateUtc, it.idMs, "${it.category} · ${it.vendor}", it.amount, "Other", it.idMs)) }
        val filtered = when (filter) {
            "Fuel" -> all.filter { it.kind == "Fuel" }
            "Service" -> all.filter { it.kind == "Service" }
            "Other" -> all.filter { it.kind == "Other" }
            else -> all
        }
        filtered.sortedByDescending { it.sortKey }
    }

    val monthly = remember(rows) {
        val cal = Calendar.getInstance()
        val buckets = sortedMapOf<String, Double>()
        rows.forEach { r ->
            cal.time = Date(r.sortKey)
            val key = String.format("%04d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
            buckets[key] = (buckets[key] ?: 0.0) + r.amount
        }
        buckets.toList().takeLast(8)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Expenses", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = CyberCyan) }
                },
                actions = {
                    IconButton(onClick = {
                        val sb = StringBuilder("date,kind,detail,amount_inr\n")
                        rows.forEach { sb.append(it.dateUtc).append(',').append(it.kind).append(',')
                            .append(it.label.replace(',', ' ')).append(',').append(String.format("%.2f", it.amount)).append('\n') }
                        val out = File(context.cacheDir, "kylaq-expenses.csv")
                        out.writeText(sb.toString())
                        val downloads = File(context.getExternalFilesDir(null), "Downloads").apply { mkdirs() }
                        val copy = File(downloads, "kylaq-expenses.csv")
                        out.copyTo(copy, overwrite = true)
                        status = "CSV saved: ${copy.absolutePath}"
                    }) { Icon(Icons.Default.Download, "Export CSV", tint = NeonEmerald) }
                    IconButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, "Add expense", tint = NeonEmerald) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("All", "Fuel", "Service", "Other").forEach { f ->
                        FilterChip(
                            selected = filter == f,
                            onClick = { filter = f },
                            label = { Text(f, fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            item {
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Monthly spend (last ${monthly.size} months)", color = TextSecondaryDark, fontSize = 12.sp)
                        if (monthly.size >= 2) {
                            XyPlot(
                                series = listOf(
                                    XySeries(
                                        "$cur/month", ElectricAmber,
                                        monthly.mapIndexed { i, p -> i.toFloat() to p.second.toFloat() }
                                    )
                                ),
                                xLabel = "month →",
                                yLabel = cur,
                                height = 150.dp
                            )
                        } else {
                            Text("Log a few entries to draw the spend trend.", color = TextSecondaryDark, fontSize = 11.sp)
                        }
                    }
                }
            }
            item { status?.let { Text(it, color = NeonEmerald, fontSize = 11.sp) } }
            item { Text("TIMELINE (${rows.size})", color = TextSecondaryDark, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            items(rows, key = { "${it.kind}-${it.idMs}" }) { r ->
                Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            when (r.kind) {
                                "Fuel" -> Icons.Default.LocalGasStation
                                "Service" -> Icons.Default.Build
                                else -> Icons.Default.ReceiptLong
                            },
                            null,
                            tint = when (r.kind) {
                                "Fuel" -> NeonEmerald
                                "Service" -> CyberCyan
                                else -> ResearchPurple
                            },
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.label, color = Color.White, fontSize = 12.sp)
                            Text(r.dateUtc.take(10), color = TextSecondaryDark, fontSize = 10.sp)
                        }
                        Text(
                            if (r.amount > 0) String.format("%s%.0f", cur, r.amount) else "--",
                            color = ElectricAmber, fontSize = 13.sp, fontWeight = FontWeight.Bold
                        )
                        if (r.kind == "Other") {
                            IconButton(onClick = { viewModel.expenseRepository.delete(r.idMs); refresh++ }) {
                                Icon(Icons.Default.Delete, "Delete", tint = WarningRed, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        ExpenseDialog(
            onDismiss = { showAdd = false },
            onSave = { category, amount, vendor, note ->
                val now = System.currentTimeMillis()
                val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(now))
                viewModel.expenseRepository.add(
                    ExpenseCodec.ExpenseEntry(now, utc, category, amount, vendor, note)
                )
                refresh++
                showAdd = false
            }
        )
    }
}

@Composable
private fun ExpenseDialog(
    onDismiss: () -> Unit,
    onSave: (String, Double, String, String) -> Unit
) {
    var category by remember { mutableStateOf(ExpenseCodec.CATEGORIES.last()) }
    var amount by remember { mutableStateOf("") }
    var vendor by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val voice = rememberVoiceLauncher { transcript ->
        note = transcript
        VoiceParse.amount(transcript)?.let { amount = String.format("%.0f", it) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Add expense", modifier = Modifier.weight(1f))
                IconButton(onClick = { voice.launch(voiceIntent()) }) {
                    Icon(Icons.Default.Mic, "Voice entry", tint = NeonEmerald)
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ExpenseCodec.CATEGORIES.take(4).forEach { c ->
                        FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c, fontSize = 10.sp) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ExpenseCodec.CATEGORIES.drop(4).forEach { c ->
                        FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c, fontSize = 10.sp) })
                    }
                }
                OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("Amount ₹") }, singleLine = true)
                OutlinedTextField(value = vendor, onValueChange = { vendor = it }, label = { Text("Vendor (optional)") }, singleLine = true)
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note / voice transcript") })
                Text("Mic: say \"paid 150 for car wash at Express Wash\".", fontSize = 10.sp, color = TextSecondaryDark)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                amount.toDoubleOrNull()?.let { onSave(category, it, vendor.trim(), note.trim()) }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
