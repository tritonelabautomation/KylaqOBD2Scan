package com.example.analysis

import com.example.data.FuelLogCodec
import com.example.data.RefuelBunkRecord
import com.example.data.TripUserOverride
import org.junit.Assert.*
import org.junit.Test

class FuelBrandTaggerTest {

    @Test
    fun testDetectBrand() {
        assertEquals(FuelBrandTagger.FuelBrand.NAYARA, FuelBrandTagger.detectBrand("Nayara Energy HiTech"))
        assertEquals(FuelBrandTagger.FuelBrand.NAYARA, FuelBrandTagger.detectBrand("Essar Petrol Pump"))
        assertEquals(FuelBrandTagger.FuelBrand.IOCL, FuelBrandTagger.detectBrand("IOCL Gachibowli"))
        assertEquals(FuelBrandTagger.FuelBrand.IOCL, FuelBrandTagger.detectBrand("IndianOil XP95"))
        assertEquals(FuelBrandTagger.FuelBrand.JIO_BP, FuelBrandTagger.detectBrand("Jio-bp ORR Junction"))
        assertEquals(FuelBrandTagger.FuelBrand.JIO_BP, FuelBrandTagger.detectBrand("Reliance Petrol Pump"))
        assertEquals(FuelBrandTagger.FuelBrand.SHELL, FuelBrandTagger.detectBrand("Shell Kondapur"))
        assertEquals(FuelBrandTagger.FuelBrand.BPCL, FuelBrandTagger.detectBrand("BPCL Madhapur"))
        assertEquals(FuelBrandTagger.FuelBrand.HPCL, FuelBrandTagger.detectBrand("HPCL Jubilee Hills"))
        assertEquals(FuelBrandTagger.FuelBrand.GENERIC, FuelBrandTagger.detectBrand("Local Highway Bunk"))
        assertEquals(FuelBrandTagger.FuelBrand.IOCL, FuelBrandTagger.detectBrand(null, "XP95"))
        assertEquals(FuelBrandTagger.FuelBrand.GENERIC, FuelBrandTagger.detectBrand(null, "REGULAR"))
        assertEquals(FuelBrandTagger.FuelBrand.IOCL, FuelBrandTagger.detectBrand("IOCL", "REGULAR"))
        assertEquals(FuelBrandTagger.FuelBrand.NAYARA, FuelBrandTagger.detectBrand(null, "X95"))
        assertEquals(FuelBrandTagger.FuelBrand.IOCL, FuelBrandTagger.detectBrand("IOCL Gachibowli", "X95"))
    }

    @Test
    fun testResolveFuelTagFromActiveFills() {
        val now = 1760000000000L

        val fill1 = FuelLogCodec.FuelEntry(
            idMs = now - 3600_000L,
            dateUtc = "2026-10-03T10:00:00Z",
            liters = 42.0,
            pricePerL = 108.5,
            odometerKm = 4200.0,
            station = "Nayara Energy",
            grade = "X95",
            note = "mileX added",
            additive = "Dorf Ketal mileX",
            additiveDosageMl = 5.0
        )

        val fill0 = FuelLogCodec.FuelEntry(
            idMs = now - 86400_000L * 4,
            dateUtc = "2026-09-29T10:00:00Z",
            liters = 40.0,
            pricePerL = 108.5,
            odometerKm = 3800.0,
            station = "IOCL",
            grade = "XP95",
            note = ""
        )

        val bunkRecord = RefuelBunkRecord(
            idMs = fill1.idMs,
            stationName = "Nayara Energy",
            nozzleId = "Nozzle #2",
            fuelGrade = "X95",
            autoCutPercent = 95.0,
            startLevelPercent = 15.0,
            pumpLitres = 42.0,
            floatDeltaLitres = 40.0,
            additiveName = "Dorf Ketal mileX",
            additiveDosageMl = 5.0
        )

        // Drive during Nayara tank
        val tag1 = FuelBrandTagger.resolveFuelTag(
            tripStartMs = now,
            userOverride = null,
            fuelLogs = listOf(fill1, fill0),
            bunkRecords = listOf(bunkRecord)
        )

        assertEquals(FuelBrandTagger.FuelBrand.NAYARA, tag1.brand)
        assertEquals("Nayara Energy", tag1.stationName)
        assertEquals("X95", tag1.grade)
        assertTrue(tag1.hasAdditive)
        assertEquals("Dorf Ketal mileX", tag1.additiveName)
        assertEquals("Nayara X95 + mileX", tag1.displayBadge)

        // Drive during previous IOCL tank
        val tag0 = FuelBrandTagger.resolveFuelTag(
            tripStartMs = now - 86400_000L * 2,
            userOverride = null,
            fuelLogs = listOf(fill1, fill0),
            bunkRecords = listOf(bunkRecord)
        )

        assertEquals(FuelBrandTagger.FuelBrand.IOCL, tag0.brand)
        assertEquals("IOCL", tag0.stationName)
        assertEquals("XP95", tag0.grade)
        assertFalse(tag0.hasAdditive)
        assertEquals("IOCL XP95", tag0.displayBadge)
    }

