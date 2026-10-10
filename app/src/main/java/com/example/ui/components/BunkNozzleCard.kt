package com.example.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.BunkNozzleStat
import com.example.data.BunkNozzleStore
import com.example.data.RefuelBunkRecord
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.ElectricAmber
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.TextPrimaryDark
import com.example.ui.theme.TextSecondaryDark
import com.example.ui.theme.WarningRed
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BunkNozzleCard(
    onOpenLogger: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var refreshKey by remember { mutableStateOf(0) }
    val stats = remember(refreshKey) { BunkNozzleStore.statsPerBunkAndNozzle(context) }
    val records = remember(refreshKey) { BunkNozzleStore.getAll(context) }
    var expandedHistory by remember { mutableStateOf(false) }
    var selectedPhotoUri by remember { mutableStateOf<String?>(null) }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, CyberCyan.copy(alpha = 0.4f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header
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
                            Icons.Default.EvStation,
                            contentDescription = null,
                            tint = CyberCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            "BUNK NOZZLE AUTO-CUT PROFILER",
                            color = CyberCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            "Per-station & nozzle cutoff calibration",
                            color = TextSecondaryDark,
                            fontSize = 10.sp
                        )
                    }
                }

                Button(
                    onClick = onOpenLogger,
                    colors = ButtonDefaults.buttonColors(containerColor = NeonEmerald),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = Color.Black, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Refuel Log", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Key Takeaway / Insight banner
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = DarkSurfaceElevated,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Insights, contentDescription = null, tint = ElectricAmber, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Auto-Cut Level Analysis",
                            color = ElectricAmber,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        if (stats.size >= 2) {
                            val minCut = stats.minOf { it.minAutoCutPercent }
                            val maxCut = stats.maxOf { it.maxAutoCutPercent }
                            "Different petrol bunks and dispenser nozzles exhibit distinct auto-cut trigger thresholds (observed range: ${String.format(Locale.US, "%.1f%% - %.1f%%", minCut, maxCut)}). Tracking nozzle profiles calibrates tank fill accuracy."
                        } else {
                            "Dispenser nozzles vary in backpressure sensitivity, causing auto-cutoffs between ~96.5% and 100.0%. Logging refuels per bunk & nozzle builds accurate cutoff profiles for your 50.0L Kylaq tank."
                        },
                        color = TextPrimaryDark,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }

            // High-level summary metrics
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricMiniTile(
                    label = "STATIONS PROFILED",
                    value = "${stats.map { it.stationName }.distinct().size}",
                    color = CyberCyan,
                    modifier = Modifier.weight(1f)
                )
                MetricMiniTile(
                    label = "NOZZLES TRACKED",
                    value = "${stats.size}",
                    color = NeonEmerald,
                    modifier = Modifier.weight(1f)
                )
                MetricMiniTile(
                    label = "AVG CUT-OFF %",
                    value = if (stats.isNotEmpty()) String.format(Locale.US, "%.1f%%", stats.map { it.avgAutoCutPercent }.average()) else "--",
                    color = ElectricAmber,
                    modifier = Modifier.weight(1f)
                )
            }

            // Per-Bunk & Nozzle List
            if (stats.isNotEmpty()) {
                Text(
                    "PROFILED NOZZLE CUTOFF SIGNATURES",
                    color = TextSecondaryDark,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    stats.forEach { stat ->
                        BunkNozzleItemRow(stat)
                    }
                }
            }

            // Expandable Recent Logs & Photo Evidence
            if (records.isNotEmpty()) {
                HorizontalDivider(color = DarkBorder, modifier = Modifier.padding(vertical = 2.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expandedHistory = !expandedHistory }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (expandedHistory) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = CyberCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Recent Fill Records (${records.size})",
                            color = CyberCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        if (expandedHistory) "Collapse" else "Show Details & Photos",
                        color = TextSecondaryDark,
                        fontSize = 10.sp
                    )
                }

                if (expandedHistory) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        records.take(6).forEach { rec ->
                            RefuelRecordDetailRow(rec, onPhotoClick = { selectedPhotoUri = it })
                        }
                    }
                }
            }
        }
    }

    // Photo Preview Modal Dialog
    selectedPhotoUri?.let { uriStr ->
        AlertDialog(
            onDismissRequest = { selectedPhotoUri = null },
            confirmButton = {
                TextButton(onClick = { selectedPhotoUri = null }) {
                    Text("Close", color = CyberCyan)
                }
            },
            title = { Text("Photo Verification", fontSize = 14.sp, fontWeight = FontWeight.Bold) },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = Uri.parse(uriStr),
                        contentDescription = "Refuel / MID Photo",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        )
    }
}

