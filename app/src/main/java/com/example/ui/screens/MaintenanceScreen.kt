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
    var query by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("All") }
    var showTrackDialog by remember { mutableStateOf(false) }
    var whyTarget by remember { mutableStateOf<MaintenanceCatalog.DueState?>(null) }
    var intervalTarget by remember { mutableStateOf<MaintenanceCatalog.ServiceItem?>(null) }

    LaunchedEffect(Unit) { if (viewModel.takeQuickAdd("service")) pickItem = true }

    val tracked = remember(refresh) { repo.trackedItemIds() }
    val logs = remember(refresh) { repo.logs() }
    val counts = states.groupingBy { it.status }.eachCount()
    val filtered = states.filter { st ->
        (tracked.isEmpty() || st.item.id in tracked) &&
            (query.isBlank() || st.item.label.contains(query, true) || st.item.category.contains(query, true)) &&
            (statusFilter == "All" || st.status.name == statusFilter)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Maintenance & Health", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = CyberCyan)
                    }
                },
                actions = {
                    IconButton(onClick = { showTrackDialog = true }) {
                        Icon(Icons.Default.Tune, contentDescription = "Items I track", tint = CyberCyan)
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search oil, brakes, tyres...", fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondaryDark) }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("All", "OVERDUE", "DUE_SOON", "GOOD").forEach { f ->
                            FilterChip(
                                selected = statusFilter == f,
                                onClick = { statusFilter = f },
                                label = {
                                    Text(
                                        when (f) {
                                            "OVERDUE" -> "Overdue"
                                            "DUE_SOON" -> "Due soon"
                                            "GOOD" -> "Good"
                                            else -> "All"
                                        },
                                        fontSize = 11.sp
                                    )
                                }
                            )
                        }
                    }
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
            items(filtered, key = { it.item.id }) { state ->
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
                            // Interval usage: how much of the km window is consumed (VehIQ progress bars).
                            state.kmRemaining?.let { rem ->
                                val progress = (1.0 - rem / state.item.intervalKm).toFloat().coerceIn(0f, 1f)
                                Spacer(Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.fillMaxWidth().height(4.dp),
                                    color = when {
                                        progress >= 1f -> WarningRed
                                        progress >= 0.9f -> ElectricAmber
                                        else -> NeonEmerald
                                    },
                                    trackColor = TextSecondaryDark.copy(alpha = 0.2f)
                                )
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            TextButton(onClick = { logTarget = state.item }) { Text("Log") }
                            TextButton(onClick = { intervalTarget = state.item }) {
                                Text("Interval", fontSize = 11.sp, color = TextSecondaryDark)
                            }
                            if (state.status == MaintenanceCatalog.DueStatus.OVERDUE ||
                                state.status == MaintenanceCatalog.DueStatus.DUE_SOON
                            ) {
                                TextButton(onClick = { whyTarget = state }) {
                                    Text("Why?", fontSize = 11.sp, color = TextSecondaryDark)
                                }
                            }
                        }
                    }
                }
            }
            if (logs.isNotEmpty()) {
                item {
                    Text("Recent services", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                items(logs.take(8), key = { "${it.itemId}_${it.dateMs}" }) { l ->
                    val label = MaintenanceCatalog.KYLAQ_ITEMS.firstOrNull { it.id == l.itemId }?.label ?: l.itemId
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(label, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                if (l.rating != null) {
                                    Text("★".repeat(l.rating), color = ElectricAmber, fontSize = 12.sp)
                                }
                                Text(l.dateUtc.take(10), color = TextSecondaryDark, fontSize = 10.sp)
                            }
                            Text(
                                buildString {
                                    l.odometerKm?.let { append(String.format("%.0f km", it)); append(" · ") }
                                    l.cost?.let { append(String.format("₹%.0f", it)) }
                                    if (l.notes.isNotBlank()) {
                                        if (isNotEmpty()) append(" · ")
                                        append(l.notes)
                                    }
                                },
                                color = TextSecondaryDark, fontSize = 10.sp
                            )
                        }
                    }
                }
            }
            item {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Parts guide - 1.0 TSI EA211 evo2", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("\u2022 Engine oil: 5W-30/5W-40 meeting VW 504 00 / 502 00, ~4.0-4.3 L with filter", color = TextSecondaryDark, fontSize = 11.sp)
                        Text("\u2022 Oil filter: spin-on canister, change with every oil service", color = TextSecondaryDark, fontSize = 11.sp)
                        Text("\u2022 Air filter: panel element, inspect at 15k, replace 30k (15k in dusty duty)", color = TextSecondaryDark, fontSize = 11.sp)
                        Text("\u2022 Cabin filter: activated-carbon type, yearly before monsoon", color = TextSecondaryDark, fontSize = 11.sp)
                        Text("\u2022 Spark plugs: iridium, gap ~0.7-0.8 mm, first at 60k then every 40-60k", color = TextSecondaryDark, fontSize = 11.sp)
                        Text("\u2022 Coolant: G12evo (pink/violet), never mix green G11; check ratio at 30k", color = TextSecondaryDark, fontSize = 11.sp)
                        Text("\u2022 Brake fluid: DOT 4 (Class 6 for ABS/ESP), every 2 years", color = TextSecondaryDark, fontSize = 11.sp)
                        Text("\u2022 Front pads: wear sensor wired; budget sets ~40k km city duty", color = TextSecondaryDark, fontSize = 11.sp)
                        Text("\u2022 Gearbox AQ250-6F (09G, Aisin TF-60SN): ATF G 052 025 A2 (JWS 3309) only, 7.0 L lifetime fill - never mix other ATF", color = TextSecondaryDark, fontSize = 11.sp)
                        Text("\u2022 09G ratios 4.148/2.370/1.556/1.155/0.859/0.686 (spread 6.05): long 6th is your highway economy gear", color = TextSecondaryDark, fontSize = 11.sp)
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

    whyTarget?.let { st ->
        val odo = repo.currentOdometerKm()
        AlertDialog(
            onDismissRequest = { whyTarget = null },
            title = { Text("Why is this flagged?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(st.item.label, fontWeight = FontWeight.SemiBold)
                    Text(
                        st.last?.let {
                            "Last logged ${it.dateUtc.take(10)}" +
                                (it.odometerKm?.let { o -> " at ${String.format("%.0f", o)} km" } ?: " (no odometer)")
                        } ?: "Never logged - the item starts UNKNOWN and ages from first tracking.",
                        fontSize = 12.sp, color = TextSecondaryDark
                    )
                    Text(
                        "Interval: ${String.format("%.0f", st.item.intervalKm / 1000.0)}k km" +
                            (if (st.item.intervalDays > 0) " / ${st.item.intervalDays} days" else "") +
                            ". Current odometer: ${odo?.let { String.format("%.0f km", it) } ?: "not set"}.",
                        fontSize = 12.sp, color = TextSecondaryDark
                    )
                    st.kmRemaining?.let {
                        Text(
                            if (it < 0) "You are ${String.format("%.0f", -it)} km PAST the distance deadline."
                            else "${String.format("%.0f", it)} km left before the distance deadline.",
                            fontSize = 12.sp,
                            color = if (it < 0) WarningRed else NeonEmerald
                        )
                    }
                    st.daysRemaining?.let {
                        Text(
                            if (it < 0) "You are ${-it} days PAST the time deadline."
                            else "$it days left before the time deadline.",
                            fontSize = 12.sp,
                            color = if (it < 0) WarningRed else NeonEmerald
                        )
                    }
                    Text(
                        if (st.status == MaintenanceCatalog.DueStatus.OVERDUE) {
                            "Either limit crossed -> OVERDUE. Log the service to reset both counters."
                        } else {
                            "A limit is close (<=1500 km or <=30 days) -> DUE_SOON."
                        },
                        fontSize = 11.sp, color = TextSecondaryDark
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { whyTarget = null; logTarget = st.item }) { Text("Log it now") }
            },
            dismissButton = { TextButton(onClick = { whyTarget = null }) { Text("Close") } }
        )
    }

    if (showTrackDialog) {
        AlertDialog(
            onDismissRequest = { showTrackDialog = false },
            title = { Text("Items I track") },
            text = {
                Column {
                    Text(
                        if (tracked.isEmpty()) "Tracking all items. Tick to narrow the board." else "${tracked.size} item(s) selected.",
                        fontSize = 11.sp, color = TextSecondaryDark
                    )
                    val severe = remember(refresh) { repo.severeConditions() }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Severe conditions", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("Dusty roads, short trips, hot climate: intervals shrink 25% (VW severe-service schedule).", fontSize = 10.sp, color = TextSecondaryDark)
                        }
                        Switch(
                            checked = severe,
                            onCheckedChange = { repo.setSevereConditions(it); refresh++ }
                        )
                    }
                    MaintenanceCatalog.KYLAQ_ITEMS.forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = tracked.isEmpty() || item.id in tracked,
                                onCheckedChange = { on ->
                                    val base = if (tracked.isEmpty()) MaintenanceCatalog.KYLAQ_ITEMS.map { it.id }.toSet() else tracked
                                    val next = if (on) base + item.id else base - item.id
                                    repo.setTrackedItemIds(if (next.size == MaintenanceCatalog.KYLAQ_ITEMS.size) emptySet() else next)
                                    refresh++
                                }
                            )
                            Text(item.label, fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { repo.setTrackedItemIds(emptySet()); refresh++; showTrackDialog = false }) {
                    Text("Track all")
                }
            },
            dismissButton = { TextButton(onClick = { showTrackDialog = false }) { Text("Done") } }
        )
    }

    intervalTarget?.let { item ->
        val current = remember(item.id, refresh) { repo.customIntervals()[item.id] }
        var kmText by remember(item.id) {
            mutableStateOf((current?.first ?: item.intervalKm).let { String.format("%.0f", it) })
        }
        var daysText by remember(item.id) {
            mutableStateOf((current?.second ?: item.intervalDays).let { if (it > 0) it.toString() else "" })
        }
        AlertDialog(
            onDismissRequest = { intervalTarget = null },
            title = { Text("Custom interval: ${item.label}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Catalog: ${String.format("%.0f", item.intervalKm / 1000.0)}k km" +
                            (if (item.intervalDays > 0) " / ${item.intervalDays} days" else "") +
                            (if (current != null) " (overridden)" else ""),
                        fontSize = 11.sp, color = TextSecondaryDark
                    )
                    OutlinedTextField(value = kmText, onValueChange = { kmText = it }, label = { Text("Every km") }, singleLine = true)
                    OutlinedTextField(value = daysText, onValueChange = { daysText = it }, label = { Text("Every days (0 = none)") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    repo.setCustomInterval(item.id, kmText.toDoubleOrNull(), daysText.toIntOrNull() ?: 0)
                    intervalTarget = null
                    refresh++
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        repo.clearCustomInterval(item.id)
                        intervalTarget = null
                        refresh++
                    }) { Text("Reset", color = WarningRed) }
                    TextButton(onClick = { intervalTarget = null }) { Text("Cancel") }
                }
            }
        )
    }

    logTarget?.let { item ->
        ServiceDialog(
            item = item,
            defaultOdo = odoText,
            onDismiss = { logTarget = null },
            onSave = { odo, cost, rating, notes ->
                val now = System.currentTimeMillis()
                val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .format(Date(now))
                repo.log(
                    MaintenanceCatalog.ServiceLog(
                        itemId = item.id, dateUtc = utc, dateMs = now,
                        odometerKm = odo, cost = cost, rating = rating, notes = notes
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
    onSave: (Double?, Double?, Int?, String) -> Unit
) {
    var odo by remember { mutableStateOf(defaultOdo) }
    var cost by remember { mutableStateOf("") }
    var rating by remember { mutableStateOf(0) }
    var notes by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log service: ${item.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Recorded now (${SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date())}).", fontSize = 11.sp, color = TextSecondaryDark)
                OutlinedTextField(value = odo, onValueChange = { odo = it }, label = { Text("Odometer km") }, singleLine = true)
                OutlinedTextField(value = cost, onValueChange = { cost = it }, label = { Text("Cost ₹ (optional)") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Workshop rating", fontSize = 12.sp, color = TextSecondaryDark, modifier = Modifier.weight(1f))
                    (1..5).forEach { n ->
                        IconButton(onClick = { rating = if (rating == n) 0 else n }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = "$n star",
                                tint = if (n <= rating) ElectricAmber else TextSecondaryDark,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Pre-service notes (optional)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(odo.toDoubleOrNull(), cost.toDoubleOrNull(), rating.takeIf { it > 0 }, notes.trim()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
