package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.GeminiTextClient
import com.example.analysis.DriveAnalytics
import com.example.data.FuelStats
import com.example.data.MaintenanceCatalog
import com.example.ui.theme.DarkCanvas
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.TextPrimaryDark
import com.example.ui.theme.TextSecondaryDark
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/**
 * Coach Chat - VehIQ's AI chat without the paywall: grounded in YOUR car's data
 * (live drive snapshot, fuel stats, maintenance dues, coast & tank history) with an on-device
 * rule-based answerer when no Gemini key / no network, so the screen always responds.
 */
private data class ChatMsg(val fromUser: Boolean, val text: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachChatScreen(viewModel: MainViewModel) {
    val scope = rememberCoroutineScope()

    val snapshot by viewModel.obdScheduler.driveAnalytics.snapshot.collectAsState()
    val coastCount = remember { viewModel.coastHistory().size }
    val tankCount = remember { viewModel.tankHistory().size }
    val fuelStats = remember { viewModel.fuelLogRepository.stats() }
    val dues = remember {
        viewModel.maintenanceRepository.dueStates()
            .filter { it.status == MaintenanceCatalog.DueStatus.OVERDUE || it.status == MaintenanceCatalog.DueStatus.DUE_SOON }
    }

    val aiAvailable = remember { GeminiTextClient.isConfigured() }
    val messages = remember { mutableStateListOf(ChatMsg(false, buildWelcome(snapshot, coastCount, dues.size))) }
    var input by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = DarkCanvas,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Coach Chat",
                        color = TextPrimaryDark,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(12.dp)
                .fillMaxSize()
        ) {
            Text(
                text = if (aiAvailable) "Grounded in your car's data - Gemini AI" else "Offline coach - rule-based answers from your own data",
                fontSize = 11.sp,
                color = if (aiAvailable) NeonEmerald else TextSecondaryDark,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { msg ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = if (msg.fromUser) Alignment.CenterEnd else Alignment.CenterStart
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (msg.fromUser) DarkSurface else NeonEmerald.copy(alpha = 0.10f),
                            border = BorderStroke(
                                1.dp,
                                if (msg.fromUser) TextSecondaryDark.copy(alpha = 0.25f) else NeonEmerald.copy(alpha = 0.4f)
                            )
                        ) {
                            Text(
                                text = msg.text,
                                fontSize = 13.sp,
                                color = TextPrimaryDark,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
                if (thinking) {
                    item {
                        Text(
                            text = "thinking...",
                            fontSize = 11.sp,
                            color = TextSecondaryDark,
                            modifier = Modifier.padding(4.dp)
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Ask about your car") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimaryDark,
                        unfocusedTextColor = TextPrimaryDark,
                        focusedLabelColor = NeonEmerald,
                        unfocusedLabelColor = TextSecondaryDark,
                        focusedBorderColor = NeonEmerald,
                        unfocusedBorderColor = TextSecondaryDark
                    )
                )
                IconButton(
                    onClick = {
                        val q = input.trim()
                        if (q.isEmpty() || thinking) return@IconButton
                        input = ""
                        messages.add(ChatMsg(true, q))
                        thinking = true
                        val grounded = buildContext(snapshot, fuelStats, dues, coastCount, tankCount)
                        scope.launch {
                            val ai = GeminiTextClient.generate(
                                "You are the fleet coach for a Skoda Kylaq 1.0 TSI (EA211 evo2, 85 kW, 178 Nm, 6MT). " +
                                    "Answer strictly from the grounded data provided. Be concrete, metric, max 5 sentences. " +
                                    "If the data is insufficient, say exactly what to log next.",
                                grounded + "\n\nOwner question: " + q
                            )
                            messages.add(ChatMsg(false, ai ?: offlineAnswer(q, snapshot, coastCount, dues, fuelStats)))
                            thinking = false
                        }
                    },
                    enabled = !thinking
                ) {
                    Icon(
                        Icons.Default.Send,
                        contentDescription = "Send",
                        tint = if (thinking) TextSecondaryDark else NeonEmerald
                    )
                }
            }
        }
    }
}

private fun buildWelcome(
    snapshot: DriveAnalytics.DriveSnapshot,
    coastCount: Int,
    dueCount: Int
): String = buildString {
    append("Hi! I'm your Kylaq coach. ")
    if (snapshot.coast.totalKm > 0) {
        append("This drive: %.1f km coasted across %d events. ".format(snapshot.coast.totalKm, snapshot.coast.eventCount))
    }
    if (snapshot.sweetSpotKmh != null) {
        append("Your sweet spot so far: %.0f km/h at %.1f km/L. ".format(snapshot.sweetSpotKmh, snapshot.sweetSpotKmL ?: Double.NaN))
    }
    append("$coastCount coast sessions and $dueCount maintenance item(s) on the due list. Ask me about your sweet spot, coasting, X95 vs normal, turbo habits or costs.")
}

private fun buildContext(
    snapshot: DriveAnalytics.DriveSnapshot,
    fuelStats: FuelStats?,
    dues: List<MaintenanceCatalog.DueState>,
    coastCount: Int,
    tankCount: Int
): String = buildString {
    append("GROUNDED CAR DATA:\n")
    if (snapshot.sweetSpotKmh != null) {
        append("Efficiency sweet spot: %.0f km/h, %.1f km/L. Best measured: %s km/h at %.1f km/L.\n".format(
            snapshot.sweetSpotKmh,
            snapshot.sweetSpotKmL ?: Double.NaN,
            snapshot.bestMeasuredSpeedKmh?.toString() ?: "n/a",
            snapshot.bestMeasuredKmL ?: Double.NaN
        ))
    } else {
        append("No sweet spot computed yet (needs a live drive).\n")
    }
    append("Coasting: %.1f km across %d events, saved ~%.2f L vs cruise-fuel.\n".format(
        snapshot.coast.totalKm, snapshot.coast.eventCount, snapshot.coast.totalSavedVsCruiseL
    ))
    snapshot.turbo?.let {
        append("Turbo: boost now %s kPa, peak %.0f kPa, over-boost events %d, avg lag %s ms.\n".format(
            it.boostKpa?.let { b -> "%.0f".format(b) } ?: "n/a",
            it.peakBoostKpa,
            it.overBoostEvents,
            it.averageLagMs?.toString() ?: "n/a"
        ))
    }
    append("Harsh accel/brake events: %d/%d. Idle: %.0f s.\n".format(snapshot.harshAccelCount, snapshot.harshBrakeCount, snapshot.idleSeconds))
    if (snapshot.fuelComparisonNote != null) append("X95 comparison: ${snapshot.fuelComparisonNote}\n")
    if (fuelStats != null && fuelStats.entryCount > 0) {
        append("Fuel log: %d entries, lifetime %.1f km/L, cost/km Rs %.2f, last price Rs %.1f/L, 30d spend Rs %.0f.\n".format(
            fuelStats.entryCount,
            fuelStats.avgKmPerL ?: Double.NaN,
            fuelStats.costPerKm ?: Double.NaN,
            fuelStats.lastPricePerL ?: Double.NaN,
            fuelStats.cost30d ?: Double.NaN
        ))
    }
    append("Saved coast sessions: $coastCount. Saved tank codes (grade-tagged): $tankCount.\n")
    if (dues.isNotEmpty()) {
        append("Maintenance due: " + dues.take(5).joinToString("; ") { "${it.item.label} (${it.headline})" } + "\n")
    }
}

private fun offlineAnswer(
    question: String,
    snapshot: DriveAnalytics.DriveSnapshot,
    coastCount: Int,
    dues: List<MaintenanceCatalog.DueState>,
    fuelStats: FuelStats?
): String {
    val q = question.lowercase()
    return when {
        q.contains("sweet spot") || q.contains("efficient") || q.contains("best speed") ->
            if (snapshot.sweetSpotKmh != null) {
                "Your data says %.0f km/h is your sweet spot: %.1f km/L there. Best measured speed band was %s km/h at %.1f km/L. Hold that band on your 32 km route and beat it next drive."
                    .format(snapshot.sweetSpotKmh, snapshot.sweetSpotKmL ?: Double.NaN, snapshot.bestMeasuredSpeedKmh ?: 0, snapshot.bestMeasuredKmL ?: Double.NaN)
            } else "No live-drive sweet spot yet - drive 10+ minutes with the scanner connected and the Efficiency tab computes it from your own fuel-vs-speed curve."

        q.contains("coast") ->
            if (snapshot.coast.totalKm > 0 || coastCount > 0) {
                "You coasted %.1f km across %d events this drive (%d saved sessions), sparing ~%.2f L versus cruising fuel. Neutral coasting between 20-80 km/h beats clutch-riding in traffic."
                    .format(snapshot.coast.totalKm, snapshot.coast.eventCount, coastCount, snapshot.coast.totalSavedVsCruiseL)
            } else "No coast data yet. Drive with the scanner on - neutral-coast segments between 20-130 km/h with no pedals are captured and saved automatically."

        q.contains("x95") || q.contains("premium") ->
            snapshot.fuelComparisonNote
                ?: "Tag every fill (X95 vs normal) in Fuel & Costs. After two grade-tagged tanks on similar routes, the app computes a real km/L comparison - no guesses."

        q.contains("turbo") || q.contains("boost") ->
            snapshot.turbo?.let {
                "Turbo right now: %s kPa boost, peak %.0f kPa, %d over-boost events, average lag %s ms. The 1.0 TSI makes 178 Nm from 1750 rpm - short cooldown idles after hard runs keep the CHRA happy."
                    .format(it.boostKpa?.let { b -> "%.0f".format(b) } ?: "n/a", it.peakBoostKpa, it.overBoostEvents, it.averageLagMs?.toString() ?: "n/a")
            } ?: "No turbo data yet - boost = MAP minus barometric. Connect the scanner and drive; lag, peak boost and over-boost events appear here."

        q.contains("service") || q.contains("due") || q.contains("maintenance") ->
            if (dues.isNotEmpty()) "On the due list: " + dues.take(4).joinToString(", ") { "${it.item.label} (${it.headline})" } + ". Open the Reminders hub to log or snooze."
            else "Nothing is due right now. Your 15k km / 1-year oil intervals suit the 1.0 TSI; check the Maintenance screen after long-life oil changes."

        q.contains("cost") || q.contains("spend") || q.contains("fuel log") || q.contains("money") ->
            if (fuelStats != null && fuelStats.entryCount > 0) {
                "Lifetime %.1f km/L across %d logged fills, running cost Rs %.2f/km, last price Rs %.1f/L, last 30 days Rs %.0f. Reports has the monthly trend and budget check."
                    .format(fuelStats.avgKmPerL ?: Double.NaN, fuelStats.entryCount, fuelStats.costPerKm ?: Double.NaN, fuelStats.lastPricePerL ?: Double.NaN, fuelStats.cost30d ?: Double.NaN)
            } else "Log your first tank in Fuel & Costs (odometer + litres + price) and cost-per-km appears here."

        q.contains("harsh") || q.contains("brake") || q.contains("accel") ->
            "This drive: %d harsh accelerations, %d harsh brakes, %.0f s idling. Smooth inputs are worth more than any fuel brand on the 1.0 TSI.".format(snapshot.harshAccelCount, snapshot.harshBrakeCount, snapshot.idleSeconds)

        else -> "Offline coach here (no Gemini key configured). Ask about: sweet spot, coasting, X95 vs normal, turbo health, harsh events, maintenance dues or fuel costs - every answer comes from your own recorded data."
    }
}
