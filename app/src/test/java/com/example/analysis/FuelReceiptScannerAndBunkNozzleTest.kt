package com.example.analysis

import com.example.data.BunkNozzleStat
import com.example.data.RefuelBunkRecord
import org.junit.Assert.*
import org.junit.Test

class FuelReceiptScannerAndBunkNozzleTest {

    @Test
    fun testReceiptScanner_ExtractsStandardIndianPumpReceipt() {
        val receiptOcrText = """
            HINDUSTAN PETROLEUM CORPORATION LTD.
            AUTO CARE CENTRE - HITEC CITY
            HYDERABAD, TELANGANA
            
            DATE: 23/09/2026   TIME: 08:52:10
            NOZZLE NO: 02
            PRODUCT: POWER 95 (XP95)
            RATE: 108.50 INR/L
            VOLUME: 38.54 LTR
            NET AMOUNT: 4181.59 INR
            
            THANK YOU FOR VISITING HPCL!
        """.trimIndent()

        val parsed = FuelReceiptScanner.parseReceiptText(receiptOcrText)

        assertNotNull(parsed)
        val p = checkNotNull(parsed)
        assertEquals(38.54, p.litres!!, 0.001)
        assertEquals(108.50, p.pricePerL!!, 0.001)
        assertEquals(4181.59, p.totalAmount!!, 0.01)
        assertEquals("HPCL", p.stationName)
        assertEquals("POWER 95", p.fuelGrade)
        assertEquals("Nozzle #2", p.nozzle)
    }

    @Test
    fun testReceiptScanner_ExtractsIndianOilReceiptWithVariedFormat() {
        val receiptOcrText = """
            INDIANOIL CO-CO GACHIBOWLI
            PUMP NO: 4
            GRADE: XP95
            DENSITY: 748.2 KG/M3
            PRICE/LTR: Rs. 109.20
            QTY (L): 42.10
            TOTAL SALE: Rs. 4597.32
            VEHICLE NO: TS09GB1234
        """.trimIndent()

        val parsed = FuelReceiptScanner.parseReceiptText(receiptOcrText)

        assertNotNull(parsed)
        val p = checkNotNull(parsed)
        assertEquals(42.10, p.litres!!, 0.001)
        assertEquals(109.20, p.pricePerL!!, 0.001)
        assertEquals(4597.32, p.totalAmount!!, 0.01)
        assertEquals("IndianOil", p.stationName)
        assertEquals("XP95", p.fuelGrade)
        assertEquals("Nozzle #4", p.nozzle)
    }

    @Test
    fun testReceiptScanner_ExtractsShellReceiptWithSpecialSymbols() {
        val receiptOcrText = """
            SHELL INDIA MARKETS PVT LTD
            FINANCIAL DISTRICT
            NOZZLE: 01
            FUEL: V-POWER PETROL
            RATE/L: 114.80
            VOL (L): 25.00
            AMOUNT: ₹ 2870.00
        """.trimIndent()

        val parsed = FuelReceiptScanner.parseReceiptText(receiptOcrText)

        assertNotNull(parsed)
        val p = checkNotNull(parsed)
        assertEquals(25.00, p.litres!!, 0.001)
        assertEquals(114.80, p.pricePerL!!, 0.001)
        assertEquals(2870.00, p.totalAmount!!, 0.01)
        assertEquals("Shell", p.stationName)
        assertEquals("Nozzle #1", p.nozzle)
    }

    @Test
    fun testRefuelBunkRecord_ComputesFloatErrorAndTankCapacityCalibration() {
        // Refuel fill: 38.54 L pumped
        // Start level: 23.0%, Auto-cut level: 98.5% -> delta = 75.5%
        // Calculated float delta on 50.0L nominal tank = 37.75 L
        val record = RefuelBunkRecord(
            idMs = 1758600000000L,
            stationName = "HPCL HiTech City",
            nozzleId = "Nozzle #2",
            fuelGrade = "POWER 95",
            autoCutPercent = 98.5,
            startLevelPercent = 23.0,
            pumpLitres = 38.54,
            floatDeltaLitres = (98.5 - 23.0) / 100.0 * 50.0, // 37.75 L
            pricePerL = 108.50,
            totalCost = 4181.59,
            odometerKm = 4120.0
        )

        assertEquals(37.75, record.floatDeltaLitres, 0.01)
        // Float sensor under-read by 38.54 - 37.75 = 0.79 L (~2.05% error)
        assertEquals(2.0498, record.floatErrorPct, 0.05)

        // Calibrated Tank Capacity = 38.54 / (0.755) = 51.046 L
        assertEquals(51.046, record.calibratedTankCapacityL, 0.05)
    }

    @Test
    fun testBunkNozzleProfiling_ProfilesCutoffVariationsAcrossBunksAndNozzles() {
        val records = listOf(
            RefuelBunkRecord(
                stationName = "HPCL HiTech City",
                nozzleId = "Nozzle #2",
                autoCutPercent = 98.5,
                startLevelPercent = 20.0,
                pumpLitres = 39.2,
                floatDeltaLitres = 39.25
            ),
            RefuelBunkRecord(
                stationName = "HPCL HiTech City",
                nozzleId = "Nozzle #2",
                autoCutPercent = 98.7,
                startLevelPercent = 30.0,
                pumpLitres = 34.3,
                floatDeltaLitres = 34.35
            ),
            RefuelBunkRecord(
                stationName = "IOCL Gachibowli",
                nozzleId = "Nozzle #4",
                autoCutPercent = 96.2,
                startLevelPercent = 15.0,
                pumpLitres = 40.8,
                floatDeltaLitres = 40.6
            )
        )

        val stats = records.groupBy { "${it.stationName.trim()} | ${it.nozzleId.trim()}" }
            .map { (_, group) ->
                val first = group.first()
                val cuts = group.map { it.autoCutPercent }
                val errors = group.map { it.floatErrorPct }
                val caps = group.map { it.calibratedTankCapacityL }

                BunkNozzleStat(
                    stationName = first.stationName,
                    nozzleId = first.nozzleId,
                    fillCount = group.size,
                    avgAutoCutPercent = cuts.average(),
                    minAutoCutPercent = cuts.minOrNull() ?: first.autoCutPercent,
                    maxAutoCutPercent = cuts.maxOrNull() ?: first.autoCutPercent,
                    avgFloatErrorPct = errors.average(),
                    avgCalibratedTankL = caps.average(),
                    lastFillDate = first.timestampUtc.take(10)
                )
            }.sortedByDescending { it.fillCount }

        assertEquals(2, stats.size)

        val hpcl = stats.first { it.stationName.contains("HPCL") }
        assertEquals("Nozzle #2", hpcl.nozzleId)
        assertEquals(2, hpcl.fillCount)
        assertEquals(98.6, hpcl.avgAutoCutPercent, 0.01)
        assertEquals(98.5, hpcl.minAutoCutPercent, 0.01)
        assertEquals(98.7, hpcl.maxAutoCutPercent, 0.01)

        val iocl = stats.first { it.stationName.contains("IOCL") }
        assertEquals("Nozzle #4", iocl.nozzleId)
        assertEquals(1, iocl.fillCount)
        assertEquals(96.2, iocl.avgAutoCutPercent, 0.01)

        // Verifies that HPCL Nozzle #2 has a higher auto-cut threshold (~98.6%) than IOCL Nozzle #4 (~96.2%)
        assertTrue(hpcl.avgAutoCutPercent > iocl.avgAutoCutPercent)
    }
}
