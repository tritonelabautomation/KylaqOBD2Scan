package com.example.analysis

import com.example.data.CarpoolCodec
import com.example.data.FuelLogCodec
import com.example.data.RecordTime
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * DAILY TRIP COST & MONTHLY PRICE AGGREGATOR
 *
 * Computes:
 * 1. Monthly Price Card: Month summary of fuel spend (₹ and Litres consumed/refilled),
 *    car pool earnings, net effective price (effective cost / surplus) and ₹/km.
 * 2. Day-Wise Segregation: Groups trips and recordings by IST date with a dedicated
 *    Day Header Card displaying overall cost for each particular day
 *    (Fuel Spent - Carpool Earned = Net Day Cost / Profit) and commute tagging.
 *
 * Pure, robust and 100% JVM unit-testable.
 */
object DailyTripCostAggregator {

    /** Fallback fuel price per liter (INR) when no fuel log is available. */
    const val DEFAULT_FUEL_PRICE_INR = 100.0

    /** Škoda Kylaq 50L fuel tank capacity. */
    const val TANK_CAPACITY_LITERS = 50.0

    data class TripRecordInput(
        val tripId: String,
        val sessionName: String,
        val startTimeUtc: String,
        val startTimestampMs: Long,
        val durationSeconds: Long = 0L,
        val distanceKm: Double = 0.0,
        val fuelLiters: Double = 0.0,
        val kmPerLiter: Double? = null,
        val startFuelPercent: Double? = null,
        val endFuelPercent: Double? = null,
        val fuelDeltaPercent: Double? = null,
        val isRefuelBrimEvent: Boolean = false,
        val transactionCount: Int = 0
    )

    data class DayTripItem(
        val tripId: String,
        val title: String,
        val startMs: Long,
        val timeLabel: String,
        val commuteSlot: CommuteComparator.CommuteSlot,
        val commuteSlotLabel: String,
        val distanceKm: Double,
        val durationSeconds: Long,
        val fuelLiters: Double,
        val fuelCost: Double,
        val kmPerLiter: Double?,
        val startFuelPercent: Double?,
        val endFuelPercent: Double?,
        val fuelDeltaPercent: Double?,
        val isRefuelBrim: Boolean,
        val carpoolEntry: CarpoolCodec.CarpoolEntry?,
        val carpoolEarned: Double,
        val carpoolRiderCount: Int,
        val riderNames: List<String>,
        val netTripCost: Double, // fuelCost - carpoolEarned
        val isTripProfit: Boolean,
        val transactionCount: Int = 0
    )

    data class DayTripGroup(
        val dateKey: String, // "yyyy-MM-dd"
        val displayDate: String, // "30 Sep 2026"
        val dayOfWeek: String, // "Wednesday"
        val isToday: Boolean,
        val isYesterday: Boolean,
        val totalDayDistanceKm: Double,
        val totalDayDurationSeconds: Long,
        val totalDayFuelLiters: Double,
        val totalDayFuelCost: Double,
        val totalDayCarpoolEarned: Double,
        val netDayCost: Double, // totalDayFuelCost - totalDayCarpoolEarned
        val isDayProfit: Boolean,
        val daySurplusOrProfit: Double, // maxOf(0.0, totalDayCarpoolEarned - totalDayFuelCost)
        val dayOutOfPocket: Double, // maxOf(0.0, totalDayFuelCost - totalDayCarpoolEarned)
        val netCostPerKm: Double?,
        val grossCostPerKm: Double?,
        val tripCount: Int,
        val trips: List<DayTripItem>
    )

    data class MonthPriceSummary(
        val monthKey: String, // "yyyy-MM"
        val displayMonth: String, // "September 2026"
        val isCurrentMonth: Boolean,
        val totalRefuelSpend: Double, // From fuel fill-up logs
        val totalRefuelLiters: Double, // From fuel fill-up logs
        val totalTripFuelLiters: Double, // Integrated from OBD trips
        val totalTripFuelCost: Double, // Trip liters * price
        val primaryFuelSpend: Double, // totalRefuelSpend if > 0 else totalTripFuelCost
        val primaryFuelLiters: Double, // totalRefuelLiters if > 0 else totalTripFuelLiters
        val totalCarpoolEarned: Double, // Total carpool earnings
        val netEffectivePrice: Double, // primaryFuelSpend - totalCarpoolEarned
        val isSurplus: Boolean, // totalCarpoolEarned >= primaryFuelSpend
        val surplusAmount: Double, // maxOf(0.0, totalCarpoolEarned - primaryFuelSpend)
        val netOutOfPocket: Double, // maxOf(0.0, primaryFuelSpend - totalCarpoolEarned)
        val totalDistanceKm: Double,
        val netCostPerKm: Double?,
        val grossCostPerKm: Double?,
        val totalTrips: Int,
        val totalCarpoolRides: Int,
        val avgKmPerLiter: Double?
    )

