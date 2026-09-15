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
     * Accepted shapes, most specific first. The recorder writes the first one
     * (`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`, UTC); older builds and hand-edited files may lack
     * milliseconds, and display-style names use a space separator.
     */
    private val PATTERNS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm"
    )

    /** Epoch milliseconds, or null when the stamp is missing, blank or unparsable. */
    fun parseMillis(utcStamp: String?): Long? {
        val text = utcStamp?.trim().orEmpty()
        if (text.isEmpty()) return null
        for (pattern in PATTERNS) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                    // Strict: "2026-09-15T16:07:42Z" must NOT match the .SSS pattern by
                    // silently reinterpreting fields.
                    isLenient = false
                }.parse(text)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

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
