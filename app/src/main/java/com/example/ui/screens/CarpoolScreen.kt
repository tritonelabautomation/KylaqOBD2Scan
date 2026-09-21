package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.CarpoolCodec
import com.example.ui.theme.ElectricAmber
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.TextPrimaryDark
import com.example.ui.theme.TextSecondaryDark
import com.example.ui.theme.WarningRed
import kotlinx.coroutines.launch

/**
 * Standalone Car Pool journal (owner 2026-09-19: "give me an separate option for car pool
 * logging ... based on date & time input in car pool logging trip can fetch at exact time if any
 * car pool exist it can link to trip"). Rides are logged by their own date and time first - the
 * recorder may have died abruptly, the passengers were aboard anyway - and each ride joins the
 * saved trip whose window covers that instant, or stands alone with its date until one exists
 * (recovered trips relink it automatically at finalize).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarpoolScreen(
    viewModel: com.example.ui.viewmodel.MainViewModel,
    onBack: () -> Unit
) {
    val tick by viewModel.carpoolRepository.changeTick.collectAsState()
    var refresh by remember { mutableStateOf(0) }
    val entries = remember(tick, refresh) {
        viewModel.carpoolEntries().sortedByDescending { CarpoolCodec.whenMs(it) ?: it.idMs }
    }
    var monthly by remember { mutableStateOf<List<CarpoolCodec.MonthRow>>(emptyList()) }
    var tripTitles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val lastBackupMs by viewModel.settingsRepository.lastBackupTimestamp.collectAsState()
    LaunchedEffect(tick, refresh) {
        monthly = viewModel.monthlyCarpool()
        tripTitles = viewModel.tripTitleMap()
    }
    var showAdd by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<CarpoolCodec.CarpoolEntry?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Car Pool") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            Button(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) {
                Text("+ Log car pool")
            }
            Spacer(Modifier.height(12.dp))
            if (entries.isEmpty()) {
                Text(
                    "No car-pool rides logged yet. Log one with its date and time: if a saved " +
                        "trip covers that instant the ride links to it automatically - including " +
                        "trips recovered later after an abrupt stop.",
                    color = TextSecondaryDark, fontSize = 12.sp
                )
            }
            // Owner 2026-09-20: "month wise earning ... not in the top in between trip segregate
            // to that month the log and show that month earning and fuel refill cost saving or
            // net fuel cost for that month". The single top summary is gone: every IST month that
            // holds rides gets its own header card directly above its rides, carrying that
            // month's earned, the fuel cost of its linked trips, and the net fuel cost (or the
            // saving when earnings beat fuel) - the same tested numbers the Reports card shows.
            val monthTotals = monthly.associateBy { it.month }
            entries.groupBy {
                com.example.data.RecordTime.format("yyyy-MM", CarpoolCodec.whenMs(it) ?: it.idMs)
            }.toSortedMap(compareByDescending { it }).forEach { (monthKey, monthEntries) ->
                val tot = monthTotals[monthKey]
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                monthKey,
                                color = TextPrimaryDark, fontWeight = FontWeight.Bold, fontSize = 14.sp
                            )
                            Text(
                                "earned " + String.format(java.util.Locale.US, "₹%.0f", tot?.earned ?: 0.0),
                                color = NeonEmerald, fontWeight = FontWeight.Bold, fontSize = 13.sp
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                "${tot?.trips ?: monthEntries.size} ride(s) · " +
                                    String.format(java.util.Locale.US, "%.0f", tot?.distanceKm ?: 0.0) + " km",
                                color = TextSecondaryDark, fontSize = 11.sp
                            )
                            tot?.fuelCost?.let { fc ->
                                Text(
                                    "fuel " + String.format(java.util.Locale.US, "₹%.0f", fc),
                                    color = TextSecondaryDark, fontSize = 11.sp
                                )
                            } ?: Text("fuel --", color = TextSecondaryDark, fontSize = 11.sp)
                        }
                        tot?.effective?.let { eff ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    if (eff > 0) "net fuel cost" else "saving over fuel",
                                    color = TextSecondaryDark, fontSize = 11.sp
                                )
                                Text(
                                    if (eff > 0) String.format(java.util.Locale.US, "₹%.0f", eff)
                                    else String.format(java.util.Locale.US, "+₹%.0f", -eff),
                                    color = if (eff > 0) ElectricAmber else NeonEmerald,
                                    fontWeight = FontWeight.Bold, fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
                monthEntries.forEach { e ->
                val ms = CarpoolCodec.whenMs(e) ?: e.idMs
                val syncState = com.example.data.BackupSyncStatus.forItem(lastBackupMs, e.idMs)
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    com.example.data.RecordTime.format("yyyy-MM-dd HH:mm", ms),
                                    color = TextPrimaryDark, fontWeight = FontWeight.SemiBold, fontSize = 13.sp
                                )
                                Spacer(Modifier.width(6.dp))
                                val syncIcon = when (syncState) {
                                    com.example.data.BackupSyncStatus.State.SYNCED -> Icons.Default.CheckCircle
                                    com.example.data.BackupSyncStatus.State.PENDING -> Icons.Default.CloudUpload
                                    com.example.data.BackupSyncStatus.State.NEVER -> Icons.Default.Warning
                                }
                                val syncTint = when (syncState) {
                                    com.example.data.BackupSyncStatus.State.SYNCED -> NeonEmerald
                                    com.example.data.BackupSyncStatus.State.PENDING -> ElectricAmber
                                    com.example.data.BackupSyncStatus.State.NEVER -> TextSecondaryDark
                                }
                                Icon(syncIcon, contentDescription = null, tint = syncTint, modifier = Modifier.size(14.dp))
                            }
                            Text(
                                String.format(java.util.Locale.US, "₹%.0f", e.earned),
                                color = NeonEmerald, fontWeight = FontWeight.Bold, fontSize = 14.sp
                            )
                        }
                        Text(
                            String.format(java.util.Locale.US, "%.1f km trip", e.distanceKm) + " · " +
                                e.riders.joinToString(", ") { r ->
                                    val d = r.effectiveDistance(e.distanceKm)
                                    (r.name.ifBlank { "rider" }) + " " +
                                        String.format(java.util.Locale.US, "₹%.0f (%.1f km, ₹%.1f/km)", r.amount, d, if (d > 0.1) r.amount / d else 0.0)
                                },
                            color = TextSecondaryDark, fontSize = 12.sp
                        )
                        e.tripId?.let { id ->
                            Text(
                                "Linked: " + (tripTitles[id] ?: "trip"),
                                color = NeonEmerald, fontSize = 11.sp
                            )
                        } ?: Text(
                            "No saved trip covers this time - standalone.",
                            color = WarningRed, fontSize = 11.sp
                        )
                        Row {
                            TextButton(onClick = { editTarget = e }) { Text("Edit") }
                            TextButton(onClick = { viewModel.deleteCarpool(e.idMs) }) {
                                Text("Delete", color = WarningRed)
                            }
                        }
                    }
                }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (showAdd || editTarget != null) {
        CarpoolDialog(
            existing = editTarget,
            defaultDistanceKm = 0.0,
            defaultWhenMs = System.currentTimeMillis(),
            onDismiss = { showAdd = false; editTarget = null },
            onSave = { d, riders, whenMs ->
                scope.launch {
                    viewModel.saveCarpool(
                        CarpoolCodec.CarpoolEntry(
                            idMs = editTarget?.idMs ?: whenMs,
                            tripId = editTarget?.tripId,
                            dateUtc = com.example.data.RecordTime.stamp(whenMs),
                            distanceKm = d,
                            riders = riders
                        )
                    )
                    showAdd = false
                    editTarget = null
                    refresh++
                }
            }
        )
    }
}
