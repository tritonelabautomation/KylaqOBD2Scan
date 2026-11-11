package com.example

import com.example.data.CsvExporter
import com.example.data.HeapGuard
import com.example.data.RecordTime
import com.example.model.Direction
import com.example.model.RecordingMetadata
import com.example.model.ResponseStatus
import com.example.model.TransactionRecord
import com.example.service.KeepAlivePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Bounded-memory recovery (owner 2026-11-11 19:02 IST OOM on 1.0.832):
 * keep-alive used to materialise every journal row into RAM. Streaming reducers and
 * last-N rings must agree with a full read on a small file, and never grow a List of
 * the whole drive when the file is large.
 */
class HeapBoundedRecoveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun tx(ms: Long, pid: String, value: Double, ecu: String = "7E8"): TransactionRecord =
        TransactionRecord(
            timestampUtc = RecordTime.stamp(ms),
            timestampMonotonic = ms,
            direction = Direction.RX,
            canRxId = ecu,
            requestHex = "01$pid",
            responseHex = "41$pid",
            service = "01",
            pid = pid,
            decodedParameter = pid,
            decodedValue = value,
            decodedValueDisplay = value.toString(),
            unit = "u",
            responseStatus = ResponseStatus.OK
        )

    @Test
    fun keepAliveLaunchesRecoveryOncePerProcess() {
        assertTrue(KeepAlivePolicy.shouldLaunchRecovery(alreadyLaunched = false))
        assertFalse(KeepAlivePolicy.shouldLaunchRecovery(alreadyLaunched = true))
    }

    @Test
    fun liveRingAndRoomChunkStayCapped() {
        assertEquals(200, HeapGuard.LIVE_RAM_ROWS)
        assertEquals(200, HeapGuard.ROOM_INSERT_CHUNK)
        assertTrue(HeapGuard.AI_ANALYSIS_MAX_SAMPLES < 50_000)
        assertTrue(HeapGuard.RIDE_SHIFT_CAP <= 256)
        assertTrue(HeapGuard.keepForFuelSummary("015E"))
        assertTrue(HeapGuard.keepForFuelSummary("5E"))
        assertTrue(HeapGuard.keepForFuelSummary("010D"))
        assertTrue(HeapGuard.keepForFuelSummary("01A6"))
        assertTrue(HeapGuard.keepForFuelSummary("012F"))
        assertFalse(HeapGuard.keepForFuelSummary("0142"))
        assertFalse(HeapGuard.keepForFuelSummary("0162"))
    }

    @Test
    fun streamingReduceAgreesWithFullReadOnAShortFile() {
        val start = RecordTime.parseMillis("2026-11-11T19:02:00.000+05:30")!!
        val rows = listOf(
            tx(start, "0C", 970.0),
            tx(start + 100, "0D", 80.0),
            tx(start + 200, "05", 49.0, "7E9"),
            tx(start + 300, "42", 13.77, "7E9"),
            tx(start + 400, "2F", 40.0),
            tx(start + 500, "2F", 38.5)
        )
        val file = tmp.newFile("short.csv")
        CsvExporter.exportTransactionsToCsv(
            file,
            RecordingMetadata(sessionId = "s", sessionName = "t", startTimeUtc = RecordTime.stamp(start)),
            rows
        )
        val full = CsvExporter.readTransactionsFromCsv(file)
        val stats = CsvExporter.reduceTransactionsFromCsv(file)
        assertEquals(full.size, stats.count)
        assertEquals(6, stats.count)
        assertEquals(970.0, stats.maxRpm, 0.001)
        assertEquals(80.0, stats.maxSpeedKmh, 0.001)
        assertEquals(49.0, stats.maxCoolantC, 0.001)
        assertEquals(13.77, stats.minVoltageV!!, 0.001)
        assertEquals(40.0, stats.startFuelPercent!!, 0.001)
        assertEquals(38.5, stats.endFuelPercent!!, 0.001)
        assertTrue(stats.detectedEcus.contains("7E8"))
        assertTrue(stats.detectedEcus.contains("7E9"))
        assertEquals(start, stats.minEpochMs)
        assertEquals(start + 500, stats.maxEpochMs)
    }

    @Test
    fun aLongJournalIsVisitedWithoutBuildingTheFullList() {
        val start = RecordTime.parseMillis("2026-11-11T19:02:00.000+05:30")!!
        val n = 4_000
        val rows = ArrayList<TransactionRecord>(n)
        for (i in 0 until n) {
            val pid = when (i % 4) {
                0 -> "0C"
                1 -> "0D"
                2 -> "05"
                else -> "42"
            }
            val value = when (pid) {
                "0C" -> 800.0 + (i % 50)
                "0D" -> (i % 120).toDouble()
                "05" -> 90.0
                else -> 14.2
            }
            rows.add(tx(start + i * 50L, pid, value, if (pid == "42") "7E9" else "7E8"))
        }
        val file = tmp.newFile("long.csv")
        CsvExporter.exportTransactionsToCsv(
            file,
            RecordingMetadata(sessionId = "long", sessionName = "long", startTimeUtc = RecordTime.stamp(start)),
            rows
        )
        rows.clear()

        var visited = 0
        val counted = CsvExporter.forEachTransactionFromCsv(file) { visited++ }
        assertEquals(n, counted)
        assertEquals(n, visited)

        val stats = CsvExporter.reduceTransactionsFromCsv(file)
        assertEquals(n, stats.count)
        assertEquals(start, stats.minEpochMs)
        assertEquals(start + (n - 1) * 50L, stats.maxEpochMs)
        assertEquals(90.0, stats.maxCoolantC, 0.001)
        assertTrue(stats.maxRpm >= 800.0)
        assertTrue(stats.detectedEcus.contains("7E8") && stats.detectedEcus.contains("7E9"))

        val tail = CsvExporter.readLastTransactionsFromCsv(file, HeapGuard.LIVE_RAM_ROWS)
        assertEquals(HeapGuard.LIVE_RAM_ROWS, tail.size)
        assertEquals(start + (n - 1) * 50L, tail.last().timestampMonotonic)
    }

    @Test
    fun replaySamplesToCsvWritesOneRowPerTransaction() {
        val start = 1_800_000_000_000L
        val rows = (0 until 12).map { i ->
            tx(start + i * 100L, if (i % 2 == 0) "0C" else "0D", if (i % 2 == 0) 900.0 else 40.0)
        }
        val txFile = tmp.newFile("replay_tx.csv")
        val sampleFile = tmp.newFile("replay_samples.csv")
        CsvExporter.exportTransactionsToCsv(
            txFile,
            RecordingMetadata(sessionId = "r", sessionName = "r", startTimeUtc = RecordTime.stamp(start)),
            rows
        )
        val written = CsvExporter.replaySamplesToCsv(txFile, sampleFile) { acc, tx ->
            acc.copy(
                timestampUtc = tx.timestampUtc,
                timestampMonotonic = tx.timestampMonotonic,
                rpm = if (tx.pid == "0C") tx.decodedValue else acc.rpm,
                speedKmh = if (tx.pid == "0D") tx.decodedValue else acc.speedKmh
            )
        }
        assertEquals(12, written)
        val samples = CsvExporter.readSamplesFromCsv(sampleFile)
        assertEquals(12, samples.size)
        assertEquals(900.0, samples.last().rpm!!, 0.001)
        assertEquals(40.0, samples.last().speedKmh!!, 0.001)
    }

    @Test
    fun fuelSummaryCsvKeepsRateWhenOtherPidsDominate() {
        val start = RecordTime.parseMillis("2026-11-11T15:59:00.000+05:30")!!
        val rows = ArrayList<TransactionRecord>(720)
        for (i in 0 until 120) {
            val t = start + i * 1000L
            rows.add(tx(t, "0C", 900.0))
            rows.add(tx(t + 50, "5E", 4.0))
            rows.add(tx(t + 100, "42", 14.0))
            rows.add(tx(t + 150, "05", 90.0))
            rows.add(tx(t + 200, "62", 10.0))
            rows.add(tx(t + 250, "0D", 60.0))
        }
        val file = tmp.newFile("factor1.csv")
        CsvExporter.exportTransactionsToCsv(
            file,
            RecordingMetadata(sessionId = "17c54147", sessionName = "run", startTimeUtc = RecordTime.stamp(start)),
            rows
        )
        val pts = CsvExporter.readFuelSummaryPointsFromCsv(file)
        assertTrue(pts.any { it.pid.equals("5E", ignoreCase = true) || it.pid.equals("015E", ignoreCase = true) })
        val summary = com.example.analysis.TripFuelSummary.summarize(pts)
        // 120 s at 4 L/h ≈ 0.133 L. Must not collapse to a chart-subsample trickle.
        assertTrue("fuel ${summary.fuelLiters}", summary.fuelLiters > 0.10)
        assertTrue("fuel ${summary.fuelLiters}", summary.fuelLiters < 0.18)
        assertTrue("distance ${summary.distanceKm}", summary.distanceKm > 1.5)
    }
}
