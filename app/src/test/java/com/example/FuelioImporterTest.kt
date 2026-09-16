package com.example

import com.example.data.FuelioImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner 2026-09-16: "how i can import my data from fuelio to my app?" -
 * Fuelio's sectioned CSV must land in the Kylaq fuel log with honest units.
 */
class FuelioImporterTest {

    private val kmCsv = """
        ## Vehicle,,,,,,,,,,
        Name,Description,DistUnit,FuelUnit,ConsumptionUnit,ImportCSVDateFormat,,,,,
        My Car,Description,1,1,1,yyyy-MM-dd,,,,,
        ## Log,,,,,,,,,,
        Data,Odo (km),Fuel (litres),Full,Price (optional),mpg (optional),latitude (optional),longitude (optional),City (optional),Notes (optional),Missed
        2026-08-01,12000,40.5,1,105.5,,,,,,IOCL,,0
        2026-08-10,12400,39.0,0,104.2,,,,,,HP,,0
        2026-08-20,12800,41.0,1,0,,,,,,IOCL,,1
    """.trimIndent()

    @Test
    fun parsesLitresPriceStationAndPartialFlags() {
        val p = FuelioImporter.parse(kmCsv, 0L)
        assertEquals(3, p.entries.size)
        val a = p.entries[0]
        assertEquals(40.5, a.liters, 1e-9)
        assertEquals(105.5, a.pricePerL, 1e-9)
        assertEquals(12000.0, a.odometerKm!!, 1e-9)
        assertEquals("IOCL", a.station)
        assertEquals(false, a.partial)
        assertEquals(true, p.entries[1].partial)          // Full = 0
        assertEquals(true, p.entries[2].partial)          // Missed = 1
        assertTrue("price-less row is marked: ${p.entries[2].note}", p.entries[2].note.contains("no price"))
    }

    @Test
    fun convertsMilesAndUsGallonsToKmAndLitres() {
        val csv = """
            ## Log,,,,,,,,,,
            Data,Odo (mi),Fuel (us gallons),Full,Price (optional),mpg (optional),latitude (optional),longitude (optional),City (optional),Notes (optional),Missed
            2026-08-01,7456.4,10.7,1,3.90,,,,,,,0
        """.trimIndent()
        val e = FuelioImporter.parse(csv, 0L).entries.single()
        assertEquals(7456.4 * FuelioImporter.MI_TO_KM, e.odometerKm!!, 0.01)
        assertEquals(10.7 * FuelioImporter.US_GAL_TO_L, e.liters, 0.001)
        assertEquals(3.90 / FuelioImporter.US_GAL_TO_L, e.pricePerL, 1e-6)
    }

    @Test
    fun flatExportWithoutSectionsStillParses() {
        val csv = """
            Date,Odo (km),Fuel (litres),Full,Price (optional),Notes,Missed
            2026-08-01,12000,40.5,1,105.5,,0
        """.trimIndent()
        assertEquals(1, FuelioImporter.parse(csv, 0L).entries.size)
    }

    @Test
    fun reImportNeverDuplicates() {
        val first = FuelioImporter.parse(kmCsv, 0L).entries
        val again = FuelioImporter.parse(kmCsv, 0L).entries
        assertEquals(0, FuelioImporter.dedupe(first, again).size)
    }

    @Test
    fun garbageFileYieldsNothingNotACrash() {
        assertEquals(0, FuelioImporter.parse("hello,world\n1,2,3", 0L).entries.size)
    }
}
