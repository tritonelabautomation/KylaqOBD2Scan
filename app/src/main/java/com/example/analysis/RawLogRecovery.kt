package com.example.analysis

import java.util.Calendar
import java.util.TimeZone

/**
 * Unsaved-recording recovery (owner 2026-09-15: "I logs my logs it didn't save
 * how to recover from mobile?").
 *
 * A recording accumulates in RAM and is finalized only when STOP fires (manual tap or
 * the auto-record engine-off timer). If Android kills the app mid-drive (swipe-away,
 * battery optimization, crash), the trip never reaches Room — but the raw OBD log was
 * already on disk, flushed after EVERY line (RawLogManager → files/raw_logs/
 * raw_log_<sessionId>.txt). The drive is therefore still on the phone.
 *
 * This object rebuilds a recording from that file:
 *  1. [sessionIdOf] maps `raw_log_2df90142.txt` → session id.
 *  2. [extractTelemetry] parses `HH:mm:ss.SSS TX >|RX < ...` lines (and, since 1.0.337,
 *     `yyyy-MM-dd HH:mm:ss.SSS ...` lines that carry their own date), re-anchors the
 *     wall-clock times to the file's last-modified day (a line whose time-of-day is
 *     more than 12 h AFTER the anchor belongs to the previous calendar day, so drives
 *     ending just past midnight keep their order), and keeps only RX Mode-01
 *     single-frame telemetry (negative `7F` responses, TX requests and multi-frame
 *     PCI are skipped - v1 recovers the polling telemetry the trip cards need).
 *
 * Line bodies come in two shapes and both are handled:
 *  - `19:33:16.009 RX < 7E804410C0F28`      (compact: CAN id fused into the frame)
 *  - `19:33:16.009 RX < 7E8 04410C0F28`     (canId rendered separately)
 *
 * Pure JVM - unit-tested in RawLogRecoveryTest.
 */
object RawLogRecovery {

    /** One recovered Mode-01 telemetry response. */
    data class Telemetry(
        val epochMillis: Long,
        val canId: String,          // e.g. "7E8" ("" when the line carried no recognizable id)
        val pidHex2: String,        // "0C", "9D" - the same 2-hex form the live recorder stores
        val payloadBytes: List<Int>,// data bytes A, B, ... after `41 <pid>`
        val responseHex: String     // compact payload hex starting at "41", e.g. "410C0F28"
    ) {
        /**
         * What `PidDecoder.decode` must be handed: the payload *including* the positive
         * service ack and the pid byte. The decoder deliberately refuses anything else
         * (FIX P0-2 - a permissive "raw data bytes" fallback used to let malformed frames
         * decode as telemetry), so passing [payloadBytes] alone yields INVALID_RESPONSE.
         */
        val decodeBytes: List<Int>
            get() = listOf(0x41, pidHex2.toInt(16)) + payloadBytes
    }

    private val LINE = Regex("""^(\d{2}):(\d{2}):(\d{2})\.(\d{3})\s+(TX >|RX <)\s+(.+?)\s*$""")

    /**
     * Dated line, written by every build from 1.0.337 on:
     * `2026-09-17 14:27:05.123 RX < 7E8 04410C0F28`.
     *
     * The undated [LINE] shape stays supported forever - the owner's existing raw logs use it -
     * but a dated line carries its own calendar day, so recovery never has to GUESS one from the
     * file's last-modified time. Guessing was wrong for any drive that spanned midnight, any log
     * copied off the phone, and any log recovered days after the drive.
     */
    private val DATED_LINE = Regex(
        """^(\d{4})-(\d{2})-(\d{2})\s+(\d{2}):(\d{2}):(\d{2})\.(\d{3})\s+(TX >|RX <)\s+(.+?)\s*$"""
    )
    private val CAN_ID = Regex("""^7[A-F0-9]{2}$""")

