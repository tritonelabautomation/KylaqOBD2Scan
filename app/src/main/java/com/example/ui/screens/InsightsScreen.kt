package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.analysis.DriveAnalytics
import com.example.analysis.DriveInsightsStore
import com.example.analysis.DrivingCoach
import com.example.engine.CoastNeutralDetector
import com.example.engine.PowertrainModel
import com.example.ui.components.XyPlot
import com.example.ui.components.XySeries
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.ElectricAmber
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.ResearchPurple
import com.example.ui.theme.TextSecondaryDark
import com.example.ui.theme.WarningRed
import com.example.ui.viewmodel.MainViewModel

/**
 * The learning-guide hub: measured power/torque curves against the factory curve, the fuel
 * consumption trend with the efficiency sweet spot, coasting-in-neutral savings, turbo
 * behaviour, per-tank fuel-quality evidence and data-driven coaching tips.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val snapshot by viewModel.obdScheduler.driveAnalytics.snapshot.collectAsState()
    val trip by viewModel.tripEconomy.collectAsState()
    val tips = DrivingCoach.tips(snapshot, trip)

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Drive Insights & Learning Guide") },
            navigationIcon = {
                IconButton(onClick = onBack, modifier = Modifier.testTag("btn_insights_back")) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { SweetSpotCard(snapshot) }
            item { FuelVsSpeedCard(snapshot) }
            item { TorquePowerCard(snapshot) }
            item { TrendCard(snapshot) }
            item { CoastCard(snapshot, viewModel.coastHistory()) }
            item { TurboCard(snapshot) }
            item { TanksCard(snapshot, viewModel.tankHistory()) }
            item { RideXrayCard(viewModel.rideHistory()) }
            item { CoachCard(tips) }
            item { Spacer(modifier = Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun SectionCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, color = CyberCyan, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(subtitle, color = TextSecondaryDark, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))
            content()
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color = NeonEmerald) {
    Column(modifier = Modifier.padding(end = 18.dp)) {
        Text(value, color = color, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(label, color = TextSecondaryDark, fontSize = 10.sp)
    }
}

@Composable
private fun SweetSpotCard(snapshot: DriveAnalytics.DriveSnapshot) {
    SectionCard(
        title = "Your efficiency sweet spot",
        subtitle = "Modelled from your measured top-gear ratio, vehicle mass and the 1.0 TSI " +
            "efficiency map; cross-checked against what you actually burned per speed band."
    ) {
        Row {
            Stat(
                "SWEET SPOT",
                snapshot.sweetSpotKmh?.let { "${it.toInt()} km/h" } ?: "--"
            )
            Stat(
                "AT SWEET SPOT",
                snapshot.sweetSpotKmL?.let { String.format("%.1f km/L", it) } ?: "--"
            )
            Stat(
                "YOUR BEST BAND",
                snapshot.bestMeasuredSpeedKmh?.let { "${it} km/h" } ?: "--",
                color = ElectricAmber
            )
            Stat(
                "MEASURED",
                snapshot.bestMeasuredKmL?.let { String.format("%.1f km/L", it) } ?: "--",
                color = ElectricAmber
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Top-gear ratio learned: " +
                (snapshot.measuredRpmPerKmh?.let { String.format("%.1f rpm per km/h", it) } ?: "still learning…") +
                ". Drag grows with speed squared, so consumption above the sweet spot climbs fast; " +
                "below it, low load and throttle pumping losses win instead.",
            color = TextSecondaryDark,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun FuelVsSpeedCard(snapshot: DriveAnalytics.DriveSnapshot) {
    SectionCard(
        title = "Fuel consumption vs speed",
        subtitle = "Dashed = physical model for your gear ratio. Solid = your measured steady-state " +
            "burn in 10 km/h bands. Amber line marks the sweet spot."
    ) {
        XyPlot(
            series = listOf(
                XySeries(
                    "model L/100km",
                    ResearchPurple,
                    snapshot.fuelVsSpeedModel.map { it.first.toFloat() to it.second.toFloat() },
                    dashed = true
                ),
                XySeries(
                    "measured",
                    NeonEmerald,
                    snapshot.fuelVsSpeedMeasured.map { it.first.toFloat() to it.second.toFloat() }
                )
            ),
            xLabel = "km/h",
            yLabel = "L/100 km",
            markerX = snapshot.sweetSpotKmh?.toFloat(),
            markerLabel = snapshot.sweetSpotKmh?.let { "sweet ${it.toInt()} km/h " }
        )
    }
}

@Composable
private fun TorquePowerCard(snapshot: DriveAnalytics.DriveSnapshot) {
    SectionCard(
        title = "Torque & power vs engine speed",
        subtitle = "Solid = measured (PID 0162 when answered, otherwise recovered from fuel energy " +
            "and vehicle dynamics). Dashed = factory 178 Nm / 85 kW curve of the EA211 evo2."
    ) {
        XyPlot(
            series = listOf(
                XySeries(
                    "measured torque Nm",
                    CyberCyan,
                    snapshot.torqueCurve.map { it.first.toFloat() to it.second.toFloat() }
                ),
                XySeries(
                    "factory torque Nm",
                    ResearchPurple,
                    snapshot.factoryTorqueCurve.map { it.first.toFloat() to it.second.toFloat() },
                    dashed = true
                ),
                XySeries(
                    "power kW",
                    ElectricAmber,
                    snapshot.powerCurve.map { it.first.toFloat() to it.second.toFloat() }
                )
            ),
            xLabel = "rpm",
            yLabel = "Nm / kW"
        )
        Row(modifier = Modifier.padding(top = 6.dp)) {
            Stat(
                "LIVE TORQUE",
                snapshot.measuredTorqueNm?.let { String.format("%.0f Nm", it) } ?: "--"
            )
            Stat(
                "LIVE POWER",
                snapshot.measuredPowerKw?.let { String.format("%.1f kW", it) } ?: "--"
            )
            Stat(
                "DEMAND",
                snapshot.demandedTorqueNm?.let { String.format("%.0f Nm", it) } ?: "--",
                color = ElectricAmber
            )
        }
    }
}

@Composable
private fun TrendCard(snapshot: DriveAnalytics.DriveSnapshot) {
    val recent = snapshot.trend.takeLast(900) // ~15 minutes at 1 Hz
    // Relative seconds keep the x axis inside float precision on long uptimes.
    val t0 = recent.firstOrNull()?.timestampMonotonicMs ?: 0L
    SectionCard(
        title = "Fuel & speed trend (last ~15 min)",
        subtitle = "1 Hz log of what the car actually did: speed, fuel flow and boost. Flat fuel " +
            "with falling speed = fuel-cut coasting; spikes = enrichment under boost."
    ) {
        XyPlot(
            series = listOf(
                XySeries("speed km/h", CyberCyan, recent.map { (it.timestampMonotonicMs - t0) / 1000f to it.speedKmh.toFloat() }),
                XySeries("fuel L/h ×5", NeonEmerald, recent.map { (it.timestampMonotonicMs - t0) / 1000f to ((it.fuelLh ?: 0.0) * 5).toFloat() }),
                XySeries("boost kPa", ElectricAmber, recent.map { (it.timestampMonotonicMs - t0) / 1000f to (it.boostKpa ?: 0.0).toFloat() })
            ),
            xLabel = "time →",
            yLabel = "mixed units",
            height = 160.dp
        )
    }
}

@Composable
private fun CoastCard(
    snapshot: DriveAnalytics.DriveSnapshot,
    history: List<DriveInsightsStore.CoastLogEntry> = emptyList()
) {
    val coast = snapshot.coast
    SectionCard(
        title = "Driving in neutral / coasting",
        subtitle = "Window from the owner's manual: 20-130 km/h, selector in D, neither pedal " +
            "depressed. Fuel-cut coast (gear in, injectors shut) vs idle coast (drivetrain " +
            "disengaged, engine at idle)."
    ) {
        Row {
            Stat("COAST EVENTS", "${coast.eventCount}")
            Stat("NEUTRAL / CUT", "${coast.neutralEvents}/${coast.fuelCutEvents}", color = ElectricAmber)
            Stat("DISTANCE", String.format("%.2f km", coast.totalKm))
            Stat("SAVED vs CRUISE", String.format("%.2f L", coast.totalSavedVsCruiseL))
            Stat("SAVED vs IDLE", String.format("%.2f L", coast.totalSavedVsIdleL))
        }
        Spacer(modifier = Modifier.height(4.dp))
        val mode = snapshot.activeCoastMode
        Text(
            when {
                mode == CoastNeutralDetector.CoastMode.ENGINE_BRAKING_FUEL_CUT ->
                    "Coasting NOW in gear — injectors shut, 0 L/h. This is the efficient mode."
                mode == CoastNeutralDetector.CoastMode.NEUTRAL_IDLE_COAST ->
                    "Coasting NOW at idle — drivetrain disengaged. Smooth, but burning idle fuel " +
                        "and without engine braking."
                else -> "Not coasting right now."
            },
            color = if (mode == CoastNeutralDetector.CoastMode.NEUTRAL_IDLE_COAST) ElectricAmber else NeonEmerald,
            fontSize = 11.sp
        )
        if (history.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "SAVED SESSIONS (behaviour + mileage persist across restarts)",
                color = TextSecondaryDark, fontSize = 10.sp
            )
            history.forEach { entry ->
                Text(entry.display, color = CyberCyan, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun TurboCard(snapshot: DriveAnalytics.DriveSnapshot) {
    val turbo = snapshot.turbo
    SectionCard(
        title = "Turbo behaviour",
        subtitle = "Boost = manifold absolute pressure minus barometric pressure. Negative is " +
            "vacuum (part load); positive means the turbo is compressing the charge."
    ) {
        if (turbo == null) {
            Text("Waiting for MAP/BARO samples…", color = TextSecondaryDark, fontSize = 12.sp)
        } else {
            Row {
                Stat(
                    "BOOST NOW",
                    turbo.boostKpa?.let { String.format("%.0f kPa", it) } ?: "--",
                    color = if (turbo.isBoosting) ElectricAmber else CyberCyan
                )
                Stat("PEAK", String.format("%.0f kPa", turbo.peakBoostKpa))
                Stat(
                    "VACUUM",
                    turbo.vacuumKpa?.let { String.format("%.0f", it) } ?: "--",
                    color = CyberCyan
                )
                Stat(
                    "TIP-IN LAG",
                    turbo.averageLagMs?.let { String.format("%.1f s", it / 1000.0) } ?: "--",
                    color = WarningRed
                )
                Stat("OVERBOOST", "${turbo.overBoostEvents}", color = WarningRed)
            }
            Spacer(modifier = Modifier.height(6.dp))
            XyPlot(
                series = listOf(
                    XySeries(
                        "avg boost kPa",
                        ElectricAmber,
                        turbo.boostVsRpm.map { it.first.toFloat() to it.second.toFloat() }
                    )
                ),
                xLabel = "rpm",
                yLabel = "kPa",
                height = 140.dp
            )
            turbo.chargeTempC?.let {
                Text(
                    "Charge-air temperature ${String.format("%.0f", it)} °C" +
                        (turbo.wastegatePct?.let { w -> " • wastegate duty ${String.format("%.0f", w)} %" } ?: ""),
                    color = TextSecondaryDark,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun TanksCard(
    snapshot: DriveAnalytics.DriveSnapshot,
    history: List<DriveInsightsStore.TankLogEntry> = emptyList()
) {
    SectionCard(
        title = "Fuel tanks: X95 vs regular evidence",
        subtitle = "Refuels are detected from the fuel-level PID. Cruise-only ignition advance, " +
            "trims and knock-retard events are compared per tank — higher octane lets the ECU " +
            "hold more spark advance."
    ) {
        if (snapshot.tanks.isEmpty()) {
            Text("No tank data yet.", color = TextSecondaryDark, fontSize = 12.sp)
        } else {
            snapshot.tanks.takeLast(6).forEach { tank ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(
                        tank.label.padEnd(8),
                        color = CyberCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        (tank.kmPerLiter?.let { String.format("%.1f km/L", it) } ?: "-- km/L").padEnd(12),
                        color = NeonEmerald,
                        fontSize = 12.sp
                    )
                    Text(
                        (tank.avgCruiseTimingDeg?.let { String.format("%.1f°", it) } ?: "--°").padEnd(8),
                        color = ElectricAmber,
                        fontSize = 12.sp
                    )
                    Text(
                        "knock ${tank.knockRetardEvents}".padEnd(10),
                        color = if (tank.knockRetardEvents > 3) WarningRed else TextSecondaryDark,
                        fontSize = 12.sp
                    )
                    Text(
                        "score ${tank.score}",
                        color = if (tank.score >= 70) NeonEmerald else TextSecondaryDark,
                        fontSize = 12.sp
                    )
                    tank.gradeTag?.let {
                        Text(
                            " $it",
                            color = if (it == "X95") NeonEmerald else ElectricAmber,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            snapshot.fuelComparisonNote?.let {
                Surface(
                    color = ResearchPurple.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                ) {
                    Text(it, color = TextSecondaryDark, fontSize = 11.sp, modifier = Modifier.padding(10.dp))
                }
            }
        }
        if (history.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "SAVED TANKS (survive restarts - X95 vs regular evidence builds up here)",
                color = TextSecondaryDark, fontSize = 10.sp
            )
            history.forEach { entry ->
                Text(entry.display, color = CyberCyan, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun CoachCard(tips: List<DrivingCoach.Tip>) {
    SectionCard(
        title = "Coaching for your daily route",
        subtitle = "Derived from this session's measured data. Same route every day means small " +
            "habits compound: 10 % on 32 km is ~1 litre per working day."
    ) {
        if (tips.isEmpty()) {
            Text(
                "Drive a few kilometres with the adapter connected and the tips appear here.",
                color = TextSecondaryDark,
                fontSize = 12.sp
            )
        } else {
            tips.take(6).forEach { tip ->
                val color = when (tip.severity) {
                    DrivingCoach.Tip.Severity.GOOD -> NeonEmerald
                    DrivingCoach.Tip.Severity.WARN -> WarningRed
                    DrivingCoach.Tip.Severity.INFO -> ElectricAmber
                }
                Surface(
                    color = color.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(tip.title, color = color, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(tip.detail, color = TextSecondaryDark, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

/** Kept for the About screen: the physics constants the model uses, shown to the user. */
internal fun modelSummary(): String =
    "Kylaq 1.0 TSI (EA211 evo2): ${PowertrainModel.PEAK_POWER_KW.toInt()} kW @5000-5500 rpm, " +
        "${PowertrainModel.PEAK_TORQUE_NM.toInt()} Nm @1750-4000 rpm, kerb " +
        "${PowertrainModel.KERB_KG.toInt()} kg, tank ${PowertrainModel.TANK_CAPACITY_L.toInt()} L."

