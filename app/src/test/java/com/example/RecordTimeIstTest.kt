package com.example

import com.example.data.RecordTime
import com.example.data.SessionTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * IST-only record time (owner mandate 2026-09-17: *"For all records use IST time only no UTC."*).
 *
 * Every stamp the app writes - session JSON, both CSVs, the raw ELM log, the crash journal, the
 * PID discovery export, the fleet export, the backup snapshot, the fuel/expense/service logs - goes
 * through [RecordTime]. These tests pin the three properties that matter:
 *
 *  1. a stamp SAYS its zone (`+05:30`) and shows IST wall time, not UTC;
 *  2. every shape the app has ever written parses back to the SAME instant, so trips recorded
 *     before the change keep their true timeline instead of shifting five and a half hours;
 *  3. the schema keys (`timestampUtc`, `startTimeUtc`, `dateUtc`) are untouched, so existing trip
 *     JSON, Room rows and backups still load.
 */
class RecordTimeIstTest {

    /** 2026-09-17 14:27:05.123 IST == 08:57:05.123 UTC. */
    private val istMillis = 1_789_635_425_123L

    private fun utcWall(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(millis))

    private fun istWall(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        }.format(Date(millis))

    @Test
    fun stampIsIstWallTimeWithItsOffsetPrinted() {
        val stamp = RecordTime.stamp(istMillis)
        assertTrue("stamp must carry the IST offset: $stamp", stamp.endsWith("+05:30"))
        assertTrue("stamp must show IST wall time: $stamp", stamp.startsWith(istWall(istMillis).replace(' ', 'T')))
        // And it must NOT show the UTC wall clock - that is the whole point of the mandate.
        assertFalse("stamp must not be UTC: $stamp", stamp.contains(utcWall(istMillis).substring(11, 16)))
        assertFalse("stamp must not use the Z suffix: $stamp", stamp.endsWith("Z"))
    }

    @Test
    fun stampRoundTripsToTheSameInstant() {
        for (millis in listOf(istMillis, 0L, 1_600_000_000_000L, System.currentTimeMillis())) {
            assertEquals(millis, RecordTime.parseMillis(RecordTime.stamp(millis)))
        }
    }

    @Test
    fun legacyUtcStampsStillParseToTheirTrueInstant() {
        // Written by every build before 1.0.337. Reading these as IST would move an imported trip
        // 5.5 h, which is exactly the duration bug SessionTime was created to kill.
        val legacy = "2026-09-17T08:57:05.123Z"
        val expected = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse(legacy)!!.time
        assertEquals(expected, RecordTime.parseMillis(legacy))
        assertEquals(expected, SessionTime.parseMillis(legacy))
        // Without milliseconds, as older builds and hand-edited files write it.
        assertEquals(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse("2026-09-17T08:57:05Z")!!.time,
            RecordTime.parseMillis("2026-09-17T08:57:05Z")
        )
    }

