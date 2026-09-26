package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.analysis.TripDriveAnalysis
import com.example.analysis.TripFuelSummary
import com.example.ui.theme.*
import java.util.Locale

private val JmCardBg = Color(0xFF1E232E)
private val JmInnerBg = Color(0xFF262C38)
private val JmTeal = Color(0xFF00E5FF)
private val JmGreen = Color(0xFF30D158)
private val JmRed = Color(0xFFFF453A)
private val JmGray = Color(0xFF8E8E93)
private val JmDivider = Color(0xFF323B4B)

/**
 * Top Header Card matching JioMotive with Business vs Personal Trip toggle.
 */
@Composable
fun JioMotiveTripHeader(
    isBusinessTrip: Boolean,
    onToggleCategory: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = JmCardBg
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = if (isBusinessTrip) "Business Trip" else "Personal Trip",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isBusinessTrip) "Tagged for tax / fuel reimbursement" else "Personal daily commute",
                    color = JmGray,
                    fontSize = 11.sp
                )
            }

            // JioMotive Teal Capsule Toggle Switch
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = JmInnerBg,
                border = androidx.compose.foundation.BorderStroke(1.dp, JmTeal.copy(alpha = 0.4f)),
                modifier = Modifier.height(36.dp)
            ) {
                Row(
                    modifier = Modifier.padding(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(if (!isBusinessTrip) JmTeal else Color.Transparent)
                            .clickable { onToggleCategory(false) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = "Personal",
                            tint = if (!isBusinessTrip) Color.Black else JmGray,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(if (isBusinessTrip) JmTeal else Color.Transparent)
                            .clickable { onToggleCategory(true) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Work,
                            contentDescription = "Business",
                            tint = if (isBusinessTrip) Color.Black else JmGray,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Route Card matching JioMotive's Route Section with timeline, speed, halts, and idlings.
 */
@Composable
fun JioMotiveRouteCard(
    dateStr: String,
    startTimeStr: String,
    endTimeStr: String,
    distanceKm: Double,
    durationSec: Long,
    avgSpeedKmh: Double,
    maxSpeedKmh: Double,
    haltsCount: Int = 1,
    haltsDurationSec: Long = 104L,
    idlingsCount: Int = 0,
    fromAddress: String = "Madhapur, Hyderabad",
    toAddress: String = "Destination, Hyderabad",
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(true) }
    var showAddressModal by remember { mutableStateOf<String?>(null) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = JmCardBg
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Route",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = dateStr,
                        color = JmGray,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = "Toggle",
                        tint = JmGray,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
                    // Origin (From)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .border(2.5.dp, JmTeal, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(JmTeal))
                        }

                        Spacer(Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("From", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(startTimeStr, color = JmGray, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            }
                            Text(
                                text = "View More",
                                color = JmTeal,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.clickable { showAddressModal = fromAddress }
                            )
                        }
                    }

                    // Vertical Track Connector & Stats
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Box(
                            modifier = Modifier
                                .padding(start = 11.dp)
                                .width(2.dp)
                                .height(160.dp)
                                .background(JmTeal.copy(alpha = 0.5f))
                        )

                        Spacer(Modifier.width(23.dp))

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            RouteStatRow("Distance", String.format(Locale.US, "%.1f km", distanceKm))
                            RouteStatRow("Duration", fmtDuration(durationSec))
                            RouteStatRow("Average Trip Speed", String.format(Locale.US, "%.2f km/h", avgSpeedKmh))
                            RouteStatRow("Maximum Trip Speed", String.format(Locale.US, "%.0f km/h", maxSpeedKmh))

                            // Halts
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Box(
                                        modifier = Modifier.size(16.dp).clip(CircleShape).background(JmRed),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.PanTool, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
                                    }
                                    Text("Halts ($haltsCount)", color = Color.White, fontSize = 13.sp)
                                }
                                Text(fmtDuration(haltsDurationSec), color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                            }

                            // Idlings
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Box(
                                        modifier = Modifier.size(16.dp).clip(CircleShape).background(Color(0xFF4A5568)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.Info, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
                                    }
                                    Text("Idlings ($idlingsCount)", color = Color.White, fontSize = 13.sp)
                                }
                                Text("0s", color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }

                    // Destination (To)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(JmTeal),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Flag, contentDescription = null, tint = Color.Black, modifier = Modifier.size(14.dp))
                        }

                        Spacer(Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("To", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(endTimeStr, color = JmGray, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            }
                            Text(
                                text = "View More",
                                color = JmTeal,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.clickable { showAddressModal = toAddress }
                            )
                        }
                    }
                }
            }
        }
    }

    // Address Details Dialog
    showAddressModal?.let { addr ->
        AlertDialog(
            onDismissRequest = { showAddressModal = null },
            title = { Text("Location Address", color = Color.White) },
            text = {
                Column {
                    Text(addr, color = Color.White, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Hyderabad, Telangana, India", color = JmGray, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddressModal = null }) {
                    Text("Close", color = JmTeal)
                }
            }
        )
    }
}

/**
 * Driver Performance Card matching JioMotive's 5-Star Driver Performance Section.
 */
@Composable
fun JioMotiveDriverPerformanceCard(
    harshBrakingCount: Int = 0,
    rapidAccelCount: Int = 0,
    overspeedingCount: Int = 0,
    sharpTurnCount: Int = 0,
    scoreRating: Int = 5,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(true) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = JmCardBg
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row with 5 Stars
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Driver performance",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // 5-Star Rating Icons
                    for (i in 1..5) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = if (i <= scoreRating) JmTeal else JmGray.copy(alpha = 0.3f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = "Toggle",
                        tint = JmGray,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    PerformanceItem("Harsh braking", harshBrakingCount)
                    HorizontalDivider(color = JmDivider, thickness = 0.8.dp)
                    PerformanceItem("Rapid Acceleration", rapidAccelCount)
                    HorizontalDivider(color = JmDivider, thickness = 0.8.dp)
                    PerformanceItem("Overspeeding", overspeedingCount)
                    HorizontalDivider(color = JmDivider, thickness = 0.8.dp)
                    PerformanceItem("Sharp Turn", sharpTurnCount)
                }
            }
        }
    }
}

@Composable
private fun PerformanceItem(title: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Teal circular checkmark icon
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, JmTeal, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = JmTeal,
                    modifier = Modifier.size(14.dp)
                )
            }
            Text(
                text = "$title ($count)",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun RouteStatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = JmGray, fontSize = 13.sp)
        Text(value, color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

private fun fmtDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "${h}h ${m}m ${s}s" else "${m}m ${s}s"
}
