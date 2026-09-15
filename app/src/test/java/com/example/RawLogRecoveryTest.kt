package com.example

import com.example.analysis.RawLogRecovery
import com.example.model.StandardPidCatalog
import com.example.protocol.PidDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Raw-log recovery (owner 2026-09-15: "I logs my logs it didn't save how to recover
 * from mobile?"). A drive killed before STOP never reached Room, but RawLogManager
 * flushed every line to files/raw_logs/raw_log_<id>.txt, so the trip is rebuildable
 * from the phone itself. These tests pin the parser that does the rebuild.
 */
class RawLogRecoveryTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    /** Midnight UTC of the given y/m/d, as epoch millis. */
    private fun utcDay(year: Int, month1: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(year, month1 - 1, day, hour, minute, 0)
        }.timeInMillis

    // ── session id ────────────────────────────────────────────────────────────────

    @Test
    fun `session id is extracted from the raw log file name`() {
        assertEquals("2df90142", RawLogRecovery.sessionIdOf("raw_log_2df90142.txt"))
    }

    @Test
    fun `file names that are not raw logs yield no session id`() {
        assertNull(RawLogRecovery.sessionIdOf("2df90142.txt"))
        assertNull(RawLogRecovery.sessionIdOf("raw_log_2df90142.csv"))
        assertNull(RawLogRecovery.sessionIdOf("raw_log_.txt"))
        assertNull(RawLogRecovery.sessionIdOf(""))
    }

    // ── line body shapes ──────────────────────────────────────────────────────────

    @Test
    fun `compact and canId-separated bodies both split correctly`() {
        // Shape A: CAN id fused into the frame (reference trace f39f1ebd style).
        assertEquals("7E8" to "04410C0F28", RawLogRecovery.splitCanId("7E804410C0F28"))
        // Shape B: canId rendered separately by RawLogEntry.toFormattedLine().
        assertEquals("7E8" to "04410C0F28", RawLogRecovery.splitCanId("7E8 04410C0F28"))
        // Spaces inside the frame are tolerated.
        assertEquals("7E8" to "04410C0F28", RawLogRecovery.splitCanId("7E8 04 41 0C 0F 28"))
        // No recognisable CAN id: kept as frame only.
        assertEquals("" to "04410C0F28", RawLogRecovery.splitCanId("04410C0F28"))
    }

    // ── frame decoding ────────────────────────────────────────────────────────────

    @Test
    fun `mode-01 single frame becomes telemetry with the 2-hex pid the recorder stores`() {
        val t = RawLogRecovery.telemetryFromFrame(1000L, "7E8", "04410C0F28")
        assertTrue(t != null)
        assertEquals("0C", t!!.pidHex2)
        assertEquals(listOf(0x0F, 0x28), t.payloadBytes)
        assertEquals("410C0F28", t.responseHex)
        assertEquals("7E8", t.canId)
        assertEquals(1000L, t.epochMillis)
    }

    @Test
    fun `frames that are not mode-01 telemetry are skipped`() {
        // Negative response (7F 01 11 = service 01, PID 0C not supported).
        assertNull(RawLogRecovery.telemetryFromFrame(0L, "7E8", "037F0111"))
        // DTC response (mode 43).
        assertNull(RawLogRecovery.telemetryFromFrame(0L, "7E8", "06430000000000"))
        // Multi-frame first frame (PCI 0x1N) - v1 recovers single frames only.
        assertNull(RawLogRecovery.telemetryFromFrame(0L, "7E8", "1014490201313030"))
        // Flow control (PCI 0x3N).
        assertNull(RawLogRecovery.telemetryFromFrame(0L, "7E8", "0330000000"))
        // Truncated / odd-length / non-hex bodies.
        assertNull(RawLogRecovery.telemetryFromFrame(0L, "7E8", "0441"))
        assertNull(RawLogRecovery.telemetryFromFrame(0L, "7E8", "04410C0F2"))
        assertNull(RawLogRecovery.telemetryFromFrame(0L, "7E8", "04410C0FZZ"))
        assertNull(RawLogRecovery.telemetryFromFrame(0L, "7E8", ""))
    }

    @Test
    fun `recovered payload decodes to the same value the live dashboard showed`() {
        // The reference trace documents 7E8 04 410C0F28 as 970 rpm.
        val t = RawLogRecovery.telemetryFromFrame(0L, "7E8", "04410C0F28")!!
        val decoded = PidDecoder.decode(StandardPidCatalog.lookup(t.pidHex2), t.payloadBytes)
        assertEquals(970.0, decoded.numericValue!!, 0.001)
    }

    @Test
    fun `two-byte mass air flow and fuel rate frames keep every data byte`() {
        // 019D (fuel mass rate): 7E8 04 41 9D 00 91 -> A=00 B=91.
        val t = RawLogRecovery.telemetryFromFrame(0L, "7E8", "04419D0091")!!
        assertEquals("9D", t.pidHex2)
        assertEquals(listOf(0x00, 0x91), t.payloadBytes)
        // 010D (speed): 7E8 03 41 0D 3C -> 60 km/h.
        val v = RawLogRecovery.telemetryFromFrame(0L, "7E8", "03410D3C")!!
        assertEquals("0D", v.pidHex2)
        assertEquals(listOf(0x3C), v.payloadBytes)
        assertEquals(60.0, PidDecoder.decode(StandardPidCatalog.lookup("0D"), v.payloadBytes).numericValue!!, 0.001)
    }

    // ── whole-file parsing + day anchoring ────────────────────────────────────────

    @Test
    fun `a full log keeps only RX telemetry, in order, with junk ignored`() {
        val anchor = utcDay(2026, 9, 15, 7, 26)
        val text = """
            --- RAW OBD-II LOG SESSION: 2df90142 ---
            07:20:01.000 TX > 7DF02010C000000000000
            07:20:01.009 RX < 7E804410C0F28
            SEARCHING...
            07:20:01.250 RX < 7E904410C0F34
            UNABLE TO CONNECT
            07:20:02.011 RX < 7E803410D3C
            07:20:02.020 RX < 7E8037F0111
        """.trimIndent()

        val frames = RawLogRecovery.extractTelemetry(text, anchor, utc)

        // TX request, the two non-frame noise lines and the 7F negative are all dropped.
        assertEquals(3, frames.size)
        assertEquals(listOf("0C", "0C", "0D"), frames.map { it.pidHex2 })
        // Gateway ECU 7E9 is preserved separately (per-ECU telemetry isolation).
        assertEquals(listOf("7E8", "7E9", "7E8"), frames.map { it.canId })
        // Strictly increasing timestamps.
        assertTrue(frames.zipWithNext().all { (a, b) -> b.epochMillis > a.epochMillis })
    }

    @Test
    fun `times are anchored to the log file day`() {
        val anchor = utcDay(2026, 9, 15, 7, 26)
        val frames = RawLogRecovery.extractTelemetry("07:20:01.009 RX < 7E804410C0F28", anchor, utc)
        val expected = utcDay(2026, 9, 15, 7, 20) + 1_009L
        assertEquals(expected, frames.single().epochMillis)
    }

    @Test
    fun `a drive ending just after midnight maps its evening lines to the previous day`() {
        // File last modified 00:30 on the 15th; the log's 23:50 lines were driven on the 14th.
        val anchor = utcDay(2026, 9, 15, 0, 30)
        val frames = RawLogRecovery.extractTelemetry(
            "23:50:00.000 RX < 7E804410C0F28\n00:10:00.000 RX < 7E803410D3C",
            anchor,
            utc
        )
        assertEquals(2, frames.size)
        assertEquals(utcDay(2026, 9, 14, 23, 50), frames[0].epochMillis)
        assertEquals(utcDay(2026, 9, 15, 0, 10), frames[1].epochMillis)
        assertTrue("order must survive the midnight wrap", frames[1].epochMillis > frames[0].epochMillis)
    }

    @Test
    fun `an empty or noise-only log recovers nothing`() {
        val anchor = utcDay(2026, 9, 15, 7, 26)
        assertTrue(RawLogRecovery.extractTelemetry("", anchor, utc).isEmpty())
        assertTrue(
            RawLogRecovery.extractTelemetry(
                "--- RAW OBD-II LOG SESSION: abcd1234 ---\nSEARCHING...\nUNABLE TO CONNECT",
                anchor, utc
            ).isEmpty()
        )
    }
}
