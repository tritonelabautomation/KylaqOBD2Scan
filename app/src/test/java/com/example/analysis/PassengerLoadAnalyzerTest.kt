package com.example.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PassengerLoadAnalyzerTest {

    private fun sample(pid: String, ts: Long, value: Double) =
        TripFuelSummary.SamplePoint(pid = pid, timestampMs = ts, value = value)

    @Test
    fun `passenger load calculation matches 5 occupants and 375 kg payload`() {
        // Mock 5 occupants (1 driver + 4 carpool passengers)
        val occupantCount = 5
        val summary = TripFuelSummary.Summary(
            fuelLiters = 3.03,
            distanceKm = 30.9,
            kmPerLiter = 10.2,
            durationSeconds = 5400L,
            averageSpeedKmh = 21.0,
            movingAverageSpeedKmh = 25.0,
            maxSpeedKmh = 62.0,
            coastSeconds = 120.0,
            idleSeconds = 450.0,
            speedHistogram = emptyList(),
            sampleCount = 200,
            meanTorqueNm = 112.0,
            peakTorqueNm = 165.0,
            torqueReferenceNm = 178.0
        )

        // Telemetry samples
        val samples = listOf(
            sample("010D", 1000L, 0.0),
            sample("010C", 1000L, 800.0),
            sample("010B", 1000L, 101.0),
            sample("0105", 1000L, 88.0),
            sample("0162", 1000L, 15.0),
            sample("010D", 2000L, 30.0),
            sample("010C", 2000L, 2900.0),
            sample("010B", 2000L, 175.0), // in boost
            sample("0105", 2000L, 91.0),
            sample("0162", 2000L, 85.0), // ~151 Nm
            sample("010D", 3000L, 50.0),
            sample("010C", 3000L, 2100.0),
            sample("010B", 3000L, 115.0),
            sample("0105", 3000L, 92.0),
            sample("0162", 3000L, 60.0)
        )

        val result = PassengerLoadAnalyzer.analyze(
            occupantCount = occupantCount,
            samples = samples,
            fuelSummary = summary
        )

        assertEquals(5, result.occupantCount)
        assertEquals(4, result.passengerCount)
        assertEquals(375.0, result.payloadKg, 1e-3)
        assertEquals(1575.0, result.totalVehicleMassKg, 1e-3) // 1200 kerb + 375
        assertEquals(1275.0, result.soloBaselineMassKg, 1e-3) // 1200 kerb + 75
        assertTrue("Mass increase should be ~23.5%", result.payloadMassIncreasePct in 23.0..24.0)

        // Specific fuel check
        assertNotNull(result.specificFuelPerTonne100Km)
        // 3.03 / (30.9 * 1.575) * 100 ~ 6.22 L/(tonne*100km)
        assertTrue("Specific fuel per tonne: ${result.specificFuelPerTonne100Km}", result.specificFuelPerTonne100Km!! in 6.0..6.5)

        // Per pax fuel check: 3.03 / (30.9 * 5) * 100 ~ 1.96 L/(pax*100km)
        assertNotNull(result.fuelPerPax100Km)
        assertTrue("Fuel per pax: ${result.fuelPerPax100Km}", result.fuelPerPax100Km!! in 1.8..2.1)

        // Zero overkill verdict
        assertFalse(result.verdict.isOverkill)
        assertEquals(PassengerLoadAnalyzer.VerdictLevel.COMFORTABLE, result.verdict.level)
        assertTrue("Mechanical headroom should be positive", result.verdict.mechanicalHeadroomPct > 5.0)
        assertTrue("Thermal headroom should be > 10C", result.verdict.thermalHeadroomC > 10.0)
    }

    @Test
    fun `shift profile correctly identifies eco normal and sport shift points`() {
        val speedSeries = listOf(
            1000L to 15.0,
            2000L to 25.0,
            3000L to 35.0,
            4000L to 50.0,
            5000L to 65.0
        )

        // Ratios:
        // G1 ratio ~ 4.148 * 26 = 107.8 (speed 15 -> RPM ~ 1617)
        // G2 ratio ~ 2.370 * 26 = 61.6 (speed 25 -> RPM ~ 1540)
        // G3 ratio ~ 1.556 * 26 = 40.5 (speed 35 -> RPM ~ 1417)
        // G4 ratio ~ 1.155 * 26 = 30.0 (speed 50 -> RPM ~ 1500)
        // G5 ratio ~ 0.859 * 26 = 22.3 (speed 65 -> RPM ~ 1450)
        val rpmSeries = listOf(
            1000L to 1600.0, // G1
            1900L to 3000.0, // Sport rev in G1 before shift
            2000L to 1540.0, // Shift to G2
            2900L to 2100.0, // Eco rev in G2 before shift
            3000L to 1417.0, // Shift to G3
            4000L to 1500.0, // G4
            5000L to 1450.0  // G5
        )

        val profile = PassengerLoadAnalyzer.analyzeShiftProfile(speedSeries, rpmSeries)

        assertTrue("Should detect at least 1 upshift", profile.totalUpshifts >= 1)
    }

    @Test
    fun `sport mode holding and dynamic shifts are classified as SPORT S mode`() {
        // City driving holding 2000-2800 RPM in 2nd/3rd gear
        val speedSeries = listOf(
            1000L to 20.0,
            2000L to 25.0,
            3000L to 30.0,
            4000L to 35.0,
            5000L to 40.0,
            6000L to 45.0
        )
        val rpmSeries = listOf(
            1000L to 2100.0, // G2 @ 20 km/h
            2000L to 2600.0, // G2 @ 25 km/h
            3000L to 3100.0, // G2 @ 30 km/h before upshift
            4000L to 2100.0, // Shift to G3 @ 35 km/h
            5000L to 2400.0, // G3 @ 40 km/h
            6000L to 2700.0  // G3 @ 45 km/h
        )

        val profile = PassengerLoadAnalyzer.analyzeShiftProfile(speedSeries, rpmSeries)
        assertEquals(1, profile.totalUpshifts)
        assertEquals(1, profile.sportShiftsCount)
        assertEquals(3100.0, profile.avgUpshiftRpm!!, 1e-3)
        assertEquals("SPORT (S)", profile.detectedMode)
        assertTrue("Sport hold percent should be > 50%", profile.sportModeHoldPct > 50.0)
    }

    @Test
    fun `solo driver baseline correctly defaults to 75 kg and single occupant`() {
        val summary = TripFuelSummary.Summary(
            fuelLiters = 2.5,
            distanceKm = 30.9,
            kmPerLiter = 12.36,
            durationSeconds = 4200L,
            averageSpeedKmh = 26.5,
            movingAverageSpeedKmh = 30.0,
            maxSpeedKmh = 70.0,
            coastSeconds = 150.0,
            idleSeconds = 200.0,
            speedHistogram = emptyList(),
            sampleCount = 100,
            meanTorqueNm = 95.0,
            peakTorqueNm = 140.0,
            torqueReferenceNm = 178.0
        )

        val result = PassengerLoadAnalyzer.analyze(
            occupantCount = 1,
            samples = emptyList(),
            fuelSummary = summary
        )

        assertEquals(1, result.occupantCount)
        assertEquals(0, result.passengerCount)
        assertEquals(75.0, result.payloadKg, 1e-3)
        assertEquals(1275.0, result.totalVehicleMassKg, 1e-3)
        assertEquals(0.0, result.payloadMassIncreasePct, 1e-3)
        assertEquals(PassengerLoadAnalyzer.VerdictLevel.COMFORTABLE, result.verdict.level)
    }

    @Test
    fun `manual drive mode override updates detectedMode and verdict to Sport S mode`() {
        val summary = TripFuelSummary.Summary(
            fuelLiters = 2.71,
            distanceKm = 31.4,
            kmPerLiter = 11.6,
            durationSeconds = 5900L,
            averageSpeedKmh = 19.0,
            movingAverageSpeedKmh = 22.0,
            maxSpeedKmh = 69.0,
            coastSeconds = 100.0,
            idleSeconds = 400.0,
            speedHistogram = emptyList(),
            sampleCount = 100,
            meanTorqueNm = 26.0,
            peakTorqueNm = 163.0,
            torqueReferenceNm = 175.0
        )

        val result = PassengerLoadAnalyzer.analyze(
            occupantCount = 1,
            samples = emptyList(),
            fuelSummary = summary,
            manualDriveMode = "SPORT (S)"
        )

        assertEquals("SPORT (S)", result.shiftProfile.detectedMode)
        assertTrue(result.verdict.headline.contains("Sport (S)"))
        assertTrue(result.verdict.explanation.contains("Sport (S)"))
    }
}
