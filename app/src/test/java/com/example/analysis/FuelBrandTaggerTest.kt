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
}
