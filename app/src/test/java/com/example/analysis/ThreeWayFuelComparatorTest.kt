package com.example.analysis

import org.junit.Assert.*
import org.junit.Test

class ThreeWayFuelComparatorTest {

    @Test
    fun testThreeWayComparison_IdentifiesClosestFactorAndConvergence() {
        // Ground truth: 31 km, 83 min (1:23 h), MID Avg 9.1 km/L -> ~3.407 L
        val midData = MidClusterData(
            tripId = "test_trip_1",
            durationMinutes = 83,
            durationText = "1:23 h",
            distanceKm = 31.0,
            avgFuelEconomyKmL = 9.1,
            avgSpeedKmh = 23.0,
            totalOdometerKm = 4021.0,
            ambientTempC = 29.5,
            mode = "Since start"
        )

        // Factor 1 (OBD Mass Integration): 3.48 L consumed -> 8.91 km/L (~2.1% delta from MID)
        // Factor 2 (Float Delta): 4.50 L consumed -> 6.89 km/L (~24.3% delta from float steps on 50L tank)
        val summary = TripFuelSummary.Summary(
            fuelLiters = 3.48,
            distanceKm = 31.0,
            kmPerLiter = 8.91,
            durationSeconds = 4980L, // 83 min
            averageSpeedKmh = 23.0,
            movingAverageSpeedKmh = 26.0,
            maxSpeedKmh = 68.0,
            coastSeconds = 120.0,
            idleSeconds = 450.0,
            speedHistogram = emptyList(),
            sampleCount = 500,
            hasSpeedSeries = true,
            hasFuelSeries = true,
            startFuelPercent = 45.0,
            endFuelPercent = 36.0,
            fuelDeltaPercent = -9.0, // 9% of 50L = 4.50 L
            fuelDeltaLiters = 4.50
        )

        val result = ThreeWayFuelComparator.compare("test_trip_1", summary, midData)

        // Assert MID factor
        assertEquals(31.0, result.distanceKm, 0.01)
        assertEquals(9.1, result.factor3ClusterMid.economyKmL!!, 0.01)
        assertEquals(31.0 / 9.1, result.factor3ClusterMid.fuelLiters!!, 0.01)

        // Assert Factor 1 (OBD Integration)
        assertEquals(3.48, result.factor1ObdIntegration.fuelLiters!!, 0.01)
        assertEquals(8.91, result.factor1ObdIntegration.economyKmL!!, 0.01)
        assertTrue(result.factor1ObdIntegration.errorPctVsMid!! < 0.0) // slightly more conservative
        assertTrue(kotlin.math.abs(result.factor1ObdIntegration.errorPctVsMid!!) < 5.0)

        // Assert Factor 2 (Tank Float Delta)
        assertEquals(4.50, result.factor2TankFloatDelta.fuelLiters!!, 0.01)
        assertEquals(31.0 / 4.50, result.factor2TankFloatDelta.economyKmL!!, 0.01)

        // Factor 1 must win as the closest physical factor to MID
        assertEquals(1, result.closestFactorNumber)
        assertNotNull(result.calibrationRatio)
        assertTrue(result.primaryInsightVerdict.contains("Factor 1"))
    }

    @Test
    fun testMidClusterScanner_DurationParsing() {
        assertEquals(83, MidClusterScanner.parseDurationTextToMinutes("1:23 h"))
        assertEquals(83, MidClusterScanner.parseDurationTextToMinutes("1:23"))
        assertEquals(45, MidClusterScanner.parseDurationTextToMinutes("45 min"))
        assertEquals(125, MidClusterScanner.parseDurationTextToMinutes("2:05 h"))
    }

    @Test
    fun testMidClusterScanner_JsonParsing() {
        val json = """
            {
              "duration_text": "1:23 h",
              "duration_minutes": 83,
              "distance_km": 31.0,
              "fuel_economy_km_l": 9.1,
              "avg_speed_km_h": 23.0,
              "odometer_km": 4021.0,
              "range_km": 240.0,
              "ambient_temp_c": 29.5,
              "mode": "Since start"
            }
        """.trimIndent()

        val parsed = MidClusterScanner.parseClusterJson(json, "trip_json_test")
        assertNotNull(parsed)
        assertEquals("trip_json_test", parsed!!.tripId)
        assertEquals(83, parsed.durationMinutes)
        assertEquals("1:23 h", parsed.durationText)
        assertEquals(31.0, parsed.distanceKm, 0.01)
        assertEquals(9.1, parsed.avgFuelEconomyKmL, 0.01)
        assertEquals(23.0, parsed.avgSpeedKmh, 0.01)
        assertEquals(4021.0, parsed.totalOdometerKm!!, 0.01)
        assertEquals(240.0, parsed.rangeKm!!, 0.01)
        assertEquals(29.5, parsed.ambientTempC!!, 0.01)
        assertEquals("Since start", parsed.mode)
    }

    @Test
    fun testMidClusterScanner_TextRegexParsing() {
        val sampleOcrText = """
            9:07
            29.5°C
            1:23 h
            31 km
            Avg. 9.1 km/l
            Avg. 23 km/h
            Since start
            4021 km
        """.trimIndent()

        val parsed = MidClusterScanner.parseFromText(sampleOcrText, "ocr_trip_1")
        assertNotNull(parsed)
        assertEquals(83, parsed!!.durationMinutes)
        assertEquals(31.0, parsed.distanceKm, 0.01)
        assertEquals(9.1, parsed.avgFuelEconomyKmL, 0.01)
        assertEquals(23.0, parsed.avgSpeedKmh, 0.01)
        assertEquals(4021.0, parsed.totalOdometerKm!!, 0.01)
        assertEquals(29.5, parsed.ambientTempC!!, 0.01)
    }
}
