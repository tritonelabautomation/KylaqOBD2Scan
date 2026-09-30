package com.example.analysis

import com.example.data.CarpoolCodec
import com.example.data.FuelLogCodec
import com.example.data.RecordTime
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Unit tests for DailyTripCostAggregator.
 * Tests monthly price summaries, day-wise segregation, profit/surplus vs cost math,
 * and commute slot tagging.
 */
class DailyTripCostAggregatorTest {

    private fun istEpoch(year: Int, month: Int, day: Int, hour: Int, min: Int): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
        cal.set(year, month - 1, day, hour, min, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    @Test
    fun `test day-wise grouping and daily cost with profit surplus`() {
        val sep30Morning = istEpoch(2026, 9, 30, 8, 30) // 08:30 IST Morning Commute
        val sep30Evening = istEpoch(2026, 9, 30, 18, 0) // 18:00 IST Evening Commute
        val sep29Morning = istEpoch(2026, 9, 29, 9, 0)  // 09:00 IST

        val trips = listOf(
            DailyTripCostAggregator.TripRecordInput(
                tripId = "trip_sep30_am",
                sessionName = "Office Morning Commute",
                startTimeUtc = RecordTime.stamp(sep30Morning),
                startTimestampMs = sep30Morning,
                durationSeconds = 2400L, // 40 min
                distanceKm = 32.0,
                fuelLiters = 1.2, // 1.2 L @ 100/L = ₹120 fuel
                kmPerLiter = 26.67
            ),
            DailyTripCostAggregator.TripRecordInput(
                tripId = "trip_sep30_pm",
                sessionName = "Home Return Commute",
                startTimeUtc = RecordTime.stamp(sep30Evening),
                startTimestampMs = sep30Evening,
                durationSeconds = 2700L, // 45 min
                distanceKm = 32.0,
                fuelLiters = 1.3, // 1.3 L @ 100/L = ₹130 fuel
                kmPerLiter = 24.6
            ),
            DailyTripCostAggregator.TripRecordInput(
                tripId = "trip_sep29_am",
                sessionName = "Solo Drive to Site",
                startTimeUtc = RecordTime.stamp(sep29Morning),
                startTimestampMs = sep29Morning,
                durationSeconds = 1800L,
                distanceKm = 20.0,
                fuelLiters = 1.0, // 1.0 L = ₹100
                kmPerLiter = 20.0
            )
        )

        // Carpool entries:
        // Sep 30 AM: 2 riders @ ₹80 = ₹160
        // Sep 30 PM: 2 riders @ ₹80 = ₹160
        // Sep 30 Total Earned = ₹320. Total Fuel Spent = 1.2 + 1.3 = 2.5 L = ₹250.
        // Net Day Result = ₹250 - ₹320 = -₹70 (Day Profit / Surplus of ₹70!)
        val carpools = listOf(
            CarpoolCodec.CarpoolEntry(
                idMs = sep30Morning,
                tripId = "trip_sep30_am",
                dateUtc = RecordTime.stamp(sep30Morning),
                distanceKm = 30.0,
                riders = listOf(
                    CarpoolCodec.Rider("Rahul", 80.0),
                    CarpoolCodec.Rider("Priya", 80.0)
                )
            ),
            CarpoolCodec.CarpoolEntry(
                idMs = sep30Evening,
                tripId = "trip_sep30_pm",
                dateUtc = RecordTime.stamp(sep30Evening),
                distanceKm = 30.0,
                riders = listOf(
                    CarpoolCodec.Rider("Rahul", 80.0),
                    CarpoolCodec.Rider("Priya", 80.0)
                )
            )
        )

        val fuelLogs = listOf(
            FuelLogCodec.FuelEntry(
                idMs = sep30Morning,
                dateUtc = RecordTime.stamp(sep30Morning),
                liters = 35.0,
                pricePerL = 100.0,
                odometerKm = 5200.0,
                station = "HPCL",
                grade = FuelLogCodec.GRADE_X95,
                note = "Full tank"
            )
        )

        val result = DailyTripCostAggregator.aggregate(
            trips = trips,
            carpoolEntries = carpools,
            fuelLogs = fuelLogs,
            fuelPricePerL = 100.0,
            nowMs = sep30Evening
        )

        assertEquals(2, result.dayGroups.size)

        // Verify Sep 30 group
        val sep30Group = result.dayGroups.first { it.dateKey == "2026-09-30" }
        assertEquals("30 Sep 2026", sep30Group.displayDate)
        assertEquals("Wednesday", sep30Group.dayOfWeek)
        assertTrue(sep30Group.isToday)
        assertEquals(2, sep30Group.tripCount)
        assertEquals(64.0, sep30Group.totalDayDistanceKm, 0.001)
        assertEquals(2.5, sep30Group.totalDayFuelLiters, 0.001)
        assertEquals(250.0, sep30Group.totalDayFuelCost, 0.001)
        assertEquals(320.0, sep30Group.totalDayCarpoolEarned, 0.001)
        assertEquals(-70.0, sep30Group.netDayCost, 0.001)
        assertTrue(sep30Group.isDayProfit)
        assertEquals(70.0, sep30Group.daySurplusOrProfit, 0.001)
        assertEquals(0.0, sep30Group.dayOutOfPocket, 0.001)
        assertEquals(-70.0 / 64.0, sep30Group.netCostPerKm!!, 0.001)

        // Verify trips within Sep 30
        val amTrip = sep30Group.trips.first { it.tripId == "trip_sep30_am" }
        assertEquals(CommuteComparator.CommuteSlot.MORNING, amTrip.commuteSlot)
        assertEquals("Morning Commute", amTrip.commuteSlotLabel)
        assertEquals(160.0, amTrip.carpoolEarned, 0.001)
        assertEquals(120.0, amTrip.fuelCost, 0.001)
        assertEquals(-40.0, amTrip.netTripCost, 0.001) // ₹40 profit on AM trip
        assertTrue(amTrip.isTripProfit)

        val pmTrip = sep30Group.trips.first { it.tripId == "trip_sep30_pm" }
        assertEquals(CommuteComparator.CommuteSlot.EVENING, pmTrip.commuteSlot)
        assertEquals("Evening Commute", pmTrip.commuteSlotLabel)
        assertEquals(160.0, pmTrip.carpoolEarned, 0.001)
        assertEquals(130.0, pmTrip.fuelCost, 0.001)
        assertEquals(-30.0, pmTrip.netTripCost, 0.001) // ₹30 profit on PM trip
        assertTrue(pmTrip.isTripProfit)

        // Verify Sep 29 group (no carpool -> net cost is full fuel cost)
        val sep29Group = result.dayGroups.first { it.dateKey == "2026-09-29" }
        assertEquals("29 Sep 2026", sep29Group.displayDate)
        assertEquals(1, sep29Group.tripCount)
        assertEquals(20.0, sep29Group.totalDayDistanceKm, 0.001)
        assertEquals(100.0, sep29Group.totalDayFuelCost, 0.001)
        assertEquals(0.0, sep29Group.totalDayCarpoolEarned, 0.001)
        assertEquals(100.0, sep29Group.netDayCost, 0.001)
        assertFalse(sep29Group.isDayProfit)
        assertEquals(100.0, sep29Group.dayOutOfPocket, 0.001)
        assertEquals(5.0, sep29Group.netCostPerKm!!, 0.001) // 100 / 20 = 5.0 Rs/km
    }

    @Test
    fun `test monthly price summary card metrics`() {
        val sep15 = istEpoch(2026, 9, 15, 10, 0)
        val sep20 = istEpoch(2026, 9, 20, 18, 0)

        val trips = listOf(
            DailyTripCostAggregator.TripRecordInput(
                tripId = "t1",
                sessionName = "Commute 1",
                startTimeUtc = RecordTime.stamp(sep15),
                startTimestampMs = sep15,
                distanceKm = 30.0,
                fuelLiters = 1.2
            ),
            DailyTripCostAggregator.TripRecordInput(
                tripId = "t2",
                sessionName = "Commute 2",
                startTimeUtc = RecordTime.stamp(sep20),
                startTimestampMs = sep20,
                distanceKm = 30.0,
                fuelLiters = 1.3
            )
        )

        val carpools = listOf(
            CarpoolCodec.CarpoolEntry(
                idMs = sep15,
                tripId = "t1",
                dateUtc = RecordTime.stamp(sep15),
                distanceKm = 30.0,
                riders = listOf(CarpoolCodec.Rider("Rider1", 100.0), CarpoolCodec.Rider("Rider2", 100.0))
            ),
            CarpoolCodec.CarpoolEntry(
                idMs = sep20,
                tripId = "t2",
                dateUtc = RecordTime.stamp(sep20),
                distanceKm = 30.0,
                riders = listOf(CarpoolCodec.Rider("Rider1", 100.0), CarpoolCodec.Rider("Rider2", 100.0))
            )
        )

        // Refuel log: 40L @ 100/L = ₹4000
        val fuelLogs = listOf(
            FuelLogCodec.FuelEntry(
                idMs = sep15,
                dateUtc = RecordTime.stamp(sep15),
                liters = 40.0,
                pricePerL = 100.0,
                odometerKm = 5000.0,
                station = "IOCL",
                grade = FuelLogCodec.GRADE_X95,
                note = ""
            )
        )

        val result = DailyTripCostAggregator.aggregate(
            trips = trips,
            carpoolEntries = carpools,
            fuelLogs = fuelLogs,
            fuelPricePerL = 100.0,
            nowMs = sep20
        )

        val month = result.currentMonthSummary
        assertEquals("2026-09", month.monthKey)
        assertTrue(month.isCurrentMonth)
        assertEquals(4000.0, month.totalRefuelSpend, 0.001)
        assertEquals(40.0, month.totalRefuelLiters, 0.001)
        assertEquals(2.5, month.totalTripFuelLiters, 0.001)
        assertEquals(250.0, month.totalTripFuelCost, 0.001)
        assertEquals(400.0, month.totalCarpoolEarned, 0.001)
        assertEquals(4000.0, month.primaryFuelSpend, 0.001)
        assertEquals(3600.0, month.netEffectivePrice, 0.001) // 4000 - 400 = 3600
        assertFalse(month.isSurplus)
        assertEquals(3600.0, month.netOutOfPocket, 0.001)
        assertEquals(60.0, month.totalDistanceKm, 0.001)
        assertEquals(60.0, month.netCostPerKm!!, 0.001) // 3600 / 60 = 60.0 Rs/km
        assertEquals(2, month.totalTrips)
        assertEquals(2, month.totalCarpoolRides)
    }

    @Test
    fun `test monthly price summary with no refuels defaults to trip integrated fuel`() {
        val sep15 = istEpoch(2026, 9, 15, 10, 0)
        val trips = listOf(
            DailyTripCostAggregator.TripRecordInput(
                tripId = "t1",
                sessionName = "Commute 1",
                startTimeUtc = RecordTime.stamp(sep15),
                startTimestampMs = sep15,
                distanceKm = 50.0,
                fuelLiters = 2.0 // 2.0 L @ 100 = ₹200
            )
        )

        val carpools = listOf(
            CarpoolCodec.CarpoolEntry(
                idMs = sep15,
                tripId = "t1",
                dateUtc = RecordTime.stamp(sep15),
                distanceKm = 50.0,
                riders = listOf(CarpoolCodec.Rider("Rider1", 150.0), CarpoolCodec.Rider("Rider2", 150.0)) // ₹300 earned
            )
        )

        val result = DailyTripCostAggregator.aggregate(
            trips = trips,
            carpoolEntries = carpools,
            fuelLogs = emptyList(), // no refuel entries
            fuelPricePerL = 100.0,
            nowMs = sep15
        )

        val month = result.currentMonthSummary
        assertEquals(0.0, month.totalRefuelSpend, 0.001)
        assertEquals(2.0, month.totalTripFuelLiters, 0.001)
        assertEquals(200.0, month.totalTripFuelCost, 0.001)
        assertEquals(200.0, month.primaryFuelSpend, 0.001)
        assertEquals(300.0, month.totalCarpoolEarned, 0.001)
        assertEquals(-100.0, month.netEffectivePrice, 0.001) // 200 - 300 = -100
        assertTrue(month.isSurplus)
        assertEquals(100.0, month.surplusAmount, 0.001)
        assertEquals(-2.0, month.netCostPerKm!!, 0.001) // -100 / 50 = -2.0 Rs/km (2 Rs/km profit)
    }
}
