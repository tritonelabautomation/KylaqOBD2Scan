package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import com.example.data.PollingSpeedMode
import com.example.ui.theme.*
import java.util.Locale

/**
 * Visual explanation and interactive selector for OBD Polling Speed Modes.
 * Clarifies the exact sampling trade-offs between SAFE (500ms), NORMAL (250ms), and FAST (125ms),
 * including cycle times, fuel rate integration errors, transient boost/torque peak capture,
 * and clone adapter buffer overflow risks.
 */
@Composable
fun PollingAccuracyCard(
    currentMode: PollingSpeedMode,
    onModeSelected: (PollingSpeedMode) -> Unit,
    observedGapMs: Long? = null,
    livePids: Int = 0,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("polling_accuracy_card")
            .animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Speed,
                        contentDescription = null,
                        tint = CyberCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Polling Speed & Data Accuracy",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryDark,
                        fontSize = 15.sp
                    )
                }

                Surface(
                    color = when (currentMode) {
                        PollingSpeedMode.FAST -> NeonEmerald.copy(alpha = 0.15f)
                        PollingSpeedMode.NORMAL -> CyberCyan.copy(alpha = 0.15f)
                        PollingSpeedMode.SAFE -> ElectricAmber.copy(alpha = 0.15f)
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = currentMode.displayName.uppercase(),
                        color = when (currentMode) {
                            PollingSpeedMode.FAST -> NeonEmerald
                            PollingSpeedMode.NORMAL -> CyberCyan
                            PollingSpeedMode.SAFE -> ElectricAmber
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Mode Selector Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PollingSpeedMode.values().forEach { mode ->
                    val isSelected = mode == currentMode
                    val modeColor = when (mode) {
                        PollingSpeedMode.FAST -> NeonEmerald
                        PollingSpeedMode.NORMAL -> CyberCyan
                        PollingSpeedMode.SAFE -> ElectricAmber
                    }
                    FilterChip(
                        selected = isSelected,
                        onClick = { onModeSelected(mode) },
                        label = {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = mode.displayName,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 12.sp,
                                    color = if (isSelected) modeColor else TextSecondaryDark
                                )
                                Text(
                                    text = "${mode.targetDelayMs} ms",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (isSelected) modeColor.copy(alpha = 0.85f) else TextSecondaryDark.copy(alpha = 0.7f)
                                )
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = modeColor.copy(alpha = 0.15f),
                            selectedLabelColor = modeColor
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chip_mode_${mode.name}")
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Active Mode Summary Box
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = currentMode.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimaryDark,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("SAMPLING CADENCE", fontSize = 9.sp, color = TextSecondaryDark, fontWeight = FontWeight.Bold)
                            Text(currentMode.sampleFrequency, fontSize = 11.sp, color = CyberCyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("FULL CYCLE TIME", fontSize = 9.sp, color = TextSecondaryDark, fontWeight = FontWeight.Bold)
                            Text(currentMode.cycleTimeRange, fontSize = 11.sp, color = TextPrimaryDark, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // Observed Telemetry Feedback Strip
            if (observedGapMs != null && livePids > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                val reqPerSec = com.example.scheduler.PollCadence.reqPerSec(observedGapMs)
                val cycleSec = com.example.scheduler.PollCadence.cycleSeconds(observedGapMs, livePids)
                Surface(
                    color = NeonEmerald.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.Sensors, contentDescription = null, tint = NeonEmerald, modifier = Modifier.size(14.dp))
                            Text(
                                text = "Live Adapter Link: ~${observedGapMs}ms (${String.format(Locale.US, "%.1f", reqPerSec)} req/s)",
                                color = NeonEmerald,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "${livePids} PIDs ≈ ${String.format(Locale.US, "%.1fs", cycleSec)}/cycle",
                            color = TextSecondaryDark,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Expandable Comparison Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = CyberCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (expanded) "Hide Accuracy & Data Loss Trade-Offs" else "How Much Accuracy Do I Lose?",
                        color = CyberCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "3-Mode Breakdown",
                    color = TextSecondaryDark,
                    fontSize = 11.sp
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                    Text(
                        text = "Quantitative Accuracy & Sampling Loss Comparison",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryDark,
                        fontSize = 13.sp
                    )

                    // Safe Mode Card
                    AccuracyTradeoffTile(
                        mode = PollingSpeedMode.SAFE,
                        accentColor = ElectricAmber,
                        isSelected = currentMode == PollingSpeedMode.SAFE
                    )

                    // Normal Mode Card
                    AccuracyTradeoffTile(
                        mode = PollingSpeedMode.NORMAL,
                        accentColor = CyberCyan,
                        isSelected = currentMode == PollingSpeedMode.NORMAL
                    )

                    // Fast Mode Card
                    AccuracyTradeoffTile(
                        mode = PollingSpeedMode.FAST,
                        accentColor = NeonEmerald,
                        isSelected = currentMode == PollingSpeedMode.FAST
                    )

                    // Physics & Protocol explanation note
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Why Does Polling Mode Affect Accuracy?",
                                fontWeight = FontWeight.Bold,
                                color = CyberCyan,
                                fontSize = 11.sp
                            )
                            Text(
                                text = "OBD-II CAN bus operates at 500 kbit/s, but ELM327 Bluetooth serial link operates request-by-request (one PID at a time). When logging 10 channels (RPM, Speed, MAP Boost, Torque, Fuel Rate, Voltage, etc.), each sensor only refreshes once per full cycle.\n\n" +
                                    "• FAST (~1s cycle) captures rapid throttle surges, turbo boost spool-up, and instant gear shifts.\n" +
                                    "• SAFE (~6s cycle) averages out short transients, introducing ~8–12% fuel integration error during stop-and-go city drives, but guarantees zero adapter buffer overflow.",
                                color = TextSecondaryDark,
                                fontSize = 10.5.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccuracyTradeoffTile(
    mode: PollingSpeedMode,
    accentColor: Color,
    isSelected: Boolean
) {
    Surface(
        color = if (isSelected) accentColor.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) accentColor.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(accentColor, shape = RoundedCornerShape(2.dp))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${mode.displayName} Mode (${mode.targetDelayMs}ms)",
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                        fontSize = 12.sp
                    )
                }
                if (isSelected) {
                    Surface(
                        color = accentColor.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "ACTIVE",
                            color = accentColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp)
                        )
                    }
                }
            }

            // Accuracy & Loss metrics
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("FUEL INTEGRATION ERROR", fontSize = 8.5.sp, color = TextSecondaryDark, fontWeight = FontWeight.Bold)
                    Text(
                        text = mode.fuelAccuracyLoss,
                        fontSize = 10.5.sp,
                        color = TextPrimaryDark,
                        lineHeight = 14.sp
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("TRANSIENT BOOST & TORQUE PEAK FIDELITY", fontSize = 8.5.sp, color = TextSecondaryDark, fontWeight = FontWeight.Bold)
                    Text(
                        text = mode.transientPeakLoss,
                        fontSize = 10.5.sp,
                        color = TextPrimaryDark,
                        lineHeight = 14.sp
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("ADAPTER STABILITY & HARDWARE", fontSize = 8.5.sp, color = TextSecondaryDark, fontWeight = FontWeight.Bold)
                    Text(
                        text = mode.stabilityVerdict,
                        fontSize = 10.5.sp,
                        color = TextSecondaryDark,
                        lineHeight = 14.sp
                    )
                }
            }

            Text(
                text = "💡 ${mode.recommendation}",
                fontSize = 10.sp,
                color = accentColor.copy(alpha = 0.9f),
                fontWeight = FontWeight.Medium
            )
        }
    }
}
