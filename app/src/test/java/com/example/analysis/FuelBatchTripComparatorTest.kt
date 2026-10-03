package com.example.analysis

import com.example.data.FuelLogCodec
import com.example.data.RefuelBunkRecord
import com.example.data.db.entities.TelemetrySampleEntity
import com.example.data.db.entities.TripEntity
import org.junit.Assert.*
import org.junit.Test

class FuelBatchTripComparatorTest {

    @Test
    fun testCompareTripWithAdditiveVsBaseline() {
        val tripStartMs = 1760000000000L
        val trip = TripEntity(
            id = "trip_milex_01",
            title = "Nayara mileX Drive",
            startTimeUtc = "2026-10-02T11:00:00Z",
            endTimeUtc = "2026-10-02T11:30:00Z",
            startTimestamp = tripStartMs,
            endTimestamp = tripStartMs + 1800_000L,
            durationSeconds = 1800L,
            maxSpeedKmh = 75.0,
            healthScore = 95
        )

        val summary = TripFuelSummary.Summary(
            sampleCount = 100,
            fuelLiters = 2.0,
            distanceKm = 24.0,
            kmPerLiter = 12.0, // 12.0 km/L on treated batch
            averageSpeedKmh = 48.0,
            movingAverageSpeedKmh = 50.0,
            maxSpeedKmh = 75.0,
            coastSeconds = 60.0,
            durationSeconds = 1800L,
            idleSeconds = 60.0,
            speedHistogram = emptyList()
        )

        // Telemetry samples with PID 010E (Timing Advance ~18.5°) and PID 0107 (LTFT ~0.8%)
        val samples = listOf(
            TelemetrySampleEntity(
                tripId = "trip_milex_01",
                timestamp = tripStartMs + 1000,
                timestampUtc = "2026-10-02T11:00:01Z",
                pid = "010E",
                parameterName = "Timing Advance",
                rawHex = "410E5C",
                numericValue = 18.5,
                displayValue = "18.5",
                unit = "°",
                ecuCanId = "7E8"
            ),
            TelemetrySampleEntity(
                tripId = "trip_milex_01",
                timestamp = tripStartMs + 2000,
                timestampUtc = "2026-10-02T11:00:02Z",
                pid = "010E",
                parameterName = "Timing Advance",
                rawHex = "410E5E",
                numericValue = 18.7,
                displayValue = "18.7",
                unit = "°",
                ecuCanId = "7E8"
            ),
            TelemetrySampleEntity(
                tripId = "trip_milex_01",
                timestamp = tripStartMs + 1000,
                timestampUtc = "2026-10-02T11:00:01Z",
                pid = "0107",
                parameterName = "Long Term Fuel Trim Bank 1",
                rawHex = "410781",
                numericValue = 0.8,
                displayValue = "0.8",
                unit = "%",
                ecuCanId = "7E8"
            ),
            TelemetrySampleEntity(
                tripId = "trip_milex_01",
                timestamp = tripStartMs + 2000,
                timestampUtc = "2026-10-02T11:00:02Z",
                pid = "0107",
                parameterName = "Long Term Fuel Trim Bank 1",
                rawHex = "410780",
                numericValue = 0.6,
                displayValue = "0.6",
                unit = "%",
                ecuCanId = "7E8"
            )
        )

        val currentFill = FuelLogCodec.FuelEntry(
            idMs = tripStartMs - 3600_000L,
            dateUtc = "2026-10-02T10:00:00Z",
            liters = 42.0,
            pricePerL = 108.5,
            odometerKm = 10450.0,
            station = "Nayara Energy",
            grade = FuelLogCodec.GRADE_X95,
            note = "mileX additive added",
            additive = "Dorf Ketal mileX",
            additiveDosageMl = 5.0,
            additiveCost = 15.0
        )

        val prevFill = FuelLogCodec.FuelEntry(
            idMs = tripStartMs - 86400_000L * 5,
            dateUtc = "2026-09-27T10:00:00Z",
            liters = 40.0,
            pricePerL = 108.5,
            odometerKm = 10050.0, // (10050 - 9650) / 40L = 10.0 km/L
            station = "IOCL",
            grade = FuelLogCodec.GRADE_REGULAR,
            note = "Standard tank"
        )

        val prevPrevFill = FuelLogCodec.FuelEntry(
            idMs = tripStartMs - 86400_000L * 10,
            dateUtc = "2026-09-22T10:00:00Z",
            liters = 40.0,
            pricePerL = 108.5,
            odometerKm = 9650.0,
            station = "IOCL",
            grade = FuelLogCodec.GRADE_REGULAR,
            note = "Base anchor fill"
        )

        val bunkRecord = RefuelBunkRecord(
            idMs = currentFill.idMs,
            stationName = "Nayara Energy",
            nozzleId = "Nozzle #2",
            fuelGrade = "X95",
            autoCutPercent = 95.0,
            startLevelPercent = 12.0,
            pumpLitres = 42.0,
            floatDeltaLitres = 41.5,
            additiveName = "Dorf Ketal mileX",
            additiveDosageMl = 5.0,
            additiveCost = 15.0
        )

        val result = FuelBatchTripComparator.compare(
            trip = trip,
            summary = summary,
            samples = samples,
            fuelEntries = listOf(currentFill, prevFill, prevPrevFill),
            bunkRecords = listOf(bunkRecord)
        )

        assertNotNull(result.currentBatch)
        assertEquals("Dorf Ketal mileX", result.currentBatch?.additiveName)
        assertTrue(result.currentBatch?.hasAdditive == true)
        assertEquals(12.0, result.currentTripKmL!!, 0.01)
        assertEquals(10.0, result.previousTankAvgKmL!!, 0.01)

        // Delta should be +2.0 km/L (+20.0%)
        assertEquals(2.0, result.deltaKmL!!, 0.01)
        assertEquals(20.0, result.deltaPercent!!, 0.01)
        assertTrue(result.isImprovement)

        // Timing Advance should be positive advance (~18.6 - 15.8 = 2.8°)
        assertTrue(result.timingAdvanceDeltaDeg!! > 2.0)

        // Trim reduction should be positive (~3.8 - 0.7 = 3.1%)
        assertTrue(result.trimReductionPct!! > 2.5)

        // Dosage advice should warn about 5ml vs 40-50L tank dilution
        assertNotNull(result.dosageAdvice)
        assertTrue(result.dosageAdvice!!.contains("Dosage Notice"))
        assertTrue(result.dosageAdvice!!.contains("5 ml"))
    }
}