    /**
     * A compact body whose length is odd cannot be byte-aligned hex, so it must still be carrying
     * its 3-character CAN id: `7E804410D50` is `7E8` + `04410D50`.
     *
     * This is the shape every SINGLE-BYTE PID produces - 0D vehicle speed, 04 engine load, 0F
     * intake air temp, 11 throttle, 46 ambient - because PCI 3 + `41` + pid + one data byte is
     * four bytes, eight hex characters, plus the three of the CAN id makes nine. The old
     * `compact.length > 3` test handed all nine characters to [telemetryFromFrame], which rejects
     * odd-length hex, so the FUSED form of every single-byte answer was dropped: a log recovered
     * from a reference trace or an owner paste came back with RPM (a two-byte payload, even length)
     * and no km/h.
     *
     * Scope, stated exactly: `RawLogManager` writes the separated form (`7E8 04410D50`), so the
     * app's own logs reach [telemetryFromFrame] already split. They were still losing speed - not
     * here but at the off-by-one length guard below, DATA-LOSS FIX 5. The fused form is what a
     * reference trace or an owner paste looks like, and it needs this split before it can be read at
     * all. Requiring a 7xx functional/physical id before stripping keeps a genuinely corrupt
     * odd-length frame from being mangled into a plausible one.
     */
    private val COMPACT_WITH_ID = Regex("""^(7[A-Fa-f0-9]{2})([0-9A-Fa-f]+)$""")
    private val HEX_ONLY = Regex("""^[0-9A-Fa-f]+$""")

    /** `raw_log_<sessionId>.txt` → `<sessionId>`, null for anything else. */
    fun sessionIdOf(fileName: String): String? =
        fileName
            .takeIf { it.startsWith("raw_log_") && it.endsWith(".txt") }
            ?.removePrefix("raw_log_")
            ?.removeSuffix(".txt")
            ?.takeIf { it.isNotBlank() }

    /**
     * Parses a raw log's text into recovered telemetry.
     *
     * @param anchorMillis the log file's last-modified time (≈ end of the drive); its
     *                     calendar day in [tz] anchors every line's time-of-day.
     */
    fun extractTelemetry(text: String, anchorMillis: Long, tz: TimeZone = TimeZone.getDefault()): List<Telemetry> {
        val cal = Calendar.getInstance(tz).apply {
            timeInMillis = anchorMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val dayStart = cal.timeInMillis

        val out = mutableListOf<Telemetry>()
        for (line in text.lineSequence()) {
            val epoch: Long
            val dirGroup: String
            val bodyGroup: String

            val dated = DATED_LINE.matchEntire(line)
            if (dated != null) {
                // The line states its own date - use it exactly, no anchoring heuristic.
                epoch = Calendar.getInstance(tz).apply {
                    clear()
                    set(
                        dated.groupValues[1].toInt(),
                        dated.groupValues[2].toInt() - 1,
                        dated.groupValues[3].toInt(),
                        dated.groupValues[4].toInt(),
                        dated.groupValues[5].toInt(),
                        dated.groupValues[6].toInt()
                    )
                    set(Calendar.MILLISECOND, dated.groupValues[7].toInt())
                }.timeInMillis
                dirGroup = dated.groupValues[8]
                bodyGroup = dated.groupValues[9]
            } else {
                val m = LINE.matchEntire(line) ?: continue
                val hours = m.groupValues[1].toLong()
                val minutes = m.groupValues[2].toLong()
                val seconds = m.groupValues[3].toLong()
                val millis = m.groupValues[4].toLong()
                val timeOfDay = ((hours * 60 + minutes) * 60 + seconds) * 1000 + millis
                var guessed = dayStart + timeOfDay
                // Drive ended just after midnight: an evening time-of-day would land ~24 h in
                // the future relative to the anchor - it belongs to the previous day.
                if (guessed - anchorMillis > 12L * 3_600_000L) guessed -= 24L * 3_600_000L
                epoch = guessed
                dirGroup = m.groupValues[5]
                bodyGroup = m.groupValues[6]
            }

            if (dirGroup == "TX >") continue
            val (canId, frameHex) = splitCanId(bodyGroup)
            val t = telemetryFromFrame(epoch, canId, frameHex) ?: continue
            out += t
        }
        return out
    }

    /** Splits a line body into CAN id + compact frame hex, handling both stored shapes. */
    internal fun splitCanId(body: String): Pair<String, String> {
        val tokens = body.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size >= 2 && tokens[0].length == 3 && CAN_ID.matches(tokens[0].uppercase())) {
            val rest = tokens.drop(1).joinToString("")
            if (HEX_ONLY.matches(rest)) return tokens[0].uppercase() to rest.uppercase()
        }
        val compact = tokens.joinToString("").uppercase()
        if (compact.length > 3 && CAN_ID.matches(compact.take(3))) {
            return compact.take(3) to compact.drop(3)
        }
        // Odd-length compact body: the CAN id was never separated, and the frame that follows it
        // IS byte-aligned. See COMPACT_WITH_ID.
        if (compact.length % 2 != 0) {
            val m = COMPACT_WITH_ID.matchEntire(compact)
            if (m != null) return m.groupValues[1].uppercase() to m.groupValues[2].uppercase()
        }
        return "" to compact
    }