    @Test
    fun testResolveFuelTagWithManualDriverOverride() {
        val now = 1760000000000L

        val override = TripUserOverride(
            tripId = "trip_test_override",
            fuelStation = "Jio-bp ORR",
            fuelGrade = "Active Petrol",
            fuelAdditive = null
        )

        val tag = FuelBrandTagger.resolveFuelTag(
            tripStartMs = now,
            userOverride = override,
            fuelLogs = emptyList()
        )

        assertEquals(FuelBrandTagger.FuelBrand.JIO_BP, tag.brand)
        assertEquals("Jio-bp ORR", tag.stationName)
        assertEquals("Active Petrol", tag.grade)
        assertTrue(tag.isManualOverride)
        assertEquals("Jio-bp Active Petrol", tag.displayBadge)
    }

    @Test
    fun `trip detail without a fuel log must not invent a Nayara tank`() {
        val tag = FuelBrandTagger.resolveFuelTag(
            tripStartMs = 1_762_000_000_000L,
            userOverride = null,
            fuelLogs = emptyList(),
            bunkRecords = emptyList()
        )
        assertEquals(FuelBrandTagger.FuelBrand.GENERIC, tag.brand)
        assertFalse(tag.stationName.contains("Nayara", ignoreCase = true))
        assertFalse(tag.displayBadge.contains("Nayara", ignoreCase = true))
        assertFalse(tag.fullStationAndGrade.contains("mileX", ignoreCase = true))
        assertFalse(tag.hasAdditive)
    }

    @Test
    fun `IOCL Regular fill is the same tag on the list and on trip detail`() {
        val fillMs = 1_762_000_000_000L
        val tripStartMs = fillMs + 3_600_000L
        val fill = FuelLogCodec.FuelEntry(
            idMs = fillMs,
            dateUtc = "2026-11-10T08:00:00Z",
            liters = 32.0,
            pricePerL = 107.8,
            odometerKm = 4907.8,
            station = "IOCL",
            grade = FuelLogCodec.GRADE_REGULAR,
            note = ""
        )
        val tag = FuelBrandTagger.resolveFuelTag(
            tripStartMs = tripStartMs,
            userOverride = null,
            fuelLogs = listOf(fill)
        )
        assertEquals(FuelBrandTagger.FuelBrand.IOCL, tag.brand)
        assertEquals("IOCL", tag.stationName)
        assertEquals("REGULAR", tag.grade)
        assertEquals("IOCL REGULAR", tag.displayBadge)
        assertFalse(tag.fullStationAndGrade.contains("Nayara", ignoreCase = true))
        assertFalse(tag.hasAdditive)
    }

    @Test
    fun brimFillDuringTripIsBegumpetNotThePreviousBanjaraTank() {
        val tripStart = 1_762_859_940_000L
        val banjara = FuelLogCodec.FuelEntry(
            idMs = tripStart - 3 * 86400_000L,
            dateUtc = "2026-11-08T10:00:00Z",
            liters = 39.31,
            pricePerL = 115.72,
            odometerKm = 4908.0,
            station = "IOCL Banjrahills COCO",
            grade = FuelLogCodec.GRADE_REGULAR,
            note = ""
        )
        val begumpet = FuelLogCodec.FuelEntry(
            idMs = tripStart + 70 * 60_000L,
            dateUtc = "2026-11-11T17:09:00+05:30",
            liters = 40.27,
            pricePerL = 115.72,
            odometerKm = 4987.0,
            station = "IndianOil COCO BEGUMPET",
            grade = FuelLogCodec.GRADE_REGULAR,
            note = "K3597"
        )
        val tag = FuelBrandTagger.resolveFuelTag(
            tripStartMs = tripStart,
            fuelLogs = listOf(begumpet, banjara),
            tripEndMs = tripStart + 2 * 3600_000L,
            tripStartOdoKm = 4971.3,
            tripEndOdoKm = 5003.6
        )
        assertEquals("IndianOil COCO BEGUMPET", tag.stationName)
        assertEquals(FuelBrandTagger.FuelBrand.IOCL, tag.brand)
        assertEquals(40.27, begumpet.liters, 1e-9)
    }

    @Test
    fun `blank station with XP95 grade is IOCL not Nayara`() {
        val fill = FuelLogCodec.FuelEntry(
            idMs = 1_762_000_000_000L,
            dateUtc = "2026-11-10T08:00:00Z",
            liters = 30.0,
            pricePerL = 121.0,
            odometerKm = 4800.0,
            station = "",
            grade = "XP95",
            note = ""
        )
        val tag = FuelBrandTagger.resolveFuelTag(
            tripStartMs = fill.idMs + 1_000L,
            fuelLogs = listOf(fill)
        )
        assertEquals(FuelBrandTagger.FuelBrand.IOCL, tag.brand)
        assertEquals("IOCL", tag.stationName)
        assertEquals("XP95", tag.grade)
    }
}