    data class AggregationResult(
        val currentMonthSummary: MonthPriceSummary,
        val allMonthSummaries: List<MonthPriceSummary>,
        val dayGroups: List<DayTripGroup>
    )

    /**
     * Determines whether two epoch timestamps fall on the same IST calendar day.
     */
    fun isSameDayIst(ts1: Long, ts2: Long): Boolean {
        val d1 = RecordTime.format("yyyy-MM-dd", ts1)
        val d2 = RecordTime.format("yyyy-MM-dd", ts2)
        return d1 == d2
    }

    /**
     * Aggregates trips, carpool entries, and fuel logs into Day Groups and Monthly Summaries.
     */
    fun aggregate(
        trips: List<TripRecordInput>,
        carpoolEntries: List<CarpoolCodec.CarpoolEntry>,
        fuelLogs: List<FuelLogCodec.FuelEntry>,
        fuelPricePerL: Double? = null,
        nowMs: Long = System.currentTimeMillis()
    ): AggregationResult {
        val effectivePricePerL = fuelPricePerL?.takeIf { it > 0.0 }
            ?: fuelLogs.maxByOrNull { it.idMs }?.pricePerL?.takeIf { it > 0.0 }
            ?: DEFAULT_FUEL_PRICE_INR

        val todayDateKey = RecordTime.format("yyyy-MM-dd", nowMs)
        val yesterdayDateKey = RecordTime.format("yyyy-MM-dd", nowMs - 86_400_000L)
        val currentMonthKey = RecordTime.format("yyyy-MM", nowMs)

        // Map carpool entries by tripId, and also build trip windows for time-based matching
        val carpoolByTripId = carpoolEntries.filter { it.tripId != null }.associateBy { it.tripId!! }
        val tripWindows = trips.map {
            CarpoolCodec.TripWindow(
                tripId = it.tripId,
                startMs = it.startTimestampMs,
                endMs = if (it.durationSeconds > 0) it.startTimestampMs + it.durationSeconds * 1000L else null
            )
        }

        // Convert each TripRecordInput into a DayTripItem
        val dayTripItems = trips.map { trip ->
            val startMs = trip.startTimestampMs
            val matchedCarpool = carpoolByTripId[trip.tripId]
                ?: com.example.data.CarpoolCodec.tripLinkFor(startMs, tripWindows)?.let { linkedTripId ->
                    carpoolByTripId[linkedTripId]
                }
                ?: carpoolEntries.firstOrNull { e ->
                    val eMs = CarpoolCodec.whenMs(e) ?: e.idMs
                    // Match within 15 minutes if unlinked
                    kotlin.math.abs(eMs - startMs) <= 15 * 60 * 1000L
                }

            val carpoolEarned = matchedCarpool?.earned ?: 0.0
            val riderNames = matchedCarpool?.riders?.map { it.name } ?: emptyList()
            val riderCount = matchedCarpool?.riders?.size ?: 0

            // Fuel liters derivation: prefer integrated liters, fallback to tank delta if refuel didn't occur
            val derivedLiters = if (trip.fuelLiters > 0.001) {
                trip.fuelLiters
            } else if (trip.fuelDeltaPercent != null && trip.fuelDeltaPercent < 0) {
                kotlin.math.abs(trip.fuelDeltaPercent) * TANK_CAPACITY_LITERS / 100.0
            } else {
                0.0
            }

            val fuelCost = derivedLiters * effectivePricePerL
            val netCost = fuelCost - carpoolEarned
            val isProfit = carpoolEarned > fuelCost

            val commuteSlot = CommuteComparator.classifySlot(startMs)
            val slotLabel = when (commuteSlot) {
                CommuteComparator.CommuteSlot.MORNING -> "Morning Commute"
                CommuteComparator.CommuteSlot.EVENING -> "Evening Commute"
                CommuteComparator.CommuteSlot.OFF_PEAK -> "Off-Peak Drive"
            }

            DayTripItem(
                tripId = trip.tripId,
                title = trip.sessionName,
                startMs = startMs,
                timeLabel = RecordTime.format("hh:mm a", startMs),
                commuteSlot = commuteSlot,
                commuteSlotLabel = slotLabel,
                distanceKm = trip.distanceKm,
                durationSeconds = trip.durationSeconds,
                fuelLiters = derivedLiters,
                fuelCost = fuelCost,
                kmPerLiter = trip.kmPerLiter ?: if (trip.distanceKm > 0.1 && derivedLiters > 0.01) trip.distanceKm / derivedLiters else null,
                startFuelPercent = trip.startFuelPercent,
                endFuelPercent = trip.endFuelPercent,
                fuelDeltaPercent = trip.fuelDeltaPercent,
                isRefuelBrim = trip.isRefuelBrimEvent,
                carpoolEntry = matchedCarpool,
                carpoolEarned = carpoolEarned,
                carpoolRiderCount = riderCount,
                riderNames = riderNames,
                netTripCost = netCost,
                isTripProfit = isProfit,
                transactionCount = trip.transactionCount
            )
        }

        // Group trips by IST Date ("yyyy-MM-dd")
        val groupedByDate = dayTripItems.groupBy { item ->
            RecordTime.format("yyyy-MM-dd", item.startMs)
        }

        val dayGroups = groupedByDate.map { (dateKey, items) ->
            val firstStartMs = items.first().startMs
            val displayDate = RecordTime.format("d MMM yyyy", firstStartMs)
            val dayOfWeek = RecordTime.format("EEEE", firstStartMs)
            val isToday = dateKey == todayDateKey
            val isYesterday = dateKey == yesterdayDateKey

            val totalDistance = items.sumOf { it.distanceKm }
            val totalDuration = items.sumOf { it.durationSeconds }
            val totalFuelLiters = items.sumOf { it.fuelLiters }
            val totalFuelCost = items.sumOf { it.fuelCost }
            val totalCarpoolEarned = items.sumOf { it.carpoolEarned }
            val netDayCost = totalFuelCost - totalCarpoolEarned
            val isProfit = totalCarpoolEarned > totalFuelCost

            val netCostPerKm = if (totalDistance > 0.05) netDayCost / totalDistance else null
            val grossCostPerKm = if (totalDistance > 0.05) totalFuelCost / totalDistance else null

            DayTripGroup(
                dateKey = dateKey,
                displayDate = displayDate,
                dayOfWeek = dayOfWeek,
                isToday = isToday,
                isYesterday = isYesterday,
                totalDayDistanceKm = totalDistance,
                totalDayDurationSeconds = totalDuration,
                totalDayFuelLiters = totalFuelLiters,
                totalDayFuelCost = totalFuelCost,
                totalDayCarpoolEarned = totalCarpoolEarned,
                netDayCost = netDayCost,
                isDayProfit = isProfit,
                daySurplusOrProfit = if (isProfit) totalCarpoolEarned - totalFuelCost else 0.0,
                dayOutOfPocket = if (!isProfit) totalFuelCost - totalCarpoolEarned else 0.0,
                netCostPerKm = netCostPerKm,
                grossCostPerKm = grossCostPerKm,
                tripCount = items.size,
                trips = items.sortedByDescending { it.startMs }
            )
        }.sortedByDescending { it.dateKey }

        // Monthly Aggregations
        val fuelLogsByMonth = fuelLogs.groupBy {
            RecordTime.format("yyyy-MM", it.idMs)
        }
        val carpoolByMonth = carpoolEntries.groupBy {
            RecordTime.format("yyyy-MM", CarpoolCodec.whenMs(it) ?: it.idMs)
        }
        val tripsByMonth = dayTripItems.groupBy {
            RecordTime.format("yyyy-MM", it.startMs)
        }

        val allMonthKeys = (fuelLogsByMonth.keys + carpoolByMonth.keys + tripsByMonth.keys + setOf(currentMonthKey))
            .filter { it.isNotBlank() }
            .distinct()
            .sortedDescending()

        val allMonthSummaries = allMonthKeys.map { monthKey ->
            val monthFuelLogs = fuelLogsByMonth[monthKey] ?: emptyList()
            val monthCarpool = carpoolByMonth[monthKey] ?: emptyList()
            val monthTrips = tripsByMonth[monthKey] ?: emptyList()

            val refuelSpend = monthFuelLogs.sumOf { it.totalCost }
            val refuelLiters = monthFuelLogs.sumOf { it.liters }

            val tripFuelLiters = monthTrips.sumOf { it.fuelLiters }
            val tripFuelCost = monthTrips.sumOf { it.fuelCost }
            val tripDistance = monthTrips.sumOf { it.distanceKm }

            val carpoolEarned = monthCarpool.sumOf { it.earned }

            // Primary fuel spend logic: cash-basis refuel spend if recorded, else OBD-integrated trip fuel cost
            val primaryFuelSpend = if (refuelSpend > 0.0) refuelSpend else tripFuelCost
            val primaryFuelLiters = if (refuelLiters > 0.0) refuelLiters else tripFuelLiters

            val netEffective = primaryFuelSpend - carpoolEarned
            val isSurplus = carpoolEarned >= primaryFuelSpend

            val netCostPerKm = if (tripDistance > 0.05) netEffective / tripDistance else null
            val grossCostPerKm = if (tripDistance > 0.05) primaryFuelSpend / tripDistance else null
            val avgKmL = if (tripDistance > 0.1 && tripFuelLiters > 0.05) tripDistance / tripFuelLiters else null

            // Display month name (e.g., "September 2026")
            val sampleMs = monthTrips.firstOrNull()?.startMs
                ?: monthFuelLogs.firstOrNull()?.idMs
                ?: monthCarpool.firstOrNull()?.let { CarpoolCodec.whenMs(it) ?: it.idMs }
                ?: RecordTime.parseMillis("${monthKey}-01T00:00:00.000+05:30")
                ?: nowMs

            val displayMonth = RecordTime.format("MMMM yyyy", sampleMs)

            MonthPriceSummary(
                monthKey = monthKey,
                displayMonth = displayMonth,
                isCurrentMonth = monthKey == currentMonthKey,
                totalRefuelSpend = refuelSpend,
                totalRefuelLiters = refuelLiters,
                totalTripFuelLiters = tripFuelLiters,
                totalTripFuelCost = tripFuelCost,
                primaryFuelSpend = primaryFuelSpend,
                primaryFuelLiters = primaryFuelLiters,
                totalCarpoolEarned = carpoolEarned,
                netEffectivePrice = netEffective,
                isSurplus = isSurplus,
                surplusAmount = if (isSurplus) carpoolEarned - primaryFuelSpend else 0.0,
                netOutOfPocket = if (!isSurplus) primaryFuelSpend - carpoolEarned else 0.0,
                totalDistanceKm = tripDistance,
                netCostPerKm = netCostPerKm,
                grossCostPerKm = grossCostPerKm,
                totalTrips = monthTrips.size,
                totalCarpoolRides = monthCarpool.size,
                avgKmPerLiter = avgKmL
            )
        }

        val currentMonthSummary = allMonthSummaries.firstOrNull { it.monthKey == currentMonthKey }
            ?: MonthPriceSummary(
                monthKey = currentMonthKey,
                displayMonth = RecordTime.format("MMMM yyyy", nowMs),
                isCurrentMonth = true,
                totalRefuelSpend = 0.0,
                totalRefuelLiters = 0.0,
                totalTripFuelLiters = 0.0,
                totalTripFuelCost = 0.0,
                primaryFuelSpend = 0.0,
                primaryFuelLiters = 0.0,
                totalCarpoolEarned = 0.0,
                netEffectivePrice = 0.0,
                isSurplus = false,
                surplusAmount = 0.0,
                netOutOfPocket = 0.0,
                totalDistanceKm = 0.0,
                netCostPerKm = null,
                grossCostPerKm = null,
                totalTrips = 0,
                totalCarpoolRides = 0,
                avgKmPerLiter = null
            )

        return AggregationResult(
            currentMonthSummary = currentMonthSummary,
            allMonthSummaries = allMonthSummaries,
            dayGroups = dayGroups
        )
    }
}