    /** Extracts a Mode-01 single-frame telemetry response, or null when not one. */
    internal fun telemetryFromFrame(epochMillis: Long, canId: String, frameHex: String): Telemetry? {
        if (frameHex.length < 6 || frameHex.length % 2 != 0 || !HEX_ONLY.matches(frameHex)) return null
        val bytes = frameHex.chunked(2).map { it.toInt(16) }
        val pci = bytes[0]
        if (pci < 2 || pci > 7) return null          // single frames only (0x1N = multi-frame, 0x3N = flow control)
        // Truncated-line guard. It used to demand `pci + 1` bytes, i.e. it trusted the count byte to
        // state the number of bytes that FOLLOW it. On this car's logs it does not: a single-byte
        // answer is written `04 41 0D 50` (four bytes total, count byte included), so the strict
        // reading called a complete frame truncated. All that is genuinely required to identify a
        // row is `41 + pid`, which is what this now asks for; the payload length is settled below.
        if (bytes.size < 3) return null
        if (bytes[1] != 0x41) return null              // Mode-01 response only (skips 7F negatives, 43 DTCs, ...)
        // DATA-LOSS FIX 5 (owner 2026-09-17): a single-byte answer written as `04 41 0D 50`
        // declares four following bytes and carries three. The guard here used to demand
        // `3 + dataLen` bytes, so EVERY single-byte response was thrown away as "truncated" -
        // 010D vehicle speed, 0105 coolant, 010F intake air temp, 0111 throttle, 0106/0107 fuel
        // trims, 0146 ambient - while two-byte answers such as 010C rpm happened to satisfy it.
        // That is precisely the shape of the owner's complaint: a log with rpm in it and no km/h.
        //
        // This is not the recovery parser being lenient about a corrupt frame, it is the recovery
        // parser agreeing with the LIVE one. `CanFrameParser` reads the same line on the road and
        // keeps it: when the declared length does not fit, it falls back to `dataBytes.drop(1)` and
        // decodes the PID from what is actually present. Two parsers that disagree about the same
        // bytes mean the live dashboard shows 80 km/h and the recovered trip shows nothing at all.
        //
        // Still rejected, so nothing is invented: a body too short to hold `41 + pid` (there is no
        // PID to read), a PCI outside the single-frame range 2..7, a mode echo that is not 0x41,
        // odd-length or non-hex bodies, multi-frame and flow-control frames, and 7F negative
        // responses. A row is decoded from the bytes that exist; it is never padded to the length
        // the count byte claims.
        val declaredDataLen = pci - 2
        val availableDataLen = bytes.size - 3
        if (availableDataLen < 1) return null
        val dataLen = minOf(declaredDataLen, availableDataLen)
        val pid = bytes[2]
        val payload = bytes.subList(3, 3 + dataLen)
        return Telemetry(
            epochMillis = epochMillis,
            canId = canId,
            pidHex2 = "%02X".format(pid),
            payloadBytes = payload,
            responseHex = bytes.subList(1, 3 + dataLen).joinToString("") { "%02X".format(it) }
        )
    }
}
