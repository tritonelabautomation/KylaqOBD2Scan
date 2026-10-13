package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.analysis.DailyTripCostAggregator
import com.example.ui.theme.*
import java.util.Locale

/**
 * MONTHLY PRICE CARD (Owner Request 2026-09-30)
 *
 * Prominently presents:
 * - Current month fuel spend (₹ and Liters consumed/refilled)
 * - Car pool earnings (₹)
 * - Net effective price (Effective cost / surplus)
 * - Net cost per km (₹/km) with gross vs net comparison
 * - Navigation between months when historical logs exist
 */
@Composable
fun MonthlyPriceCard(
    monthSummary: DailyTripCostAggregator.MonthPriceSummary,
    availableMonths: List<DailyTripCostAggregator.MonthPriceSummary> = emptyList(),
    selectedMonthIndex: Int = 0,
    onPreviousMonth: (() -> Unit)? = null,
    onNextMonth: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    listOf(
                        CyberCyan.copy(alpha = 0.5f),
                        NeonEmerald.copy(alpha = 0.5f),
                        ElectricAmber.copy(alpha = 0.3f)
                    )
                ),
                shape = RoundedCornerShape(18.dp)
            ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Top Month Header with Selector Arrows
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(CyberCyan.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.CalendarMonth,
                            contentDescription = null,
                            tint = CyberCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = monthSummary.displayMonth,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimaryDark
                            )
                            Spacer(Modifier.width(6.dp))
                            if (monthSummary.isCurrentMonth) {
                                Surface(
                                    color = NeonEmerald.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "CURRENT",
                                        color = NeonEmerald,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = "Monthly Fuel & Carpool Ledger",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondaryDark,
                            fontSize = 11.sp
                        )
                    }
                }

                // Month Nav Controls
                if (availableMonths.size > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { onNextMonth?.invoke() },
                            enabled = selectedMonthIndex < availableMonths.size - 1,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.ChevronLeft,
                                contentDescription = "Older Month",
                                tint = if (selectedMonthIndex < availableMonths.size - 1) CyberCyan else TextSecondaryDark.copy(alpha = 0.3f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Text(
                            text = "${selectedMonthIndex + 1}/${availableMonths.size}",
                            fontSize = 11.sp,
                            color = TextSecondaryDark,
                            fontFamily = FontFamily.Monospace
                        )
                        IconButton(
                            onClick = { onPreviousMonth?.invoke() },
                            enabled = selectedMonthIndex > 0,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = "Newer Month",
                                tint = if (selectedMonthIndex > 0) CyberCyan else TextSecondaryDark.copy(alpha = 0.3f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 2x2 Primary Financial Metric Tiles
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Metric 1: Fuel Spend
                MetricTile(
                    title = "FUEL SPENT",
                    primaryValue = "₹" + String.format(Locale.US, "%.0f", monthSummary.primaryFuelSpend),
                    subtitle = buildString {
                        if (monthSummary.totalRefuelLiters > 0.0) {
                            append(String.format(Locale.US, "%.1f L refilled", monthSummary.totalRefuelLiters))
                        } else {
                            append(String.format(Locale.US, "%.1f L burned", monthSummary.totalTripFuelLiters))
                        }
                    },
                    icon = Icons.Default.LocalGasStation,
                    iconColor = ElectricAmber,
                    modifier = Modifier.weight(1f)
                )

                // Metric 2: Carpool Earned
                MetricTile(
                    title = "CARPOOL EARNED",
                    primaryValue = "₹" + String.format(Locale.US, "%.0f", monthSummary.totalCarpoolEarned),
                    subtitle = "${monthSummary.totalCarpoolRides} ride(s) pooled",
                    icon = Icons.Default.Groups,
                    iconColor = NeonEmerald,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Metric 3: Net Effective Price (Cost or Surplus)
                val netLabel = if (monthSummary.isSurplus) "NET SURPLUS" else "NET OUT-OF-POCKET"
                val netVal = if (monthSummary.isSurplus) {
                    "+₹" + String.format(Locale.US, "%.0f", monthSummary.surplusAmount)
                } else {
                    "₹" + String.format(Locale.US, "%.0f", monthSummary.netOutOfPocket)
                }
                val netSub = if (monthSummary.isSurplus) "Trips paid for themselves!" else "Effective cost after pool"
                val netColor = if (monthSummary.isSurplus) NeonEmerald else ElectricAmber

                MetricTile(
                    title = netLabel,
                    primaryValue = netVal,
                    subtitle = netSub,
                    icon = if (monthSummary.isSurplus) Icons.Default.Savings else Icons.Default.AccountBalanceWallet,
                    iconColor = netColor,
                    primaryColor = netColor,
                    modifier = Modifier.weight(1f)
                )

                // Metric 4: Net Cost per km (₹/km)
                val rateVal = monthSummary.netCostPerKm?.let { kmRate ->
                    if (kmRate <= 0.0) {
                        "+₹" + String.format(Locale.US, "%.2f", kotlin.math.abs(kmRate)) + "/km"
                    } else {
                        "₹" + String.format(Locale.US, "%.2f", kmRate) + "/km"
                    }
                } ?: "₹ -- / km"

                val grossRateSub = monthSummary.grossCostPerKm?.let {
                    "Gross: ₹" + String.format(Locale.US, "%.2f", it) + "/km"
                } ?: if (monthSummary.totalDistanceKm > 0.0) {
                    "Total: ${String.format(Locale.US, "%.1f", monthSummary.totalDistanceKm)} km"
                } else if (monthSummary.fuelLogs.isNotEmpty()) {
                    "${monthSummary.fuelLogs.size} fill-up(s)"
                } else {
                    "Total: 0 km"
                }

                val rateColor = when {
                    monthSummary.isSurplus -> NeonEmerald
                    monthSummary.netCostPerKm != null -> CyberCyan
                    else -> TextSecondaryDark
                }

                MetricTile(
                    title = "NET PRICE / KM",
                    primaryValue = rateVal,
                    subtitle = grossRateSub,
                    icon = Icons.AutoMirrored.Filled.TrendingUp,
                    iconColor = rateColor,
                    primaryColor = rateColor,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(10.dp))

            // Bottom Activity Summary Bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.background.copy(alpha = 0.6f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (monthSummary.totalTrips > 0) Icons.Default.DirectionsCar else Icons.Default.LocalGasStation,
                            contentDescription = null,
                            tint = CyberCyan,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (monthSummary.totalTrips > 0) {
                                "${monthSummary.totalTrips} drives · ${String.format(Locale.US, "%.1f", monthSummary.totalDistanceKm)} km"
                            } else if (monthSummary.totalDistanceKm > 0.0) {
                                "${monthSummary.fuelLogs.size} fill-up(s) · ${String.format(Locale.US, "%.1f", monthSummary.totalDistanceKm)} km (Odo)"
                            } else if (monthSummary.fuelLogs.isNotEmpty()) {
                                "${monthSummary.fuelLogs.size} fill-up(s) · ${String.format(Locale.US, "%.1f L refilled", monthSummary.totalRefuelLiters)}"
                            } else {
                                "0 drives · 0.0 km"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondaryDark,
                            fontSize = 11.sp
                        )
                    }

                    monthSummary.avgKmPerLiter?.let { kmL ->
                        Text(
                            text = String.format(Locale.US, "%.1f km/L avg", kmL),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = NeonEmerald,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricTile(
    title: String,
    primaryValue: String,
    subtitle: String,
    icon: ImageVector,
    iconColor: Color,
    primaryColor: Color = TextPrimaryDark,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier.padding(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondaryDark
                )
                Icon(
                    icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(15.dp)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = primaryValue,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = primaryColor,
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondaryDark,
                fontSize = 10.sp,
                maxLines = 1
            )
        }
    }
}
