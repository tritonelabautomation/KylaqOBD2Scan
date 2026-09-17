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

    /**
     * Renders an instant in IST with any shape. This is the ONLY sanctioned way to turn a millis
     * value into text anywhere in the app.
     *
     * It exists because 21 call sites were building their own `SimpleDateFormat` with no timezone,
     * which silently means "whatever zone this device is set to". On the owner's phone that is IST,
     * so it looked correct - until the device zone changes, a backup is restored elsewhere, or a
     * log is read on a second phone, and every trend axis and trip time shifts by hours with nothing
     * in the code to explain it. Owner mandate 2026-09-17: *"All logs, trends everything should be
     * IST even the old logs should be IST by default."* "By default" cannot mean "by accident of the
     * device setting", so the zone is pinned here instead.
     */
    fun format(pattern: String, millis: Long): String =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = recordZone }.format(Date(millis))

    /**
     * An IST-pinned [SimpleDateFormat] for callers that format many instants in a loop - a chart
     * drawing its axis ticks, a list binding rows - where building a formatter per value would
     * show up as jank. `remember { RecordTime.formatter("HH:mm:ss") }` replaces
     * `remember { SimpleDateFormat("HH:mm:ss", Locale.US) }` with the same cost and the zone
     * guaranteed. Not thread-safe, exactly like the SimpleDateFormat it hands back: keep one per
     * composition, do not share it across threads.
     */
    fun formatter(pattern: String): SimpleDateFormat =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = recordZone }

    /**
     * The instant a record refers to, when the record carries BOTH an epoch-millis field and a
     * string stamp - which every Room row in this app does (`TripEntity.startTimestamp` beside
     * `startTimeUtc`, `timestamp` beside `timestampUtc` in the four sample tables).
     *
     * **The millis win, and that is not a tie-break, it is the only defensible reading.** Two
     * conventions wrote the strings in the owner's existing history. Auditing build 1.0.336 - the one
     * that wrote every log currently on his phone - found 18 formatters that printed a literal `Z`:
     * **15 formatted in UTC and meant it**, including `RecordingManager`, so the trips and logs
     * themselves, and **3 formatted in the device zone and printed the same `Z` while holding IST
     * digits**: `ZipImporter`'s fallback stamp for an import with no meta, `TripRepository`'s AI
     * analysis stamp, and `PidDiscoveryService`'s discovery export. Both shapes are in his data and
     * they need opposite corrections: read the honest ones as IST and the drive moves 5.5 h early,
     * read the dishonest ones as UTC and it moves 5.5 h late. No single rule for "an old `Z`" can be
     * right for both.
     *
     * `System.currentTimeMillis()` has no zone to get wrong, so where it was stored alongside the
     * string it settles the question per record without guessing. The string is then display-only.
     * A record with no millis (a Fuelio CSV, an imported ZIP, a hand-edited file) falls through to
     * [parseMillis], which honours an explicit offset, reads `Z` as UTC and reads a naive stamp as
     * IST - the documented default, and the best available when there is nothing to check against.
     *
     * **A millis value only counts as an instant if it could be one.** Two ways this app stores a
     * non-instant in a `Long` that sits beside a stamp:
     *
     *  - `0` / negative - an unfilled column or a `?: 0L` fallback. `0` is 1970, and plotting it
     *    would put a drive forty years before the car was built.
     *  - **`SystemClock.elapsedRealtime()`** - milliseconds since boot. `TransactionRecord
     *    .timestampMonotonic` is filled with uptime by the live transport and scheduler, and
     *    `RecordingManager` copied it into the indexed `TelemetrySampleEntity.timestamp` column. So
     *    for every live-recorded trip that column held uptime, and the trend chart formatted it as a
     *    time of day: a drive taken three hours after boot drew its axis from ~08:30 IST on
     *    1970-01-01 whatever the real hour, and cross-trip trends compared one phone's uptime
     *    against another's. Nothing crashed, because uptime still increases - the shape of the
     *    curve stayed right while every label on it was wrong.
     *
     * Both are outside [MIN_PLAUSIBLE_EPOCH_MS]..[MAX_PLAUSIBLE_EPOCH_MS], so both fall through to
     * the stamp beside them, which does state the instant. That repairs rows already on the owner's
     * phone with no migration and no rewrite of his history.
     *
     * @return the instant, or null when neither field yields one. Never 0: null means "unknown".
     */
    fun instantOf(millis: Long?, stored: String?): Long? {
        if (millis != null && millis in MIN_PLAUSIBLE_EPOCH_MS..MAX_PLAUSIBLE_EPOCH_MS) return millis
        return parseMillis(stored)
    }

    /**
     * 2020-01-01T00:00:00Z. Every record this app can hold is later than that, while uptime would
     * take fifty years of continuous runtime to reach it - so the two populations do not overlap and
     * the bound separates them cleanly rather than by taste.
     */
    const val MIN_PLAUSIBLE_EPOCH_MS = 1_577_836_800_000L

    /** 2100-01-01T00:00:00Z. A value past this is corrupt, not a future booking. */
    const val MAX_PLAUSIBLE_EPOCH_MS = 4_102_444_800_000L

    /** True when [millis] can only be an epoch instant, not uptime and not an unfilled column. */
    fun isPlausibleEpoch(millis: Long?): Boolean =
        millis != null && millis in MIN_PLAUSIBLE_EPOCH_MS..MAX_PLAUSIBLE_EPOCH_MS

    /**
     * Re-states any record's time in canonical IST, for the write paths that emit a file: CSV
     * export, session JSON, the ZIP bundle, a fleet sheet.
     *
     * Exporting used to copy the stored string through untouched, so exporting a trip recorded
     * before the IST mandate produced a file full of `...Z` UTC stamps - the owner's data left the
     * phone in the one zone he asked never to see. This resolves the instant with [instantOf] (so an
     * old honest-UTC row, an old dishonest-`Z` row and a row carrying uptime all land on the real
     * moment) and re-stamps it in IST.
     *
     * **Byte-stable for anything already IST.** A stamp that already ends in the IST offset and
     * parses to the same instant is returned exactly as stored, so exports of recent sessions do not
     * change by one character. That matters because the crash journal is required to be
     * byte-identical to the CSV export of the same session; a normalizer that re-flowed canonical
     * stamps would have broken that for no gain.
     *
     * @return the IST stamp, or null when no instant can be resolved - in which case the caller
     *         should keep whatever it had rather than write a blank over real data.
     */
    // The parameter is `stored`, NOT `stamp`: a parameter called `stamp` shadows the `stamp(millis)`
    // function, so `return stamp(instant)` would have tried to invoke a String. Kotlin rejects that,
    // but only at compile time, and only in CI.
    fun normalizeToIst(millis: Long?, stored: String?): String? {
        val instant = instantOf(millis, stored) ?: return null
        if (stored != null && stored.endsWith(IST_OFFSET_SUFFIX) && parseMillis(stored) == instant) {
            return stored
        }
        return stamp(instant)
    }

    /** The offset every canonical record stamp ends with. */
    private const val IST_OFFSET_SUFFIX = "+05:30"

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

    /**
     * `HH:mm:ss.SSS` in IST, for the live monitors where a full date on every row is noise.
     *
     * The screens used to cut this out of the stamp by hand - `timestampUtc.takeLast(12)
     * .removeSuffix("Z")` - which is arithmetic on a string whose length and suffix just changed:
     * applied to `2026-09-17T14:27:05.123+05:30` it prints `05.123+05:30`. Parsing the stamp and
     * re-formatting it is correct for every shape this app has ever written, legacy `Z` included.
     */
    fun timeOfDay(millis: Long): String = format("HH:mm:ss.SSS", millis)

    /**
     * `HH:mm:ss.SSS` for any stamp this app has written, or [fallback] when it is missing or
     * unparsable. The single replacement for every `takeLast(12).removeSuffix("Z")` in the UI.
     */
    fun timeOfDay(stamp: String?, fallback: String = "--"): String {
        val millis = parseMillis(stamp) ?: return fallback
        return timeOfDay(millis)
    }

    /**
     * `yyyy-MM-dd HH:mm:ss` in IST for a stamp - the trip-card form, with milliseconds dropped.
     * Replaces `startTimeUtc.take(19).replace("T", " ")`, which happened to survive the zone change
     * by luck and silently dropped the fraction.
     */
    fun dateTime(stamp: String?, fallback: String = "--"): String {
        val millis = parseMillis(stamp) ?: return fallback
        return format("yyyy-MM-dd HH:mm:ss", millis)
    }

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
