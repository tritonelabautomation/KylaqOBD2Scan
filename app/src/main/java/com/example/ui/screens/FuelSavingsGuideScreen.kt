package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.analysis.FuelSavingsCoach
import com.example.ui.theme.*
import com.example.ui.viewmodel.MainViewModel

/**
 * SAVE FUEL playbook (owner request 2026-09-13: "I was expecting something like
 * obdeleven.com/how-to-save-fuel"). Same 5-step structure as the best public guides
 * (baseline -> smooth driving -> steady speed -> hidden drains -> car health + FAQ),
 * but every number that CAN be personalised IS: the owner's own multi-tank km/L
 * baseline, their latest pump price, this car's idle burn and AC model.
 *
 * Structure-only inspiration from public guidance; all physics constants come from
 * this repo's own PowertrainModel / AcClimateModel (documented in the QA audit docs).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FuelSavingsGuideScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onOpenFuelCosts: () -> Unit,
    onOpenDtc: () -> Unit,
    onOpenMaintenance: () -> Unit
) {
    // QA/QC 2026-09-13: reactive to fuel-log mutations (add/delete/import) even when the
    // user returns from Fuel & Costs via back-navigation (remember{} alone would stay stale).
    val changeTick by viewModel.fuelLogRepository.changeTick.collectAsState()
    var baseline by remember {
        mutableStateOf(FuelSavingsCoach.baseline(viewModel.fuelLogRepository.entries()))
    }
    LaunchedEffect(changeTick) {
        baseline = FuelSavingsCoach.baseline(viewModel.fuelLogRepository.entries())
    }
    val idlePerHour = FuelSavingsCoach.idleRupeesPerHour(baseline.latestPricePerL)
    val idlePer10Min = FuelSavingsCoach.idleRupeesPer10Min(baseline.latestPricePerL)
    // True model extreme (Hyderabad summer: 45 C outside, 18 C set), NOT the unknown-temps fallback.
    val acWorstLh = remember { FuelSavingsCoach.acExtraLh("AC", autoMode = false, ambientC = 45.0, setTempC = 18.0) }
    val acTypicalLh = remember { FuelSavingsCoach.acExtraLh("AC", autoMode = true, ambientC = 35.0, setTempC = 24.0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Save Fuel", color = TextPrimaryDark, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = CyberCyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
            )
        },
        containerColor = DarkCanvas
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            // ── STEP 1: YOUR BASELINE (personalised) ─────────────────────────
            item {
                GuideCard("1 · YOUR BASELINE — measure first", Icons.Default.QueryStats) {
                    if (baseline.recentKmL != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                String.format(java.util.Locale.US, "%.1f", baseline.recentKmL),
                                color = NeonEmerald, fontSize = 40.sp, fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("km/L over your last ${baseline.tanks} full tank(s)", color = TextPrimaryDark, fontSize = 14.sp)
                                baseline.latestPricePerL?.let {
                                    Text("latest pump price ₹%.2f/L".format(it), color = TextSecondaryDark, fontSize = 12.sp)
                                }
                            }
                        }
                        baseline.trendPct?.let { t ->
                            Spacer(Modifier.height(6.dp))
                            val up = t >= 0
                            AssistChip(
                                onClick = {},
                                label = { Text("%+.1f%% vs previous tanks".format(t)) },
                                leadingIcon = {
                                    Icon(
                                        if (up) Icons.Default.TrendingUp else Icons.Default.TrendingDown,
                                        null, modifier = Modifier.size(16.dp),
                                        tint = if (up) NeonEmerald else WarningRed
                                    )
                                }
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "One trip proves nothing — traffic and weather move it. Guides agree: track 3-4 " +
                                "refuels before judging a habit change. Partials never anchor km/L here (they only add litres), " +
                                "so this number is honest.",
                            color = TextSecondaryDark, fontSize = 12.sp
                        )
                    } else {
                        Text(
                            "NO BASELINE YET — and that is the single biggest fuel-saving mistake.",
                            color = WarningRed, fontWeight = FontWeight.Bold, fontSize = 14.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Log 2+ consecutive FULL fill-ups (odometer + litres) in Fuel & Costs and your real km/L " +
                                "appears here — then every tip below can be measured against your own number.",
                            color = TextSecondaryDark, fontSize = 12.sp
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onOpenFuelCosts, colors = ButtonDefaults.buttonColors(containerColor = CyberCyan)) {
                        Icon(Icons.Default.LocalGasStation, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open Fuel & Costs", color = DarkCanvas)
                    }
                }
            }

            // ── STEP 2: DRIVE SMOOTHLY ────────────────────────────────────────
            item {
                GuideCard("2 · DRIVE SMOOTHLY — the 23 % lever", Icons.Default.SportsMartialArts) {
                    Bullet("Aggressive driving burns 23 % more fuel than normal driving on curves - and up to 59 % more in stop-and-go work zones (Transportation Research Part D 126, 2024: naturalistic second-by-second data + Autonomie drive-cycle simulations).")
                    Bullet("Everyday range from ORNL/SAE: 10-40 % worse mileage in stop-and-go traffic, 15-30 % at highway speeds - about $0.25-1 per gallon thrown away.")
                    Bullet("Every hard launch and every late brake throws away momentum you paid for in petrol.")
                    Bullet("The coffee-cup rule: drive like a full cup is on your dash. If passengers rock forward/back, your km/L is dropping.")
                    Bullet("Watch your own numbers: Insights scores acceleration/braking per ride; the trip fuel card shows litres burned per trip.")
                }
            }

            // ── STEP 3: STEADY SPEED + SMARTER ROUTES ─────────────────────────
            item {
                GuideCard("3 · STEADY SPEED, SMARTER ROUTES", Icons.Default.Speed) {
                    Bullet("Your 1.0 TSI is happiest at a steady 60-90 km/h in top gear (~1500-2500 rpm). Below ~1000 rpm it lugs and enriches — floor it there and you pay twice.")
                    Bullet("US DOE rule of thumb: every 5 mph over 50 mph ≈ \$0.27 extra per gallon. Speed rebuilds drag ~v² — the AQ250 can't save you from physics.")
                    Bullet("Fewer stops beats shorter distance: batch errands into one loop; a warm engine in closed loop beats three cold short trips.")
                    Bullet("Look ahead, lift early, coast to lights — your coast seconds are tracked per trip (decel fuel cut = 0 L/h).")
                }
            }

            // ── STEP 4: HIDDEN DRAINS (personalised) ─────────────────────────
            item {
                GuideCard("4 · CUT THE HIDDEN DRAINS", Icons.Default.Visibility) {
                    if (idlePerHour != null) {
                        Bullet(
                            "IDLING costs you ₹%.0f/hour at your latest pump price (this engine drinks ~0.8 L/h warm). ".format(idlePerHour) +
                                "A 10-minute school-gate idle ≈ ₹%.0f, zero kilometres.".format(idlePer10Min ?: 0.0)
                        )
                    } else {
                        Bullet("IDLING burns ~0.8 L/h warm — log one fill-up and this line shows YOUR ₹/hour cost.")
                    }
                    Bullet(
                        "A/C: typical AUTO use at 35 °C ambient adds ≈ %.2f L/h; a Hyderabad-summer max-cool run (45 °C out, 18 °C set) up to ≈ %.2f L/h (this app's AC model: 1.1 kW + 0.10 kW/Δ°C capped 4.0, AUTO ×0.75). ".format(acTypicalLh, acWorstLh) +
                            "On short city trips a maxed A/C can cut economy by >25 % — vent the hot air with windows first, then close and run moderate A/C on recirc."
                    )
                    Bullet("Windows vs A/C: windows fine below ~60 km/h; above that, drag makes moderate A/C the cheaper choice.")
                    Bullet("Weight: every ~45 kg of junk in the boot ≈ 1 % economy. Roof racks/boxes: 10-25 % at highway speed — remove when unused.")
                    Bullet("Start-stop: leave it ON in city traffic — it exists precisely to kill the idle burn above.")
                }
            }

            // ── STEP 5: CAR HEALTH ────────────────────────────────────────────
            item {
                GuideCard("5 · LET THE CAR CONFESS — health first", Icons.Default.HealthAndSafety) {
                    Bullet("A sudden km/L drop with no change in traffic = suspect the car, not your foot. Scan first, guess never.")
                    Bullet("Silent fuel-wasters: ageing O2 sensor, worn plugs, dirty air filter, misfires, low tyre pressure (proper tyres: +0.6 % typical, up to +3 %).")
                    Bullet("This app's dashboard now shows live fuel rate (015E/019D), trims (0106/0107) and fuel-system status — or tells you loudly why a value is missing.")
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onOpenDtc, colors = ButtonDefaults.buttonColors(containerColor = WarningRed)) {
                            Text("Scan fault codes", color = Color.White, fontSize = 13.sp)
                        }
                        OutlinedButton(onClick = onOpenMaintenance) {
                            Text("Maintenance", fontSize = 13.sp)
                        }
                    }
                }
            }

            // ── FAQ ───────────────────────────────────────────────────────────
            item {
                Text("QUICK ANSWERS", color = ElectricAmber, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            item {
                FaqItem(
                    "What speed is most fuel efficient?",
                    "Moderate and steady beats fast for almost every car — drag rises with the square of speed. For your Kylaq 1.0 TSI the relaxed band is roughly 60-90 km/h in top gear. Find YOUR exact number: same stretch of road, two steady speeds, compare the trip card's km/L."
                )
            }
            item {
                FaqItem(
                    "Does start-stop really save fuel?",
                    "Yes in stop-and-go traffic — it deletes the warm-idle burn at lights (~0.8-1.1 L/h on the 1.0 TSI), and the battery and starter are designed for it. Your Kylaq has it, and the app now MEASURES it: the trip fuel card counts the stalls and engine-off seconds, estimates the fuel saved against your own trip's measured idle rate, and reports the cranking-enrichment spike at each restart separately. On a smooth highway run it barely matters anyway."
                )
            }
            item {
                FaqItem(
                    "A/C or open windows?",
                    "Low speed: windows win. Highway speed: drag from open windows exceeds a moderate A/C on recirculation. Best move in a baked car: windows open for a minute to vent, then close and A/C moderate."
                )
            }
            item {
                FaqItem(
                    "How fast do better habits pay off?",
                    "Small wins show within a few trips (less idling, smoother launches). The honest verdict needs 2-4 full tanks logged — which is exactly what step 1 measures for you."
                )
            }

            item {
                Text(
                    "Playbook structure follows publicly available fuel-saving guidance (e.g. OBDeleven \"How to save fuel\", 06/2026); " +
                        "all vehicle numbers come from this app's own models and YOUR fuel log.",
                    color = TextMutedDark, fontSize = 10.sp,
                    modifier = Modifier.padding(bottom = 20.dp)
                )
            }
        }
    }
}

@Composable
private fun GuideCard(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSurface, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = CyberCyan, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, color = TextPrimaryDark, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun Bullet(text: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text("•", color = ElectricAmber, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text(text, color = TextSecondaryDark, fontSize = 12.5.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun FaqItem(question: String, answer: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSurface, RoundedCornerShape(12.dp))
            .clickable { expanded = !expanded }
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                null, tint = CyberCyan, modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(question, color = TextPrimaryDark, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
        AnimatedVisibility(visible = expanded) {
            Text(
                answer, color = TextSecondaryDark, fontSize = 12.5.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(top = 6.dp, start = 24.dp)
            )
        }
    }
}