/**
 * Ride X-ray: per-recording behaviour breakdown the owner asked for - accelerator/brake/nothing/
 * neutral-coast seconds as shares, gear usage (D/S/M tagged), shift points (sport-map detection)
 * and elevation climbed/descended from GPS altitude. D-vs-S comparison across tagged rides.
 */
@Composable
private fun RideXrayCard(rides: List<com.example.analysis.RideBehaviorRecorder.RideSummary>) {
    SectionCard(
        title = "Ride X-ray (per-ride behaviour)",
        subtitle = "Pedal / brake / coast / neutral seconds, gear seconds, shift map, elevation - saved per recording"
    ) {
        if (rides.isEmpty()) {
            Text(
                "Record a drive of 1+ minute and your first X-ray appears here.",
                color = TextSecondaryDark, fontSize = 11.sp
            )
        } else {
            rides.take(4).forEach { r ->
                Column(
                    modifier = androidx.compose.ui.Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        "${r.dateUtc.take(10)} · ${String.format("%.0f", r.durationSec / 60.0)} min · " +
                            "${String.format("%.1f", r.distanceKm)} km · selector ${r.modeTag}",
                        color = NeonEmerald, fontSize = 11.sp, fontWeight = FontWeight.Bold
                    )
                    val top = r.stateSeconds.entries.sortedByDescending { it.value }.take(4)
                    Text(
                        top.joinToString("  ") {
                            "${it.key.replace('_', ' ').lowercase()} ${String.format("%.0f", if (r.durationSec > 0) it.value / r.durationSec * 100 else 0.0)}%"
                        },
                        color = Color.White, fontSize = 10.sp
                    )
                    val gears = r.gearSeconds.withIndex().drop(1).filter { it.value >= 5.0 }
                        .joinToString(" ") { "G${it.index}:${String.format("%.0f", it.value)}s" }
                    Text(
                        "gears ${gears.ifBlank { "n/a" }} · climbed ${String.format("%.0f", r.elevationGainM)} m · " +
                            "descended ${String.format("%.0f", r.elevationLossM)} m",
                        color = TextSecondaryDark, fontSize = 10.sp
                    )
                    r.avgUpshiftRpm?.let { up ->
                        Text(
                            "${r.shiftCount} shift(s), avg upshift ${String.format("%.1f", up / 1000.0)}k rpm - " +
                                if (r.sportLikeShiftMap) "prolonged sport-map shifts" else "economy D-map shifts",
                            color = if (r.sportLikeShiftMap) ElectricAmber else TextSecondaryDark,
                            fontSize = 10.sp
                        )
                    }
                }
            }
            val dUp = rides.filter { it.modeTag == "D" }.mapNotNull { it.avgUpshiftRpm }
            val sUp = rides.filter { it.modeTag != "D" }.mapNotNull { it.avgUpshiftRpm }
            if (dUp.isNotEmpty() && sUp.isNotEmpty()) {
                Text(
                    "D vs S behaviour: D rides upshift at ${String.format("%.1f", dUp.average() / 1000.0)}k rpm, " +
                        "S/M at ${String.format("%.1f", sUp.average() / 1000.0)}k rpm - sport holds gears " +
                        "${String.format("%.0f", sUp.average() - dUp.average())} rpm longer.",
                    color = CyberCyan, fontSize = 10.sp
                )
            }
        }
    }
}
