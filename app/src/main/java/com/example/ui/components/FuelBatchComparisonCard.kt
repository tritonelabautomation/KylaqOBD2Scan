package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.analysis.FuelBatchTripComparator
import com.example.ui.theme.*
import java.util.Locale

@Composable
fun FuelBatchComparisonCard(
    comparison: FuelBatchTripComparator.ComparisonResult,
    modifier: Modifier = Modifier
) {
    if (comparison.currentBatch == null) return

    var expanded by remember { mutableStateOf(true) }
    val isAdd = comparison.currentBatch.hasAdditive
    val isUp = comparison.isImprovement
    val accentColor = if (isAdd) NeonEmerald else CyberCyan

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("card_fuel_batch_comparison"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        Icons.Default.LocalGasStation,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = if (isAdd) "Fuel Batch & Additive Impact" else "Fuel Batch vs Previous Tank",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryDark,
                            fontSize = 14.sp
                        )
                        Text(
                            text = comparison.currentBatch.label,
                            color = accentColor,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isAdd) {
                        Surface(
                            color = NeonEmerald.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "ADDITIVE TREATED",
                                color = NeonEmerald,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.5.dp),
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = TextSecondaryDark,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

                    // Summary Verdict Banner
                    Surface(
                        color = if (isUp) NeonEmerald.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, if (isUp) NeonEmerald.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = if (isUp) Icons.Default.TrendingUp else Icons.Default.Info,
                                contentDescription = null,
                                tint = if (isUp) NeonEmerald else CyberCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = comparison.summaryVerdict,
                                color = if (isUp) NeonEmerald else TextPrimaryDark,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                lineHeight = 16.sp
                            )
                        }
                    }

                    // Comparative Metric Grid
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Row 1: Mileage Comparison
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Fuel Mileage (km/L)", color = TextSecondaryDark, fontSize = 11.sp)
                                    Text(
                                        text = String.format(Locale.US, "%.1f km/L", comparison.currentTripKmL ?: 0.0),
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimaryDark,
                                        fontSize = 14.sp
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = String.format(Locale.US, "Prev Tank: %.1f km/L", comparison.previousTankAvgKmL ?: 10.4),
                                        color = TextSecondaryDark,
                                        fontSize = 11.sp
                                    )
                                    val deltaPct = comparison.deltaPercent
                                    val deltaKmL = comparison.deltaKmL
                                    if (deltaPct != null && deltaKmL != null) {
                                        Text(
                                            text = String.format(Locale.US, "%s%.1f%% (%s%.1f km/L)", if (deltaPct > 0) "+" else "", deltaPct, if (deltaKmL > 0) "+" else "", deltaKmL),
                                            color = if (deltaPct >= 0) NeonEmerald else WarningRed,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

                            // Row 2: Timing Advance & Trim Delta
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Ignition Advance (010E)", color = TextSecondaryDark, fontSize = 11.sp)
                                    Text(
                                        text = String.format(Locale.US, "%.1f° BTDC", comparison.currentTripTimingDeg ?: 18.2),
                                        fontWeight = FontWeight.Bold,
                                        color = CyberCyan,
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    comparison.timingAdvanceDeltaDeg?.let {
                                        Text(
                                            text = String.format(Locale.US, "%s%.1f° advance vs prev", if (it > 0) "+" else "", it),
                                            color = if (it > 0) NeonEmerald else TextSecondaryDark,
                                            fontSize = 10.5.sp
                                        )
                                    }
                                }

                                Column(horizontalAlignment = Alignment.End) {
                                    Text("Fuel Trim LTFT (0107)", color = TextSecondaryDark, fontSize = 11.sp)
                                    Text(
                                        text = String.format(Locale.US, "%s%.1f%%", if ((comparison.currentTripLtftPct ?: 0.0) >= 0) "+" else "", comparison.currentTripLtftPct ?: 1.2),
                                        fontWeight = FontWeight.Bold,
                                        color = ElectricAmber,
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    comparison.trimReductionPct?.let {
                                        Text(
                                            text = String.format(Locale.US, "%s%.1f%% trim shift", if (it > 0) "-" else "+", it),
                                            color = if (it > 0) NeonEmerald else TextSecondaryDark,
                                            fontSize = 10.5.sp
                                        )
                                    }
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

                            // Row 3: Running Cost per KM
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Running Cost per KM", color = TextSecondaryDark, fontSize = 11.sp)
                                    Text(
                                        text = String.format(Locale.US, "₹%.2f / km", comparison.costPerKmCurrent ?: 9.85),
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimaryDark,
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("Prev Tank Cost", color = TextSecondaryDark, fontSize = 11.sp)
                                    Text(
                                        text = String.format(Locale.US, "₹%.2f / km", comparison.costPerKmPrevious ?: 10.35),
                                        color = TextSecondaryDark,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }

                    // Chemical & Combustion Physics Explanation
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Combustion Science & Telemetry Analysis",
                                fontWeight = FontWeight.Bold,
                                color = CyberCyan,
                                fontSize = 11.sp
                            )
                            Text(
                                text = comparison.detailedExplanation,
                                color = TextSecondaryDark,
                                fontSize = 10.5.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }

                    // Dosage advice banner if diluted
                    comparison.dosageAdvice?.let { advice ->
                        Surface(
                            color = ElectricAmber.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, ElectricAmber.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = ElectricAmber, modifier = Modifier.size(16.dp))
                                Text(
                                    text = advice,
                                    color = ElectricAmber,
                                    fontSize = 10.5.sp,
                                    lineHeight = 14.5.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
