package com.example.analysis

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarpoolReceiptScannerTest {

    @Test
    fun testQuickRideScreenshotWithSurgeAndRupeeGlyphCorrection() {
        val ocrText = """
            09:08 40.0 Vo 4G+ 86%
            < VEDHIK KIDS PLAY SC... -> Sattva Knowledge City...
            
            Yaswanth
            Hop Off
            Earned
            ₹192 ↗ ₹246
            
            Kashyap Sharma
            Hop Off
            Earned
            ₹97 ↗ ₹124
            
            Ride Completed Successfully
            1495 Earned
            Amount credited your wallet
            
            Live-location stopped when the ride ended
            Okay
        """.trimIndent()

        val result = CarpoolReceiptScanner.parseFromUnstructuredText(ocrText)

        assertNotNull(result)
        assertEquals(495.0, result.totalEarned ?: 0.0, 0.01)
        assertEquals(2, result.riders.size)

        val rider1 = result.riders.find { it.name == "Yaswanth" }
        assertNotNull("Yaswanth must be detected", rider1)
        assertEquals(246.0, rider1!!.amount, 0.01)

        val rider2 = result.riders.find { it.name == "Kashyap Sharma" }
        assertNotNull("Kashyap Sharma must be detected", rider2)
        assertEquals(124.0, rider2!!.amount, 0.01)

        assertEquals("VEDHIK KIDS PLAY SC...", result.routeFrom)
        assertEquals("Sattva Knowledge City...", result.routeTo)
    }

    @Test
    fun testSRideMultiRiderSummary() {
        val ocrText = """
            SRide Trip Summary
            Route: Gachibowli to Hitech City
            Distance: 14.8 km
            
            Riders:
            Anand Kumar
            ₹140
            14.8 km
            
            Pooja Reddy
            ₹110
            11.2 km
            
            Total Earned: ₹250
        """.trimIndent()

        val result = CarpoolReceiptScanner.parseFromUnstructuredText(ocrText)
        assertNotNull(result)
        assertEquals(250.0, result.totalEarned ?: 0.0, 0.01)
        assertEquals(14.8, result.totalDistanceKm ?: 0.0, 0.1)
        assertEquals(2, result.riders.size)
        assertEquals("Anand Kumar", result.riders[0].name)
        assertEquals(140.0, result.riders[0].amount, 0.01)
        assertEquals(14.8, result.riders[0].distanceKm ?: 0.0, 0.1)
        assertEquals("Pooja Reddy", result.riders[1].name)
        assertEquals(110.0, result.riders[1].amount, 0.01)
        assertEquals(11.2, result.riders[1].distanceKm ?: 0.0, 0.1)
    }

    @Test
    fun testSingleTotalEarnedFallback() {
        val ocrText = """
            Ride Completed
            Earned: ₹350
            Trip ended at Sattva Knowledge City
        """.trimIndent()

        val result = CarpoolReceiptScanner.parseFromUnstructuredText(ocrText)
        assertNotNull(result)
        assertEquals(350.0, result.totalEarned ?: 0.0, 0.01)
        assertTrue(result.riders.isNotEmpty())
        assertEquals(350.0, result.riders[0].amount, 0.01)
    }

    @Test
    fun testExtractAmountsFiltersDistanceAndPercentages() {
        val line1 = "14.8 km"
        val line2 = "86%"
        val line3 = "₹192 ↗ ₹246"
        val line4 = "Earned ₹140.00"

        assertEquals(0, CarpoolReceiptScanner.extractAmounts(line1).size)
        assertEquals(0, CarpoolReceiptScanner.extractAmounts(line2).size)
        
        val surgeAmounts = CarpoolReceiptScanner.extractAmounts(line3)
        assertEquals(2, surgeAmounts.size)
        assertEquals(192.0, surgeAmounts[0], 0.01)
        assertEquals(246.0, surgeAmounts[1], 0.01)

        val directAmounts = CarpoolReceiptScanner.extractAmounts(line4)
        assertEquals(1, directAmounts.size)
        assertEquals(140.0, directAmounts[0], 0.01)
    }
}
