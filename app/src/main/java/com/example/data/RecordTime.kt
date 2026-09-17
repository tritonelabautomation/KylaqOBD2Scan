package com.example.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The ONE clock every record in this app is stamped with.
 *
 * Owner mandate, 2026-09-17: *"For all records use IST time only no UTC."*
 *
 * Before this existed there were nine separate `SimpleDateFormat(... 'Z')` +
 * `TimeZone.getTimeZone("UTC")` blocks scattered across the scheduler, the recorder, the
 * transports, the discovery service, the fleet exporter and the backup snapshotter. Every
 * file the owner opens - session JSON, transaction CSV, samples CSV, raw ELM log, PID
 * discovery export, backup snapshot - carried a UTC wall clock, so a drive that started at
 * 08:57 in Hyderabad was written as 03:27 and had to be converted by hand. The exported
 * discovery JSON the owner pastes back for auditing still reads `"timestamp":
 * "2026-09-16T08:58:05.839Z"` against raw log lines that read `[08:57:10.520]` local: two
 * clocks in one document, five and a half hours apart.
 *
 * Rules this class enforces:
 *
 *  1. Every stamp is written in the device's own zone - Asia/Kolkata (IST, UTC+05:30) for
 *     this car - **with its offset spelled out** (`+05:30`). A stamp that carries its offset
 *     cannot be misread later, and a trip file copied to another country stays unambiguous.
 *  2. Field names like `timestampUtc` / `startTimeUtc` are KEPT. They are schema keys that
 *     trip JSON, the Room entities, the ZIP importer and every existing backup already
 *     contain; renaming them would orphan the owner's whole trip history. The name is now a
 *     historical artefact, the value is IST. [SessionTime] documents the same thing.
 *  3. Reading is tolerant forever: an explicit offset is honoured, a trailing `Z` is read as
 *     UTC (every file written before this change), and a naive stamp with no zone at all is
 *     read as IST. Old trips keep their true instant; new trips read back exactly.
 *  4. Epoch milliseconds and `timestampMonotonic` stay the arithmetic source of truth. A
 *     wall-clock string is for humans and for files; durations, ordering and the gear/power
 *     pairing are never computed from a formatted stamp.
 */
object RecordTime {

    /** IST as the mandate names it. Resolved once; the offset is printed with every stamp. */
    val zone: TimeZone get() = recordZone

    private val recordZone: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")

    /** `2026-09-17T14:27:05.123+05:30` - the canonical record stamp. */
    private const val STAMP_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"

    /** `2026-09-17 14:27` - session names, notification text, list rows. */
    private const val DISPLAY_PATTERN = "yyyy-MM-dd HH:mm"

    /** `2026-09-17 14:27:05.123` - raw ELM log lines, where every line must carry its date. */
    private const val LOG_PATTERN = "yyyy-MM-dd HH:mm:ss.SSS"

