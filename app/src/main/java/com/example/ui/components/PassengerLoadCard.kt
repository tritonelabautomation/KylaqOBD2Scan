package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.analysis.PassengerLoadAnalyzer
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.ElectricAmber
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.ResearchPurple
import com.example.ui.theme.TextPrimaryDark
import com.example.ui.theme.TextSecondaryDark
import com.example.ui.theme.WarningRed
import java.util.Locale

/**
 * Passenger Load, Engine Work & Transmission Shift Dynamics Card.
 *
 * Displays:
 * 1. Occupant payload and vehicle operating mass.
 * 2. Specific fuel burn (L / tonne·100km & L / pax·100km).
 * 3. Engine torque demand, turbo boost time and thermal load.
 * 4. AQ250 transmission shift RPM profile (Eco vs Normal vs Sport/Load ~3k RPM).
 * 5. Authentic EA211 Overkill & Health Verdict.
 */
@Composable
fun PassengerLoadCard(
    result: PassengerLoadAnalyzer.Result,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header Row: Occupant Count Badge & Payload Mass
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(CyberCyan.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Group,
                            contentDescription = null,
                            tint = CyberCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "PASSENGER LOAD & DYNAMICS",
                            color = CyberCyan,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        Text(
                            text = if (result.passengerCount > 0) "${result.occupantCount} Occupants (Driver + ${result.passengerCount} Pax)" else "Solo Driver (1 Occupant)",
                            color = TextPrimaryDark,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                    }
                }

                // Mass Increase Tag
                Surface(
                    color = if (result.occupantCount >= 4) ElectricAmber.copy(alpha = 0.15f) else CyberCyan.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (result.occupantCount >= 4) ElectricAmber.copy(alpha = 0.4f) else CyberCyan.copy(alpha = 0.3f)
                    )
                ) {
                    Text(
                        text = "+${result.payloadKg.toInt()} kg (${result.totalVehicleMassKg.toInt()} kg)",
                        color = if (result.occupantCount >= 4) ElectricAmber else CyberCyan,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Grid 1: Specific Energy & Passenger Efficiency
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = result.specificFuelPerTonne100Km?.let { String.format(Locale.US, "%.2f", it) } ?: "--",
                        color = NeonEmerald,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text("L / TONNE·100KM", color = TextSecondaryDark, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = result.fuelPerPax100Km?.let { String.format(Locale.US, "%.2f L", it) } ?: "--",
                        color = NeonEmerald,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text("PER-PAX / 100KM", color = TextSecondaryDark, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }
                Column(modifier = Modifier.weight(1f)) {
                    val sign = if (result.payloadMassIncreasePct > 0) "+" else ""
                    Text(
                        text = "$sign${String.format(Locale.US, "%.1f", result.payloadMassIncreasePct)}%",
                        color = if (result.payloadMassIncreasePct > 15.0) ElectricAmber else TextPrimaryDark,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text("MASS OVER SOLO", color = TextSecondaryDark, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
            Spacer(modifier = Modifier.height(8.dp))

            // Section 2: Engine Torque & Boost Under Payload
            Text(
                text = "ENGINE WORK & TURBO DEMAND",
                color = TextSecondaryDark,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    val meanT = result.meanTorqueNm
                    Text(
                        text = meanT?.let { "${String.format(Locale.US, "%.0f", it)} Nm" } ?: "--",
                        color = TextPrimaryDark,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text("MEAN TORQUE", color = TextSecondaryDark, fontSize = 10.sp)
                }
                Column(modifier = Modifier.weight(1f)) {
                    val peakT = result.peakTorqueNm
                    Text(
                        text = peakT?.let { "${String.format(Locale.US, "%.0f", it)} / ${result.ratedTorqueNm.toInt()} Nm" } ?: "--",
                        color = if ((result.torqueUtilizationPct ?: 0.0) > 90.0) ElectricAmber else TextPrimaryDark,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text("PEAK / RATED (1.0 TSI)", color = TextSecondaryDark, fontSize = 10.sp)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${String.format(Locale.US, "%.0f", result.boostActivePct)}%",
                        color = if (result.boostActivePct > 30.0) ResearchPurple else TextPrimaryDark,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text("IN TURBO BOOST", color = TextSecondaryDark, fontSize = 10.sp)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
            Spacer(modifier = Modifier.height(8.dp))

            // Section 3: Transmission Shift Points & Sport Mode Analysis
            val sp = result.shiftProfile
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "TRANSMISSION SHIFT DYNAMICS (AQ250 6-SPEED AT)",
                    color = TextSecondaryDark,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                val badgeColor = when (sp.detectedMode) {
                    "SPORT (S)" -> ElectricAmber
                    "ECO (D)" -> NeonEmerald
                    else -> CyberCyan
                }
                Surface(
                    color = badgeColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(4.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, badgeColor.copy(alpha = 0.35f))
                ) {
                    Text(
                        text = sp.detectedMode,
                        color = badgeColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${sp.totalUpshifts} shifts",
                        color = TextPrimaryDark,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text("DETECTED UPSHIFTS", color = TextSecondaryDark, fontSize = 10.sp)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = sp.avgUpshiftRpm?.let { "${String.format(Locale.US, "%.0f", it)} RPM" } ?: "--",
                        color = if ((sp.avgUpshiftRpm ?: 0.0) >= 2350.0) ElectricAmber else TextPrimaryDark,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text("AVG SHIFT POINT", color = TextSecondaryDark, fontSize = 10.sp)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${String.format(Locale.US, "%.0f", sp.sportModeHoldPct)}%",
                        color = if (sp.sportModeHoldPct >= 20.0) ElectricAmber else TextPrimaryDark,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text("SPORT / REV HOLD", color = TextSecondaryDark, fontSize = 10.sp)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Visual Shift Profile Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    color = NeonEmerald.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(6.dp)) {
                        Text("D-ECO (<2.0k)", color = NeonEmerald, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Text("${sp.ecoShiftsCount} (${String.format(Locale.US, "%.0f", sp.ecoShiftsPct)}%)", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Surface(
                    color = CyberCyan.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(6.dp)) {
                        Text("NORMAL (2.0-2.45k)", color = CyberCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Text("${sp.normalShiftsCount} (${String.format(Locale.US, "%.0f", sp.normalShiftsPct)}%)", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Surface(
                    color = if (sp.sportShiftsCount > 0 || sp.sportModeHoldPct >= 20.0) ElectricAmber.copy(alpha = 0.15f) else DarkSurfaceElevated,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(6.dp)) {
                        Text("SPORT (>2.45k)", color = if (sp.sportShiftsCount > 0 || sp.sportModeHoldPct >= 20.0) ElectricAmber else TextSecondaryDark, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Text("${sp.sportShiftsCount} (${String.format(Locale.US, "%.0f", sp.sportShiftsPct)}%)", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Section 4: Authentic Engine Stress & Overkill Verdict Card
            val verdict = result.verdict
            Surface(
                color = when (verdict.level) {
                    PassengerLoadAnalyzer.VerdictLevel.COMFORTABLE -> NeonEmerald.copy(alpha = 0.12f)
                    PassengerLoadAnalyzer.VerdictLevel.MODERATE_LOAD -> CyberCyan.copy(alpha = 0.12f)
                    PassengerLoadAnalyzer.VerdictLevel.HEAVY_LOAD -> ElectricAmber.copy(alpha = 0.12f)
                    PassengerLoadAnalyzer.VerdictLevel.OVERLOADED -> WarningRed.copy(alpha = 0.15f)
                },
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    when (verdict.level) {
                        PassengerLoadAnalyzer.VerdictLevel.COMFORTABLE -> NeonEmerald.copy(alpha = 0.4f)
                        PassengerLoadAnalyzer.VerdictLevel.MODERATE_LOAD -> CyberCyan.copy(alpha = 0.4f)
                        PassengerLoadAnalyzer.VerdictLevel.HEAVY_LOAD -> ElectricAmber.copy(alpha = 0.4f)
                        PassengerLoadAnalyzer.VerdictLevel.OVERLOADED -> WarningRed.copy(alpha = 0.5f)
                    }
                )
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (!verdict.isOverkill) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (!verdict.isOverkill) NeonEmerald else WarningRed,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = verdict.headline,
                            color = if (!verdict.isOverkill) NeonEmerald else WarningRed,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = verdict.explanation,
                        color = TextPrimaryDark,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}
