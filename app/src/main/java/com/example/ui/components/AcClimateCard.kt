package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.example.engine.AcClimateModel
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.ElectricAmber
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.TextSecondaryDark
import java.util.Locale

/**
 * AC & CLIMATE BEHAVIOUR card (added 2026-09-09, purely additive).
 * Shows the owner-tagged climate state, AUTO flag, setpoint vs PID 0146 ambient
 * delta, the modelled compressor load and its fuel price, and the cross-ride
 * learned AC-on vs AC-off economy.
 */
@Composable
fun AcClimateCard(
    acTag: String,
    onCycleAc: () -> Unit,
    autoMode: Boolean,
    onToggleAuto: () -> Unit,
    setTempC: Double,
    onSetTemp: (Double) -> Unit,
    ambientC: Double?,
    baselineLh: Double?,
    onKmL: Double?,
    offKmL: Double?,
    learnedRides: Int,
    modifier: Modifier = Modifier
) {
    val delta = AcClimateModel.deltaC(ambientC, setTempC)
    val loadKw = AcClimateModel.compressorLoadKw(acTag, autoMode, ambientC, setTempC)
    val penaltyLh = AcClimateModel.fuelPenaltyLh(loadKw)
    val penaltyPct = AcClimateModel.penaltyPercent(penaltyLh, baselineLh)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, CyberCyan.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .background(DarkSurface, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AC & CLIMATE BEHAVIOUR", color = CyberCyan, fontSize = 12.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
            // Climate tag chip: OFF -> AC -> BLOWER (same tags the ride recorder uses)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        when (acTag) {
                            "AC" -> NeonEmerald.copy(alpha = 0.15f)
                            "BLOWER" -> ElectricAmber.copy(alpha = 0.15f)
                            else -> TextSecondaryDark.copy(alpha = 0.15f)
                        }
                    )
                    .clickable { onCycleAc() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    when (acTag) { "AC" -> "AC ON"; "BLOWER" -> "BLOWER"; else -> "AC OFF" },
                    color = if (acTag == "AC") NeonEmerald else if (acTag == "BLOWER") ElectricAmber else TextSecondaryDark,
                    fontSize = 10.sp, fontWeight = FontWeight.Black
                )
            }
            // AUTO modulation flag
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (autoMode) CyberCyan.copy(alpha = 0.2f) else TextSecondaryDark.copy(alpha = 0.12f))
                    .clickable { onToggleAuto() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text("AUTO", color = if (autoMode) CyberCyan else TextSecondaryDark, fontSize = 10.sp, fontWeight = FontWeight.Black)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("SET", color = TextSecondaryDark, fontSize = 10.sp)
            Box(Modifier.clip(RoundedCornerShape(6.dp)).background(TextSecondaryDark.copy(alpha = 0.15f)).clickable { onSetTemp((setTempC - 0.5).coerceAtLeast(16.0)) }.padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text("-", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black)
            }
            Text(String.format(Locale.US, "%.1f°C", setTempC), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Box(Modifier.clip(RoundedCornerShape(6.dp)).background(TextSecondaryDark.copy(alpha = 0.15f)).clickable { onSetTemp((setTempC + 0.5).coerceAtMost(28.0)) }.padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text("+", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black)
            }
            Text("OUT", color = TextSecondaryDark, fontSize = 10.sp, modifier = Modifier.padding(start = 6.dp))
            Text(
                if (ambientC != null) String.format(Locale.US, "%.0f°C", ambientC) else "--",
                color = if (ambientC != null) NeonEmerald else TextSecondaryDark,
                fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
            )
            Text(
                if (delta != null) String.format(Locale.US, "Δ %+.0f°", delta) else "Δ --",
                color = if (delta != null && delta > 6) ElectricAmber else TextSecondaryDark,
                fontSize = 11.sp, fontWeight = FontWeight.Bold
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("COMPRESSOR", color = TextSecondaryDark, fontSize = 10.sp)
            Text(String.format(Locale.US, "%.2f kW", loadKw), color = if (loadKw > 0.05) ElectricAmber else TextSecondaryDark, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text("FUEL COST", color = TextSecondaryDark, fontSize = 10.sp)
            Text(String.format(Locale.US, "+%.2f L/h", penaltyLh), color = if (penaltyLh > 0.05) ElectricAmber else TextSecondaryDark, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            if (penaltyPct != null) {
                Text(String.format(Locale.US, "(+%.0f%%)", penaltyPct), color = ElectricAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        Text(
            if (onKmL != null && offKmL != null) {
                val dPct = if (onKmL > 0.01) 100.0 * (offKmL - onKmL) / onKmL else 0.0
                String.format(Locale.US, "LEARNED over %d ride(s): AC ON %.1f km/L vs OFF %.1f km/L (-%.1f%%)", learnedRides, onKmL, offKmL, dPct)
            } else {
                "LEARNED: tag AC state while driving - per-ride ON vs OFF economy appears here after both states log distance."
            },
            color = if (onKmL != null && offKmL != null) NeonEmerald else TextSecondaryDark,
            fontSize = 10.sp
        )
        Text(
            "J1979 exposes no compressor/setpoint PID - state is owner-tagged; OUT = PID 0146; AUTO modulates load x0.75.",
            color = TextSecondaryDark, fontSize = 9.sp
        )
    }
}
