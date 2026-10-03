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
        // Factor 2 (Float Delta): 4.05 L consumed -> 7.65 km/L (~15.9% delta from float steps on 45L tank)
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
            fuelDeltaPercent = -9.0, // 9% of 45L = 4.05 L
            fuelDeltaLiters = 4.05
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
        assertEquals(4.05, result.factor2TankFloatDelta.fuelLiters!!, 0.01)
        assertEquals(31.0 / 4.05, result.factor2TankFloatDelta.economyKmL!!, 0.01)

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

    @Test
    fun testMidClusterScanner_ExactOwnerKylaqClusterSnapshot() {
        val ownerClusterOcrText = """
            17:28
            31.0°c
            1:27 h
            31 km
            Avg. 9.8 km/l
            Avg. 21 km/h
            Since start
            4052 km
            210 km
        """.trimIndent()

        val parsed = MidClusterScanner.parseFromText(ownerClusterOcrText, "trip_da5654ff")
        assertNotNull(parsed)
        assertEquals("trip_da5654ff", parsed!!.tripId)
        assertEquals(87, parsed.durationMinutes) // 1 hr 27 min
        assertEquals("1:27 h", parsed.durationText)
        assertEquals(31.0, parsed.distanceKm, 0.01)
        assertEquals(9.8, parsed.avgFuelEconomyKmL, 0.01)
        assertEquals(21.0, parsed.avgSpeedKmh, 0.01)
        assertEquals(4052.0, parsed.totalOdometerKm!!, 0.01)
        assertEquals(210.0, parsed.rangeKm!!, 0.01)
        assertEquals(31.0, parsed.ambientTempC!!, 0.01)
        assertEquals("Since start", parsed.mode)
        assertEquals("17:28", parsed.timeOfDay)

        // Test 3-way comparator with this exact cluster data:
        // Fuel summary from OBD: 31.54 km, 3.04 L consumed (10.37 km/L)
        val summary = TripFuelSummary.Summary(
            fuelLiters = 3.04,
            distanceKm = 31.54,
            kmPerLiter = 10.37,
            durationSeconds = 5400L,
            averageSpeedKmh = 21.0,
            movingAverageSpeedKmh = 23.0,
            maxSpeedKmh = 65.0,
            coastSeconds = 110.0,
            idleSeconds = 480.0,
            speedHistogram = emptyList(),
            sampleCount = 500,
            hasSpeedSeries = true,
            hasFuelSeries = true,
            startFuelPercent = 50.0,
            endFuelPercent = 43.02, // drop 6.98% * 45L = 3.14L -> 10.04 km/L
            fuelDeltaPercent = -6.98,
            fuelDeltaLiters = 3.14
        )

        val comparison = ThreeWayFuelComparator.compare("trip_da5654ff", summary, parsed)

        // Factor 3: Ground Truth Baseline
        assertEquals(9.8, comparison.factor3ClusterMid.economyKmL!!, 0.01)
        assertEquals(31.0 / 9.8, comparison.factor3ClusterMid.fuelLiters!!, 0.01)

        // Factor 1: OBD Injection Integration (10.37 km/L vs MID 9.8 km/L -> +5.8% delta)
        assertEquals(3.04, comparison.factor1ObdIntegration.fuelLiters!!, 0.01)
        assertEquals(10.37, comparison.factor1ObdIntegration.economyKmL!!, 0.01)
        assertEquals(5.81, comparison.factor1ObdIntegration.errorPctVsMid!!, 0.1)

        // Factor 2: Tank Float Level Delta (10.04 km/L vs MID 9.8 km/L -> +2.4% delta)
        assertEquals(3.14, comparison.factor2TankFloatDelta.fuelLiters!!, 0.01)
        assertEquals(31.54 / 3.14, comparison.factor2TankFloatDelta.economyKmL!!, 0.1)
    }

    @Test
    fun testMidClusterScanner_NoisyOcrVariationsAndDecimalCommas() {
        val noisyText = """
            17:28
            31,0 °C
            1:27h
            31 km
            Avg. 9,8
            km/l
            Avg. 21 km/h
            Since start
            4052 km
            210 km
        """.trimIndent()

        val parsed = MidClusterScanner.parseFromText(noisyText, "trip_noisy")
        assertNotNull(parsed)
        assertEquals(9.8, parsed!!.avgFuelEconomyKmL, 0.01)
        assertEquals(31.0, parsed.distanceKm, 0.01)
        assertEquals(87, parsed.durationMinutes)
        assertEquals(21.0, parsed.avgSpeedKmh, 0.01)
        assertEquals(4052.0, parsed.totalOdometerKm!!, 0.01)
        assertEquals(210.0, parsed.rangeKm!!, 0.01)
    }
}
