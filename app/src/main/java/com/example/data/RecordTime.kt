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

    /**
     * Accepted shapes, most specific first.
     *
     * `XXX` reads `+05:30` and `Z`; the two `'Z'` patterns read the UTC stamps every file
     * written before 2026-09-17 carries; the last two read naive stamps as IST, which is what
     * they always were on this device (they were formatted with the default zone and simply
     * never said so).
     */
    private val PARSE_PATTERNS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd HH:mm:ss.SSS",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm"
    )

    /**
     * Epoch milliseconds for any stamp this app has ever written, or null when it is missing,
     * blank or unparsable. A stamp carrying an explicit offset is honoured exactly; a legacy
     * `...Z` stamp is read as UTC; a naive stamp is read as IST.
     */
    fun parseMillis(stamp: String?): Long? {
        val text = stamp?.trim().orEmpty()
        if (text.isEmpty()) return null
        for (pattern in PARSE_PATTERNS) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    // Patterns ending in a literal 'Z' must be parsed as UTC; everything else
                    // is read in the record zone.
                    timeZone = if (pattern.endsWith("'Z'")) TimeZone.getTimeZone("UTC") else recordZone
                    // Strict: "2026-09-15T16:07:42Z" must NOT match the .SSS pattern by
                    // silently reinterpreting fields.
                    isLenient = false
                }.parse(text)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    /** True when [stamp] carries an explicit zone (offset or `Z`) rather than being naive. */
    fun isZoned(stamp: String?): Boolean {
        val t = stamp?.trim().orEmpty() ?: return false
        return t.endsWith("Z") || Regex("""[+-]\d{2}:?\d{2}$""").containsMatchIn(t)
    }
}
