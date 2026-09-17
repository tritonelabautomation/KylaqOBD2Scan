package com.example

import com.example.analysis.RawLogRecovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Raw-log lines carry their own date (owner mandate 2026-09-17: IST in every record, and *"it never
 * ever loose the logs"*).
 *
 * The log used to print time-of-day only - `19:33:16.009 RX < 7E804410C0F28` - so recovery had to
 * GUESS the calendar day from the file's last-modified time, with a ±12 h rule for drives that
 * crossed midnight. Guessing is wrong for any log recovered days later, any log copied off the
 * phone, and any log whose mtime the OS touched. Every line now states `yyyy-MM-dd HH:mm:ss.SSS`.
 *
 * The undated shape must keep working forever: the owner's existing raw logs use it.
 */
class RawLogDatedRecoveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val ist = TimeZone.getTimeZone("Asia/Kolkata")

    private fun istMillis(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int, ms: Int = 0): Long =
        java.util.Calendar.getInstance(ist).apply {
            clear()
            set(y, mo - 1, d, h, mi, s)
            set(java.util.Calendar.MILLISECOND, ms)
        }.timeInMillis

    private fun istWall(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply { timeZone = ist }.format(Date(millis))

    /** `41 0C 0F 28` = 970 rpm; `41 0D 50` = 80 km/h. Both single-frame Mode-01 from 7E8. */
    private val frames = listOf("7E804410C0F28" to "0C", "7E8 04410D50" to "0D")

    @Test
    fun datedLinesAreReadAtTheInstantTheyStateNotTheFileMtime() {
        val driveDay = istMillis(2026, 9, 15, 19, 33, 16, 9)
        val text = frames.mapIndexed { i, (frame, _) ->
            "${istWall(driveDay + i * 130L)} RX < $frame"
        }.joinToString("\n")

        // The file says it was last modified THREE DAYS LATER - a copied log, a restored backup, a
        // recovery run long after the drive. The dated lines must still land on the 15th.
        val anchor = driveDay + 3 * 24 * 3_600_000L
        val out = RawLogRecovery.extractTelemetry(text, anchorMillis = anchor, tz = ist)

        assertEquals(2, out.size)
        assertEquals(driveDay, out[0].epochMillis)
        assertEquals(driveDay + 130L, out[1].epochMillis)
        assertEquals("0C", out[0].pidHex2)
        assertEquals("0D", out[1].pidHex2)
        assertEquals("7E8", out[0].canId)
    }

    @Test
    fun aSingleBytePidInCompactFormIsRecoveredInsteadOfSilentlyDropped() {
        // `41 0D 50` = 80 km/h. PCI 3 + 41 + pid + ONE data byte is 4 bytes = 8 hex chars, and
        // with the CAN id fused in front that is 9 characters - an odd-length string. The old
        // parser handed all nine to the hex check, which rejects odd lengths, so the FUSED form of
        // every single-byte answer was dropped: a log recovered from a reference trace or an owner
        // paste came back with RPM (two-byte payload, even length) and no km/h. Every single-byte
        // PID has this shape - 0D, 04, 0F, 11, 46 - i.e. most of the dashboard. The separated form
        // `7E8 04410D50` always worked; this pins that both do now.
        val day = istMillis(2026, 9, 15, 19, 33, 16, 9)
        val compactSpeed = "${istWall(day)} RX < 7E804410D50"
        val spacedSpeed = "${istWall(day + 130)} RX < 7E8 04410D50"
        val out = RawLogRecovery.extractTelemetry(
            listOf(compactSpeed, spacedSpeed).joinToString("\n"), anchorMillis = day, tz = ist
        )
        assertEquals("both shapes of a single-byte PID must survive", 2, out.size)
        assertEquals("0D", out[0].pidHex2)
        assertEquals("7E8", out[0].canId)
        assertEquals("410D50", out[0].responseHex)
        assertEquals(listOf(0x50), out[0].payloadBytes)
        assertEquals(day, out[0].epochMillis)
        assertEquals("0D", out[1].pidHex2)
        // decode() gets the payload WITH its "41 <pid>" header, so the rebuild stays honest.
        assertEquals(listOf(0x41, 0x0D, 0x50), out[0].decodeBytes)
    }

    @Test
    fun anOddLengthBodyThatIsNotACanIdIsNotMangledIntoAFakeFrame() {
        // Stripping 3 characters is only safe when they are a 7xx OBD id. Junk stays junk.
        val day = istMillis(2026, 9, 15, 19, 33, 16, 9)
        val out = RawLogRecovery.extractTelemetry(
            listOf(
                "${istWall(day)} RX < 9Z804410D50",     // not a 7xx id
                "${istWall(day + 10)} RX < 7E804410D5",  // truncated: 8 chars, even, not a frame
                "${istWall(day + 20)} RX < hello"
            ).joinToString("\n"),
            anchorMillis = day, tz = ist
        )
        assertEquals(0, out.size)
    }

    @Test
    fun aDatedDriveCrossingMidnightKeepsItsOrderWithoutAnyHeuristic() {
        val beforeMidnight = istMillis(2026, 9, 16, 23, 59, 30)
        val afterMidnight = istMillis(2026, 9, 17, 0, 1, 5)
        val text = listOf(
            "${istWall(beforeMidnight)} RX < 7E804410C0F28",
            "${istWall(afterMidnight)} RX < 7E8 04410D50"
        ).joinToString("\n")

        val out = RawLogRecovery.extractTelemetry(text, anchorMillis = afterMidnight, tz = ist)
        assertEquals(2, out.size)
        assertEquals(beforeMidnight, out[0].epochMillis)
        assertEquals(afterMidnight, out[1].epochMillis)
        assertTrue("a midnight drive must stay in order", out[0].epochMillis < out[1].epochMillis)
        assertEquals(95_000L, out[1].epochMillis - out[0].epochMillis)
    }

    @Test
    fun undatedLinesStillRecoverViaTheMtimeAnchorSoOldLogsAreNotOrphaned() {
        val day = istMillis(2026, 9, 15, 19, 33, 16, 9)
        val text = frames.map { (frame, _) -> "19:33:16.009 RX < $frame" }.joinToString("\n")
        val out = RawLogRecovery.extractTelemetry(text, anchorMillis = day, tz = ist)
        assertEquals("the legacy shape must keep working", 2, out.size)
        assertEquals(day, out[0].epochMillis)
    }

    @Test
    fun aMixedLogReadsEachLineByItsOwnShape() {
        val dated = istMillis(2026, 9, 15, 8, 0, 0)
        val anchor = istMillis(2026, 9, 15, 19, 33, 16, 9)
        val text = listOf(
            "${istWall(dated)} RX < 7E804410C0F28",   // new build: dated
            "19:33:16.009 RX < 7E8 04410D50"          // old build: undated, anchored to mtime
        ).joinToString("\n")
        val out = RawLogRecovery.extractTelemetry(text, anchorMillis = anchor, tz = ist)
        assertEquals(2, out.size)
        assertEquals(dated, out[0].epochMillis)
        assertEquals(anchor, out[1].epochMillis)
    }

    @Test
    fun txRequestsNegativesAndJunkAreStillSkippedInBothShapes() {
        val day = istMillis(2026, 9, 15, 19, 0, 0)
        val text = listOf(
            "${istWall(day)} TX > 010C",                            // a request, not telemetry
            "${istWall(day + 10)} RX < 7E87F0111",                  // negative response
            "${istWall(day + 20)} RX < 7E804410C0F28",              // the one real answer
            "not a log line at all",
            "${istWall(day + 30)} RX < 7E8 10 14 41 0C 0F 28 00",   // multi-frame PCI, not single
            "",
            "--- RAW OBD-II LOG SESSION: 2df90142 | started 2026-09-15T19:00:00.000+05:30 ---"
        ).joinToString("\n")
        val out = RawLogRecovery.extractTelemetry(text, anchorMillis = day, tz = ist)
        assertEquals(1, out.size)
        assertEquals(day + 20, out[0].epochMillis)
    }

    @Test
    fun theSessionHeaderTheRecorderNowWritesIsIgnoredNotMisreadAsTelemetry() {
        val text = "--- RAW OBD-II LOG SESSION: 2df90142 | started 2026-09-15T19:00:00.000+05:30 (IST +05:30) ---\n" +
            "${istWall(istMillis(2026, 9, 15, 19, 0, 5))} RX < 7E804410C0F28"
        val out = RawLogRecovery.extractTelemetry(text, anchorMillis = istMillis(2026, 9, 15, 19, 5, 0), tz = ist)
        assertEquals(1, out.size)
    }

    @Test
    fun sessionIdMappingIsUnchangedAndAConnectionLogIsNeverReadAsATrip() {
        assertEquals("2df90142", RawLogRecovery.sessionIdOf("raw_log_2df90142.txt"))
        // The connection log deliberately does NOT use the `raw_log_` prefix: it holds adapter
        // traffic from before a trip started, so turning it into a session would invent a trip out
        // of an idle connection.
        assertEquals(null, RawLogRecovery.sessionIdOf("conn_log_2026-09-17.txt"))
        assertEquals(null, RawLogRecovery.sessionIdOf("archived/raw_log_x.txt"))
    }

    @Test
    fun connectionLogsArePrunedAndSessionLogsNeverAre() {
        val now = 1_789_635_425_123L
        val tenDays = 10 * 24 * 3_600_000L
        // A stale connection log is a safety net that has expired: prune it.
        assertTrue(
            com.example.data.RawLogManager.isPrunableConnectionLog(
                "conn_log_2026-09-07.txt", now - tenDays, now
            )
        )
        // A fresh one is still the only copy of the pre-recording window: keep it.
        assertEquals(
            false,
            com.example.data.RawLogManager.isPrunableConnectionLog("conn_log_2026-09-17.txt", now - 3_600_000L, now)
        )
        // A trip log is a trip, however old: never prunable, whatever its name or age.
        assertEquals(
            false,
            com.example.data.RawLogManager.isPrunableConnectionLog("raw_log_2df90142.txt", now - 400 * tenDays, now)
        )
    }

    @Test
    fun anEmptyLogYieldsNothingRatherThanAnInventedTrip() {
        val file = tmp.newFile("raw_log_empty.txt")
        assertEquals(0, RawLogRecovery.extractTelemetry(file.readText(), System.currentTimeMillis(), ist).size)
        assertEquals(0, RawLogRecovery.extractTelemetry("\n\n\n", System.currentTimeMillis(), ist).size)
    }
}
