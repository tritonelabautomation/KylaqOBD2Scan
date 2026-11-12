package com.example.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class CommuteComparatorTest {

    private fun istEpoch(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
        cal.set(year, month - 1, day, hour, minute, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    @Test
    fun `commute slot classifies morning and evening commutes accurately in IST`() {
        val morningTs = istEpoch(2026, 9, 28, 7, 51)
        val eveningTs = istEpoch(2026, 9, 28, 17, 30)
        val offPeakTs = istEpoch(2026, 9, 28, 13, 0)

        assertEquals(CommuteComparator.CommuteSlot.MORNING, CommuteComparator.classifySlot(morningTs))
        assertEquals(CommuteComparator.CommuteSlot.EVENING, CommuteComparator.classifySlot(eveningTs))
        assertEquals(CommuteComparator.CommuteSlot.OFF_PEAK, CommuteComparator.classifySlot(offPeakTs))
    }

    @Test
    fun `apples to apples comparison calculates accurate payload and fuel deltas`() {
        val currentTrip = CommuteComparator.TripCommuteProfile(
            tripId = "trip_4d347517",
            title = "Morning Commute 5 Pax",
            startTimestampMs = istEpoch(2026, 9, 28, 7, 51),
            distanceKm = 30.9,
            durationSeconds = 5400L, // 1h 30m
            avgSpeedKmh = 21.0,
            fuelLiters = 3.03,
            kmPerLiter = 10.2,
            occupantCount = 5,
            payloadKg = 375.0,
            meanTorqueNm = 112.0,
            peakTorqueNm = 165.0,
            boostActivePct = 34.0,
            sportShiftsPct = 28.0,
            avgUpshiftRpm = 2650.0
        )

        val soloTrip1 = CommuteComparator.TripCommuteProfile(
            tripId = "trip_solo_1",
            title = "Morning Commute Solo A",
            startTimestampMs = istEpoch(2026, 9, 26, 8, 0),
            distanceKm = 31.2,
            durationSeconds = 4320L, // 1h 12m
            avgSpeedKmh = 26.0,
            fuelLiters = 2.6,
            kmPerLiter = 12.0,
            occupantCount = 1,
            payloadKg = 75.0,
            meanTorqueNm = 96.0,
            peakTorqueNm = 145.0,
            boostActivePct = 21.0,
            sportShiftsPct = 8.0,
            avgUpshiftRpm = 2150.0
        )

        val soloTrip2 = CommuteComparator.TripCommuteProfile(
            tripId = "trip_solo_2",
            title = "Morning Commute Solo B",
            startTimestampMs = istEpoch(2026, 9, 25, 7, 45),
            distanceKm = 30.5,
            durationSeconds = 4500L,
            avgSpeedKmh = 24.4,
            fuelLiters = 2.7,
            kmPerLiter = 11.3,
            occupantCount = 1,
            payloadKg = 75.0,
            meanTorqueNm = 98.0,
            peakTorqueNm = 148.0,
            boostActivePct = 23.0,
            sportShiftsPct = 10.0,
            avgUpshiftRpm = 2200.0
        )

        val comparison = CommuteComparator.compare(
            currentTrip = currentTrip,
            historicalTrips = listOf(soloTrip1, soloTrip2)
        )

        assertEquals(CommuteComparator.CommuteSlot.MORNING, comparison.slot)
        assertEquals(2, comparison.matchingHistoricalTripsCount)
        assertNotNull(comparison.soloBaseline)

        // Payload delta: 375 kg - 75 kg = +300 kg
        assertEquals(300.0, comparison.payloadDeltaKg!!, 1e-3)

        // Fuel economy delta: 10.2 - ~11.65 = ~ -1.45 km/L
        assertTrue("Fuel economy delta should be negative", comparison.kmPerLiterDelta!! < 0.0)

        // Torque delta: 112 Nm - 97 Nm = +15 Nm
        assertEquals(15.0, comparison.meanTorqueDeltaNm!!, 1.0)

        // Sport shifts delta: 28% - 9% = +19%
        assertEquals(19.0, comparison.sportShiftsPctDelta!!, 1.0)

        // Summary insight contains key details
        assertTrue(comparison.summaryInsight.contains("5 occupants"))
        assertTrue(comparison.summaryInsight.contains("previous morning commute"))
    }
}
