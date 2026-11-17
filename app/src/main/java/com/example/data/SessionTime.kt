package com.example.data

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Parses the session timestamps the recorder writes, so an imported trip can keep the time
 * window it was actually recorded in.
 *
 * Why this exists (owner 2026-09-15, "did you add altitude info from GPS into trip log?"):
 * while making the trip log survive backup -> reinstall -> import, the importer's trip row
 * turned out to be stamped `startTimestamp = now - 60 s`, `endTimestamp = now` and
 * `durationSeconds = 60` for EVERY restored trip - so the whole imported history looked like
 * a set of one-minute drives that had just finished, and anything derived from duration
 * (average speed, idle share, L/h trends) was wrong. The session JSON already carries
 * `startTimeUtc` / `endTimeUtc`; this helper reads them.
 *
 * Pure JVM (no Android types) so it is unit-testable.
 */
object SessionTime {

    /**
     * Epoch milliseconds, or null when the stamp is missing, blank or unparsable.
     *
     * Delegates to [RecordTime.parseMillis], which is the single tolerant reader for every stamp
     * this app has ever written. That matters now because the recorder changed clocks: owner
     * mandate 2026-09-17, "For all records use IST time only no UTC." A trip file may carry
     *
     *  - `2026-09-17T14:27:05.123+05:30` - written from 1.0.337 on, offset stated explicitly;
     *  - `2026-09-16T08:57:10.520Z`      - written before that, genuine UTC;
     *  - `2026-09-16 08:57`              - a session NAME, naive and always local (IST).
     *
     * All three must yield the same instant they meant when they were written, or an imported trip
     * gets a duration shifted by five and a half hours - the exact bug this helper was created to
     * kill. The parameter keeps the name `utcStamp` because callers and the JSON keys say "utc".
     */
    fun parseMillis(utcStamp: String?): Long? = RecordTime.parseMillis(utcStamp)

    /**
     * Duration in whole seconds, or null when either end is unknown or the window is
     * negative (a corrupt file must not produce a negative trip duration).
     */
    fun durationSeconds(startUtc: String?, endUtc: String?): Long? {
        val start = parseMillis(startUtc) ?: return null
        val end = parseMillis(endUtc) ?: return null
        val seconds = (end - start) / 1000L
        return if (seconds >= 0L) seconds else null
    }
}
