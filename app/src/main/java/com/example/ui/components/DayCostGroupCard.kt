package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.analysis.DailyTripCostAggregator
import com.example.ui.theme.*
import java.util.Locale

/**
 * DAY COST GROUP HEADER CARD (Owner Request 2026-09-30)
 *
 * Groups trips and recordings by IST date and computes overall day cost:
 * Daily Fuel Spent - Daily Carpool Earned = Net Day Cost or Day Profit / Surplus.
 */
@Composable
fun DayCostGroupCard(
    dayGroup: DailyTripCostAggregator.DayTripGroup,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 4.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            // Row 1: Date, Day of Week, Today/Yesterday Badge, Trip Count & Distance
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Event,
                        contentDescription = null,
                        tint = CyberCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = dayGroup.displayDate,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryDark,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "· ${dayGroup.dayOfWeek}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondaryDark,
                        fontSize = 12.sp
                    )

                    if (dayGroup.isToday) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            color = CyberCyan.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "TODAY",
                                color = CyberCyan,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.5.dp)
                            )
                        }
                    } else if (dayGroup.isYesterday) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            color = TextSecondaryDark.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "YESTERDAY",
                                color = TextSecondaryDark,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.5.dp)
                            )
                        }
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${dayGroup.tripCount} trip(s) · ${String.format(Locale.US, "%.1f", dayGroup.totalDayDistanceKm)} km",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondaryDark,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    if (dayGroup.dayStartOdometerKm != null && dayGroup.dayEndOdometerKm != null) {
                        Text(
                            text = "ODO: ${String.format(Locale.US, "%,.1f", dayGroup.dayStartOdometerKm)} → ${String.format(Locale.US, "%,.1f", dayGroup.dayEndOdometerKm)} km",
                            style = MaterialTheme.typography.bodySmall,
                            color = CyberCyan.copy(alpha = 0.85f),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
            Spacer(Modifier.height(6.dp))

            // Row 2: Daily Overall Cost Breakdown
            // Fuel Spent (₹ and L) | Carpool Earned (₹) | Net Day Cost / Profit (₹ and ₹/km)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Fuel info
                Column {
                    Text(
                        text = "FUEL SPENT",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondaryDark
                    )
                    Text(
                        text = "₹${String.format(Locale.US, "%.0f", dayGroup.totalDayFuelCost)} (${String.format(Locale.US, "%.1f", dayGroup.totalDayFuelLiters)} L)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = ElectricAmber,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Carpool info
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "CARPOOL EARNED",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondaryDark
                    )
                    Text(
                        text = "₹${String.format(Locale.US, "%.0f", dayGroup.totalDayCarpoolEarned)}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeonEmerald,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Net Day Result (Cost or Profit)
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (dayGroup.isDayProfit) "DAY PROFIT / SURPLUS" else "NET DAY COST",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (dayGroup.isDayProfit) NeonEmerald else TextSecondaryDark
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val netColor = if (dayGroup.isDayProfit) NeonEmerald else if (dayGroup.netDayCost > 0) ElectricAmber else TextPrimaryDark
                        val netText = if (dayGroup.isDayProfit) {
                            "+₹" + String.format(Locale.US, "%.0f", dayGroup.daySurplusOrProfit)
                        } else {
                            "₹" + String.format(Locale.US, "%.0f", dayGroup.dayOutOfPocket)
                        }
                        Text(
                            text = netText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = netColor,
                            fontFamily = FontFamily.Monospace
                        )
                        dayGroup.netCostPerKm?.let { rate ->
                            Spacer(Modifier.width(4.dp))
                            val rateStr = if (dayGroup.isDayProfit) {
                                "(+₹" + String.format(Locale.US, "%.1f", kotlin.math.abs(rate)) + "/km)"
                            } else {
                                "(₹" + String.format(Locale.US, "%.1f", rate) + "/km)"
                            }
                            Text(
                                text = rateStr,
                                fontSize = 10.sp,
                                color = netColor.copy(alpha = 0.85f),
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}