@Composable
private fun MetricMiniTile(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = DarkSurfaceElevated,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, color = TextSecondaryDark, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(value, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BunkNozzleItemRow(stat: BunkNozzleStat) {
    val spread = stat.maxAutoCutPercent - stat.minAutoCutPercent
    val isConsistent = spread <= 0.8

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = DarkSurfaceElevated,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isConsistent) NeonEmerald.copy(alpha = 0.3f) else ElectricAmber.copy(alpha = 0.3f)
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
                    Text(
                        stat.stationName,
                        color = TextPrimaryDark,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = CyberCyan.copy(alpha = 0.15f)
                    ) {
                        Text(
                            stat.nozzleId,
                            color = CyberCyan,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (isConsistent) NeonEmerald.copy(alpha = 0.15f) else ElectricAmber.copy(alpha = 0.15f)
                ) {
                    Text(
                        if (isConsistent) "STABLE AUTO-CUT" else "VARIABLE CUTOFF",
                        color = if (isConsistent) NeonEmerald else ElectricAmber,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }

            // Cutoff Metrics
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Auto-Cut Level", color = TextSecondaryDark, fontSize = 9.sp)
                    Text(
                        String.format(Locale.US, "%.1f%% (spread: %.1f%%)", stat.avgAutoCutPercent, spread),
                        color = ElectricAmber,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text("PID Float Error", color = TextSecondaryDark, fontSize = 9.sp)
                    val sign = if (stat.avgFloatErrorPct > 0) "+" else ""
                    Text(
                        String.format(Locale.US, "%s%.1f%% error", sign, stat.avgFloatErrorPct),
                        color = if (kotlin.math.abs(stat.avgFloatErrorPct) <= 3.0) NeonEmerald else WarningRed,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text("Calibrated Tank", color = TextSecondaryDark, fontSize = 9.sp)
                    Text(
                        String.format(Locale.US, "%.1f L (%d fills)", stat.avgCalibratedTankL, stat.fillCount),
                        color = CyberCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun RefuelRecordDetailRow(
    rec: RefuelBunkRecord,
    onPhotoClick: (String) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = DarkSurfaceElevated.copy(alpha = 0.7f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "${rec.stationName} (${rec.nozzleId}) • ${rec.fuelGrade}",
                    color = TextPrimaryDark,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    String.format(Locale.US, "%.2f L @ ₹%.2f", rec.pumpLitres, rec.pricePerL),
                    color = NeonEmerald,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Cutoff: ${String.format(Locale.US, "%.1f%%", rec.autoCutPercent)} (Start: ${String.format(Locale.US, "%.1f%%", rec.startLevelPercent)})",
                    color = ElectricAmber,
                    fontSize = 10.sp
                )
                Text(
                    "Float: ${String.format(Locale.US, "%.2f L (err: %+.1f%%)", rec.floatDeltaLitres, rec.floatErrorPct)}",
                    color = TextSecondaryDark,
                    fontSize = 10.sp
                )
            }

            // Photo Badges if present
            if (rec.receiptPhotoUri != null || rec.midPhotoUri != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    rec.receiptPhotoUri?.let { uri ->
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = CyberCyan.copy(alpha = 0.15f),
                            modifier = Modifier.clickable { onPhotoClick(uri) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.ReceiptLong, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(3.dp))
                                Text("Receipt OCR Photo", color = CyberCyan, fontSize = 9.sp)
                            }
                        }
                    }

                    rec.midPhotoUri?.let { uri ->
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = ElectricAmber.copy(alpha = 0.15f),
                            modifier = Modifier.clickable { onPhotoClick(uri) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Speed, contentDescription = null, tint = ElectricAmber, modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(3.dp))
                                Text("MID Photo", color = ElectricAmber, fontSize = 9.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