    private fun format(pattern: String, millis: Long): String =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = recordZone }.format(Date(millis))

    /** Canonical record stamp for "now", in IST with its offset. */
    fun stamp(): String = stamp(System.currentTimeMillis())

    /** Canonical record stamp for an instant, in IST with its offset. */
    fun stamp(millis: Long): String = format(STAMP_PATTERN, millis)

    /** Human stamp for "now": `2026-09-17 14:27`. */
    fun display(): String = display(System.currentTimeMillis())

    /** Human stamp for an instant: `2026-09-17 14:27`. */
    fun display(millis: Long): String = format(DISPLAY_PATTERN, millis)

    /**
     * Full dated log stamp: `2026-09-17 14:27:05.123`.
     *
     * The raw ELM log used to write time-of-day only (`14:27:05.123`), so recovering a drive
     * had to GUESS its date from the file's last-modified time - wrong for any log that spanned
     * midnight, was copied, or was recovered days later. Every line now carries its own date.
     */
    fun logStamp(millis: Long = System.currentTimeMillis()): String = format(LOG_PATTERN, millis)

    /** The zone abbreviation the stamps are written in, for headers and UI copy ("IST"). */
    fun zoneLabel(): String = recordZone.getDisplayName(false, TimeZone.SHORT, Locale.US)

    /** `+05:30` - the offset every stamp in this app carries. */
    fun offsetLabel(millis: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("XXX", Locale.US).apply { timeZone = recordZone }.format(Date(millis))

    /** An ISO-8601 stamp whose zone is stated as an offset: `+05:30`, `+0530`. */
    private val ZONED_OFFSET = Regex(
        """^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2})(?::(\d{2}))?(?:\.(\d{1,3}))?([+-]\d{2}:?\d{2})$"""
    )

    /** An ISO-8601 stamp whose zone is stated as `Z` - genuine UTC, written before 2026-09-17. */
    private val ZONED_Z = Regex(
        """^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2})(?::(\d{2}))?(?:\.(\d{1,3}))?[Zz]$"""
    )

    /** A stamp with no zone at all: formatted in the record zone and never said so. */
    private val NAIVE = Regex(
        """^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2})(?::(\d{2}))?(?:\.(\d{1,3}))?$"""
    )

    /**
     * Epoch milliseconds for any stamp this app has ever written, or null when it is missing,
     * blank or unparsable. A stamp carrying an explicit offset is honoured exactly; a legacy
     * `...Z` stamp is read as UTC; a naive stamp is read as IST.
     *
     * The pattern is chosen from the stamp's SHAPE, not by trying patterns in order until one
     * takes. `SimpleDateFormat.parse` reads a PREFIX and ignores whatever is left over, so an
     * ordered list is a trap: `yyyy-MM-dd HH:mm` happily "parses" `2026-09-17 14:27:05.123` by
     * stopping at the minutes and dropping the seconds and the fraction on the floor. That is 65 s
     * off every such stamp in a recovered trip, and it is invisible in the file. Recognise the
     * shape, normalise it to one canonical spelling, then parse exactly that - strictly.
     */
    fun parseMillis(stamp: String?): Long? {
        val text = stamp?.trim().orEmpty()
        if (text.isEmpty()) return null

        ZONED_OFFSET.matchEntire(text)?.let { m ->
            // XXX wants a colon in the offset; +0530 and +05:30 must both read.
            val rawOffset = m.groupValues[5]
            val offset = if (rawOffset.length == 5) rawOffset.substring(0, 3) + ":" + rawOffset.substring(3) else rawOffset
            return parseStrict(
                patternFor(hasSeconds = m.groups[3] != null, hasFraction = m.groups[4] != null, zoneSuffix = "XXX"),
                canonical(m, zoneSuffix = offset),
                recordZone
            )
        }

        ZONED_Z.matchEntire(text)?.let { m ->
            return parseStrict(
                patternFor(hasSeconds = m.groups[3] != null, hasFraction = m.groups[4] != null, zoneSuffix = "'Z'"),
                canonical(m, zoneSuffix = "Z"),
                // Genuine UTC: every trip file written before 2026-09-17 carries this shape.
                TimeZone.getTimeZone("UTC")
            )
        }

        NAIVE.matchEntire(text)?.let { m ->
            return parseStrict(
                patternFor(hasSeconds = m.groups[3] != null, hasFraction = m.groups[4] != null, zoneSuffix = ""),
                canonical(m, zoneSuffix = ""),
                // No zone stated: it was formatted in the record zone and simply never said so.
                recordZone
            )
        }
        return null
    }

    /** The exact pattern for a shape that has already been recognised - never a guess. */
    private fun patternFor(hasSeconds: Boolean, hasFraction: Boolean, zoneSuffix: String): String =
        "yyyy-MM-dd'T'HH:mm" +
            (if (hasSeconds) ":ss" else "") +
            (if (hasFraction) ".SSS" else "") +
            zoneSuffix

    /** Re-spells a recognised stamp in exactly what [patternFor] describes. */
    private fun canonical(m: MatchResult, zoneSuffix: String): String {
        val g = m.groupValues
        val seconds = m.groups[3]?.value ?: "00"
        // A fraction of 1 or 2 digits means tenths/hundredths: pad, never truncate.
        val fraction = m.groups[4]?.value?.padEnd(3, '0')?.take(3)
        return g[1] + "T" + g[2] + ":" + seconds +
            (if (fraction != null) ".$fraction" else "") + zoneSuffix
    }

    /** Strict parse of an exactly-shaped stamp: no prefix-matching, no field reinterpretation. */
    private fun parseStrict(pattern: String, text: String, zone: TimeZone): Long? = runCatching {
        SimpleDateFormat(pattern, Locale.US).apply {
            timeZone = zone
            isLenient = false
        }.parse(text)?.time
    }.getOrNull()

    /** True when [stamp] carries an explicit zone (offset or `Z`) rather than being naive. */
    fun isZoned(stamp: String?): Boolean {
        val t = stamp?.trim().orEmpty() ?: return false
        return t.endsWith("Z") || Regex("""[+-]\d{2}:?\d{2}$""").containsMatchIn(t)
    }
}
