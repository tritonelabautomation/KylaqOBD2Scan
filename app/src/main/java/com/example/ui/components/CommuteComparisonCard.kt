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
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Info
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
import com.example.analysis.CommuteComparator
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.ElectricAmber
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.ResearchPurple
import com.example.ui.theme.TextPrimaryDark
import com.example.ui.theme.TextSecondaryDark
import java.util.Locale

/**
 * Apples-to-Apples Directional Commute Comparison Card.
 *
 * Compares current morning or evening commute against prior runs in the same direction and route length.
 */
@Composable
fun CommuteComparisonCard(
    comparison: CommuteComparator.CommuteComparison,
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
            // Header Row
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
                            .background(ResearchPurple.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CompareArrows,
                            contentDescription = null,
                            tint = ResearchPurple,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "${comparison.slot.title.uppercase(Locale.US)} COMPARISON",
                            color = ResearchPurple,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        Text(
                            text = "Apples-to-Apples Directional Analysis",
                            color = TextPrimaryDark,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                    }
                }

                Surface(
                    color = ResearchPurple.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, ResearchPurple.copy(alpha = 0.3f))
                ) {
                    Text(
                        text = "${comparison.matchingHistoricalTripsCount} peer runs",
                        color = ResearchPurple,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            val baseline = comparison.soloBaseline ?: comparison.historicalSlotAverage
            if (baseline != null && comparison.matchingHistoricalTripsCount > 0) {
                // Table Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("METRIC", color = TextSecondaryDark, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.2f))
                    Text("THIS RUN", color = CyberCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(baseline.title.uppercase(Locale.US), color = TextSecondaryDark, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("DELTA", color = ElectricAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                }

                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                Spacer(modifier = Modifier.height(6.dp))

                // Row 1: Payload & Occupants
                ComparisonRow(
                    label = "Occupants / Mass",
                    currentVal = "${comparison.currentTrip.occupantCount} (${comparison.currentTrip.payloadKg.toInt()} kg)",
                    baselineVal = "${baseline.occupantCount} (${baseline.payloadKg.toInt()} kg)",
                    deltaVal = comparison.payloadDeltaKg?.let { "+${it.toInt()} kg" } ?: "--",
                    deltaColor = if ((comparison.payloadDeltaKg ?: 0.0) > 0) ElectricAmber else TextPrimaryDark
                )

                // Row 2: Fuel Economy
                val curKmL = comparison.currentTrip.kmPerLiter
                val baseKmL = baseline.kmPerLiter
                val kmLDelta = comparison.kmPerLiterDelta
                ComparisonRow(
                    label = "Fuel Economy",
                    currentVal = curKmL?.let { "${String.format(Locale.US, "%.1f", it)} km/L" } ?: "--",
                    baselineVal = baseKmL?.let { "${String.format(Locale.US, "%.1f", it)} km/L" } ?: "--",
                    deltaVal = kmLDelta?.let { "${if (it >= 0) "+" else ""}${String.format(Locale.US, "%.1f", it)}" } ?: "--",
                    deltaColor = if ((kmLDelta ?: 0.0) >= 0) NeonEmerald else ElectricAmber
                )

                // Row 3: Mean Engine Torque
                val curTorque = comparison.currentTrip.meanTorqueNm
                val baseTorque = baseline.meanTorqueNm
                val torqueDelta = comparison.meanTorqueDeltaNm
                ComparisonRow(
                    label = "Mean Torque",
                    currentVal = curTorque?.let { "${String.format(Locale.US, "%.0f", it)} Nm" } ?: "--",
                    baselineVal = baseTorque?.let { "${String.format(Locale.US, "%.0f", it)} Nm" } ?: "--",
                    deltaVal = torqueDelta?.let { "${if (it >= 0) "+" else ""}${String.format(Locale.US, "%.0f", it)} Nm" } ?: "--",
                    deltaColor = if ((torqueDelta ?: 0.0) > 5) ElectricAmber else TextPrimaryDark
                )

                // Row 4: Turbo Boost Time %
                val curBoost = comparison.currentTrip.boostActivePct
                val baseBoost = baseline.boostActivePct
                val boostDelta = comparison.boostActivePctDelta
                ComparisonRow(
                    label = "Boost Active",
                    currentVal = "${String.format(Locale.US, "%.0f", curBoost)}%",
                    baselineVal = "${String.format(Locale.US, "%.0f", baseBoost)}%",
                    deltaVal = boostDelta?.let { "${if (it >= 0) "+" else ""}${String.format(Locale.US, "%.0f", it)}%" } ?: "--",
                    deltaColor = ResearchPurple
                )

                // Row 5: Sport / High Shift %
                val curSport = comparison.currentTrip.sportShiftsPct
                val baseSport = baseline.sportShiftsPct
                val sportDelta = comparison.sportShiftsPctDelta
                ComparisonRow(
                    label = "Sport Shifts (>2.8k)",
                    currentVal = "${String.format(Locale.US, "%.0f", curSport)}%",
                    baselineVal = "${String.format(Locale.US, "%.0f", baseSport)}%",
                    deltaVal = sportDelta?.let { "${if (it >= 0) "+" else ""}${String.format(Locale.US, "%.0f", it)}% pts" } ?: "--",
                    deltaColor = ElectricAmber
                )

                // Row 6: Duration & Avg Speed
                ComparisonRow(
                    label = "Duration / Speed",
                    currentVal = "${comparison.currentTrip.durationSeconds / 60}m @ ${comparison.currentTrip.avgSpeedKmh.toInt()} km/h",
                    baselineVal = "${baseline.durationSeconds / 60}m @ ${baseline.avgSpeedKmh.toInt()} km/h",
                    deltaVal = comparison.avgSpeedDeltaKmh?.let { "${if (it >= 0) "+" else ""}${String.format(Locale.US, "%.0f", it)} km/h" } ?: "--",
                    deltaColor = TextSecondaryDark
                )

                Spacer(modifier = Modifier.height(10.dp))
            }

            // Summary Insight Box
            Surface(
                color = DarkSurface.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = ResearchPurple,
                        modifier = Modifier.size(16.dp).padding(top = 1.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = comparison.summaryInsight,
                        color = TextPrimaryDark,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ComparisonRow(
    label: String,
    currentVal: String,
    baselineVal: String,
    deltaVal: String,
    deltaColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextPrimaryDark, fontSize = 11.sp, modifier = Modifier.weight(1.2f))
        Text(currentVal, color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(baselineVal, color = TextSecondaryDark, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
        Text(deltaVal, color = deltaColor, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
    }
}