    @Test
    fun naiveStampsAreReadAsIstBecauseThatIsWhatTheyAlwaysWere() {
        // A session NAME or a log line with no zone was formatted in the device zone - IST here.
        val naive = "2026-09-17 14:27:05.123"
        val expected = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        }.parse(naive)!!.time
        assertEquals(expected, RecordTime.parseMillis(naive))
        // No seconds means :00, and no fraction means .000 - a shape that states less is not a
        // shape that states the same instant. This line used to assert that "2026-09-17 14:27:05"
        // equalled `expected`, i.e. that dropping ".123" still gave 05.123, which is 123 ms of
        // arithmetic nobody can see. It never ran: the commit that added it did not compile, so the
        // assertion was wrong from the day it was written until the build went green.
        assertEquals(expected - 123L, RecordTime.parseMillis("2026-09-17 14:27:05"))
        assertEquals(expected - 5_123L, RecordTime.parseMillis("2026-09-17 14:27"))
        // And the whole family lands on the same IST day, which is the part the mandate is about.
        assertEquals(RecordTime.display(expected), RecordTime.display(expected - 5_123L))
    }

    @Test
    fun anIstStampAndTheSameInstantAsLegacyUtcAgree() {
        // The same moment written by the old build and by the new one must read back identical, or
        // a trip history that spans the upgrade gets a five-hour tear in it.
        val asLegacyUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(istMillis))
        assertEquals(RecordTime.parseMillis(asLegacyUtc), RecordTime.parseMillis(RecordTime.stamp(istMillis)))
    }

    @Test
    fun noShapeLosesItsFractionOrItsSecondsToAGreedyShorterPattern() {
        // CI caught this: SimpleDateFormat.parse reads a PREFIX and ignores the rest, so trying
        // patterns shortest-first let "yyyy-MM-dd HH:mm" swallow "2026-09-17 14:27:05.123" and
        // return the instant of 14:27:00.000 - 65 s and 123 ms off, invisibly.
        val full = istMillis
        assertEquals(full, RecordTime.parseMillis(istWall(full)))
        assertEquals(full, RecordTime.parseMillis(istWall(full).replace(' ', 'T')))
        assertEquals(full, RecordTime.parseMillis(istWall(full).replace(' ', 'T') + "+05:30"))
        // A 'Z' suffix is read as UTC, so the SAME DIGITS with a Z on them are a different instant
        // - 5.5 h later, because IST is 5.5 h ahead of UTC. That is not a bug, it is what the
        // suffix means, and it is why the honest 'Z' rule exists: the old builds stamped IST wall
        // time and then printed a 'Z' that lied about it.
        //
        // This assertion used to add 19 800 000 ms to the PARSED value to make it equal `full`,
        // which put it 11 h out. The offset belongs on the other side: `…14:27:05.123Z` is 5.5 h
        // (19 800 000 ms) LATER than `full`, not earlier. And an instant needs no offset at all
        // once parsed - what actually has to hold is that a legacy stamp of the same INSTANT reads
        // back identical, which is the next assertion's job.
        assertEquals(
            full + 19_800_000L,
            RecordTime.parseMillis(istWall(full).replace(' ', 'T') + "Z")
        )
        // The same instant, one written the old way and one the new way: identical, no arithmetic.
        assertEquals(full, RecordTime.parseMillis(utcWall(full).replace(' ', 'T') + "Z"))
        assertEquals(full, RecordTime.parseMillis(RecordTime.stamp(full)))
        // One and two digit fractions are tenths and hundredths: padded, never truncated.
        val tenth = RecordTime.parseMillis("2026-09-17 14:27:05.1")!!
        assertEquals(100, tenth % 1000)
        val hundredth = RecordTime.parseMillis("2026-09-17T14:27:05.12+05:30")!!
        assertEquals(120, hundredth % 1000)
        // No seconds at all still means :00, not "unparsable".
        assertEquals(RecordTime.parseMillis("2026-09-17 14:27:00"), RecordTime.parseMillis("2026-09-17 14:27"))
    }

    @Test
    fun logStampCarriesTheDateSoRecoveryNeverGuessesIt() {
        val log = RecordTime.logStamp(istMillis)
        assertEquals(istWall(istMillis), log)
        assertTrue("a raw log line must state its calendar day: $log", log.startsWith("20"))
        assertEquals(10, log.substringBefore(' ').length)
    }

    @Test
    fun displayStampIsTheIstDateTheOwnerLives() {
        assertEquals(istWall(istMillis).substring(0, 16), RecordTime.display(istMillis))
    }

    @Test
    fun displayFormsAreCutFromTheInstantNotFromTheStringLength() {
        // Four screens used to render a stamp with `timestampUtc.takeLast(12).removeSuffix("Z")`
        // and one with `take(19).replace("T", " ")`. That is arithmetic on a string whose length
        // and suffix changed with the IST mandate: against
        // `2026-09-17T14:27:05.123+05:30` takeLast(12) prints "05.123+05:30".
        assertEquals("14:27:05.123", RecordTime.timeOfDay(istMillis))
        assertEquals("14:27:05.123", RecordTime.timeOfDay(RecordTime.stamp(istMillis)))
        // The legacy UTC shape must render as the IST wall clock the owner lives in, not as the
        // UTC digits that happen to sit in the file.
        assertEquals("14:27:05.123", RecordTime.timeOfDay("2026-09-17T08:57:05.123Z"))
        assertEquals("2026-09-17 14:27:05", RecordTime.dateTime(RecordTime.stamp(istMillis)))
        assertEquals("2026-09-17 14:27:05", RecordTime.dateTime("2026-09-17T08:57:05.123Z"))
        // Missing or unparsable: the fallback, never a slice of garbage and never 1970.
        assertEquals("--", RecordTime.timeOfDay(null))
        assertEquals("--", RecordTime.timeOfDay(""))
        assertEquals("n/a", RecordTime.dateTime("not a time", "n/a"))
    }

    @Test
    fun zoneLabelSaysIst() {
        // The OFFSET is the part that carries information and it is exact; the abbreviation is
        // whatever the platform's tzdata calls Asia/Kolkata ("IST" on any current JDK,
        // "GMT+05:30" on ancient ones), so it is asserted loosely rather than pinned.
        assertEquals("+05:30", RecordTime.offsetLabel(istMillis))
        val label = RecordTime.zoneLabel()
        assertTrue("zone label must be usable text: '$label'", label.isNotBlank())
        assertTrue("zone label must name IST or its offset: '$label'", label == "IST" || label.contains("05:30"))
    }

    @Test
    fun isZonedTellsAnExplicitZoneFromAGuess() {
        assertTrue(RecordTime.isZoned("2026-09-17T14:27:05.123+05:30"))
        assertTrue(RecordTime.isZoned("2026-09-17T08:57:05.123Z"))
        assertFalse(RecordTime.isZoned("2026-09-17 14:27:05.123"))
        assertFalse(RecordTime.isZoned(null))
        assertFalse(RecordTime.isZoned("   "))
    }

    @Test
    fun unparsableAndMissingStampsAreNullNeverZero() {
        // A zero would silently become 1970 and corrupt every duration computed from it.
        assertNull(RecordTime.parseMillis(null))
        assertNull(RecordTime.parseMillis(""))
        assertNull(RecordTime.parseMillis("   "))
        assertNull(RecordTime.parseMillis("not a time"))
        assertNull(RecordTime.parseMillis("17/09/2026"))
        // Strict parsing: a no-millis stamp must not match the millis pattern by reinterpreting
        // fields, and an impossible date must not roll over into a real one.
        assertNull(RecordTime.parseMillis("2026-13-45T99:99:99.999+05:30"))
    }

    @Test
    fun sessionTimeDelegatesSoImportedTripsKeepTheirDuration() {
        val start = RecordTime.stamp(istMillis)
        val end = RecordTime.stamp(istMillis + 45 * 60_000L)
        assertEquals(45 * 60L, SessionTime.durationSeconds(start, end))
        // A window written half in legacy UTC and half in IST still measures correctly.
        val legacyStart = "2026-09-17T08:57:05.123Z"
        val legacyStartMs = RecordTime.parseMillis(legacyStart)!!
        val newEnd = RecordTime.stamp(legacyStartMs + 3_600_000L)
        assertEquals(3_600L, SessionTime.durationSeconds(legacyStart, newEnd))
    }

    @Test
    fun pickerResultsAreAlwaysZeroPaddedAndParseable() {
        // Owner 2026-09-20: calendar/clock pickers replaced typed dates. A picker result like
        // 2026-9-5 would fail the dialog's parse - the formatters must pad, and the round trip
        // through parseStamp must land on the exact instant the owner dialled in, in IST.
        assertEquals("2026-09-05", RecordTime.pickedDate(2026, 9, 5))
        assertEquals("00:07", RecordTime.pickedTime(0, 7))
        assertEquals("23:59", RecordTime.pickedTime(23, 59))
        val stamp = RecordTime.stamp(
            RecordTime.parseStamp(RecordTime.pickedDate(2026, 9, 20) + "T00:44:00.000+05:30")!!
        )
        assertTrue(stamp.startsWith("2026-09-20T00:44"))
    }
}
