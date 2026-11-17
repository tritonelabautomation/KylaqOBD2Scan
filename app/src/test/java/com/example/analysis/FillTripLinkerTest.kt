package com.example.analysis

import com.example.data.FuelLogCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner 2026-11-11: recovered trip 17c54147 started 15:59 at 4,971 km and ended
 * 5,004 km. Receipt K3597 is 40.27 L at IndianOil COCO Begumpet, 17:09, odo
 * 4,987. The +1 h-from-start matcher tagged the previous Banjara Hills tank
 * and showed 71.8 % × 52.9 L = 37.99 L instead of the pump.
 */
class FillTripLinkerTest {

    private val tripStart = 1_762_859_940_000L // 2026-11-11 15:59 IST-ish
    private val tripEnd = tripStart + 2 * 3600_000L
    private val banjara = FuelLogCodec.FuelEntry(
        idMs = tripStart - 3 * 86400_000L,
        dateUtc = "2026-11-08T10:00:00Z",
        liters = 39.31,
        pricePerL = 115.72,
        odometerKm = 4908.0,
        station = "IOCL Banjrahills COCO",
        grade = FuelLogCodec.GRADE_REGULAR,
        note = ""
    )
    private val begumpet = FuelLogCodec.FuelEntry(
        idMs = tripStart + 70 * 60_000L, // 17:09
        dateUtc = "2026-11-11T17:09:00+05:30",
        liters = 40.27,
        pricePerL = 115.72,
        odometerKm = 4987.0,
        station = "IndianOil COCO BEGUMPET",
        grade = FuelLogCodec.GRADE_REGULAR,
        note = "K3597"
    )

    @Test
    fun brimFillMatchesByOdoNotThePreviousTank() {
        val link = FillTripLinker.link(
            fills = listOf(begumpet, banjara),
            tripStartMs = tripStart,
            tripEndMs = tripEnd,
            tripStartOdoKm = 4971.3,
            tripEndOdoKm = 5003.6
        )!!
        assertEquals("IndianOil COCO BEGUMPET", link.stationName)
        assertEquals(40.27, link.pumpLitres, 1e-9)
        assertTrue(link.happenedOnThisTrip)
    }

    @Test
    fun brimLitresAreTheReceiptNotTheFloatWindow() {
        val link = FillTripLinker.link(
            fills = listOf(begumpet, banjara),
            tripStartMs = tripStart,
            tripEndMs = tripEnd,
            tripStartOdoKm = 4971.3,
            tripEndOdoKm = 5003.6
        )!!
        val floatWindowL = TankCapacity.litresFromPercent(71.8, 52.9)
        assertEquals(37.99, floatWindowL, 0.05)
        assertEquals(40.27, link.pumpLitres, 1e-9)
    }

    @Test
    fun laterDriveKeepsBegumpetUntilTheNextFill() {
        val link = FillTripLinker.link(
            fills = listOf(begumpet, banjara),
            tripStartMs = tripEnd + 3600_000L,
            tripEndMs = tripEnd + 2 * 3600_000L,
            tripStartOdoKm = 5004.0,
            tripEndOdoKm = 5040.0
        )!!
        assertEquals("IndianOil COCO BEGUMPET", link.stationName)
        assertFalse(link.happenedOnThisTrip)
    }

    @Test
    fun fillDuringTripIsMatchedByTimeWhenOdoIsMissing() {
        val noOdo = begumpet.copy(odometerKm = null)
        val link = FillTripLinker.link(
            fills = listOf(noOdo, banjara),
            tripStartMs = tripStart,
            tripEndMs = tripEnd
        )!!
        assertEquals("IndianOil COCO BEGUMPET", link.stationName)
        assertTrue(link.happenedOnThisTrip)
        assertEquals(40.27, link.pumpLitres, 1e-9)
    }

    @Test
    fun oldOneHourFromStartWindowWouldHavePickedBanjara() {
        // Document the bug: fill at start+70 min is outside start+60 min.
        assertTrue(begumpet.idMs > tripStart + 3600_000L)
        val oldPick = listOf(begumpet, banjara)
            .sortedByDescending { it.idMs }
            .firstOrNull { it.idMs <= tripStart + 3600_000L }
        assertEquals("IOCL Banjrahills COCO", oldPick!!.station)
    }
}
