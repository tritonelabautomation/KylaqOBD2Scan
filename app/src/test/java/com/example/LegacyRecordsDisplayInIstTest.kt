package com.example

import com.example.data.RecordTime
import com.example.data.db.entities.instantMs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Owner mandate 2026-09-17, stated twice: *"All logs, trends everything should be IST even the old
 * logs should be IST by default."*
 *
 * Two separate claims are pinned here, because they fail in opposite directions.
 *
 * **1. Display is IST by construction, not by accident of the device.** 21 formatters used to be
 * built with no timezone, which means "whatever this phone is set to". On the owner's phone that is
 * IST so it looked right - the defect only appears when the zone changes: a restore onto another
 * handset, a trip abroad, an emulator, a QA device. [deviceZoneCannotMoveARecordTime] sets the
 * default zone to three others and requires identical output.
 *
 * **2. Old records resolve to the instant they really happened.** The owner's history was written by
 * builds that disagreed with each other about what a `Z` meant, so there is no single correct rule
 * for "an old stamp": [bothLegacyConventionsResolveToTheSameInstant] writes the same drive both ways
 * and requires the same IST time out.
 */
class LegacyRecordsDisplayInIstTest {

    /** 2026-09-17 14:27:05.123 IST == 08:57:05.123 UTC. Derived, never hand-typed. */
    private val instant = istMillis(2026, 9, 17, 14, 27, 5, 123)

    private lateinit var savedDefault: TimeZone

    @Before
    fun saveZone() {
        savedDefault = TimeZone.getDefault()
    }

    @After
    fun restoreZone() {
        // A leaked default zone would silently rewrite every other suite's expectations.
        TimeZone.setDefault(savedDefault)
    }

    private fun istMillis(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int, ms: Int = 0): Long =
        java.util.Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).apply {
            clear()
            set(y, mo - 1, d, h, mi, s)
            set(java.util.Calendar.MILLISECOND, ms)
        }.timeInMillis

    private fun inZone(zone: String, millis: Long, pattern: String): String =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone(zone) }
            .format(Date(millis))

    // ── 1. Pinned to IST ───────────────────────────────────────────────────────────────────

    @Test
    fun deviceZoneCannotMoveARecordTime() {
        val pattern = "yyyy-MM-dd HH:mm:ss"
        val expected = inZone("Asia/Kolkata", instant, pattern)
        assertEquals("sanity: the instant is 14:27:05 in IST", "2026-09-17 14:27:05", expected)

        // The zones a real device could plausibly be set to. If display followed the device, each
        // of these would print a different time for one and the same drive.
        for (zone in listOf("Asia/Kolkata", "UTC", "America/New_York", "Asia/Tokyo", "Europe/London")) {
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            assertEquals(
                "a record must read the same in IST whatever the device is set to (device=$zone)",
                expected,
                RecordTime.format(pattern, instant)
            )
            assertEquals(
                "the reusable formatter must be pinned too (device=$zone)",
                expected,
                RecordTime.formatter(pattern).format(Date(instant))
            )
        }
    }

    @Test
    fun theChartAndListShapesArePinnedAsWell() {
        // These are the exact patterns the trend axis, the trip rows and the day headers use.
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        assertEquals("14:27:05", RecordTime.format("HH:mm:ss", instant))
        assertEquals("14:27", RecordTime.format("HH:mm", instant))
        assertEquals("17 Sep", RecordTime.format("d MMM", instant))
        assertEquals("Thursday, Sep 17", RecordTime.format("EEEE, MMM d", instant))
        assertEquals("20260917_142705", RecordTime.format("yyyyMMdd_HHmmss", instant))
    }

    @Test
    fun stampAndDisplayCarryTheOffsetOrTheIstDate() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        assertEquals("2026-09-17T14:27:05.123+05:30", RecordTime.stamp(instant))
        assertEquals("2026-09-17 14:27", RecordTime.display(instant))
        assertEquals("2026-09-17 14:27:05.123", RecordTime.logStamp(instant))
        assertEquals("+05:30", RecordTime.offsetLabel(instant))
    }

    // ── 2. Old records, both conventions ───────────────────────────────────────────────────

    @Test
    fun bothLegacyConventionsResolveToTheSameInstant() {
        // Auditing build 1.0.336 found 18 formatters printing a literal `Z`: 15 formatted in UTC and
        // meant it, and 3 formatted in the device zone while printing the same `Z` (ZipImporter's
        // no-meta fallback, TripRepository's AI-analysis stamp, PidDiscoveryService's export). Both
        // shapes are in the owner's existing history.
        val honestUtc = inZone("UTC", instant, "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        val dishonestZ = inZone("Asia/Kolkata", instant, "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        assertEquals("2026-09-17T08:57:05.123Z", honestUtc)
        assertEquals("2026-09-17T14:27:05.123Z", dishonestZ)

        // Read as strings alone they are five and a half hours apart, and NO single rule gets both
        // right: parse the honest one as IST and the drive moves 5.5 h early, parse the dishonest
        // one as UTC and it moves 5.5 h late. That is why the millis decide.
        assertTrue(
            "the two legacy shapes must differ",
            RecordTime.parseMillis(honestUtc) != RecordTime.parseMillis(dishonestZ)
        )

        // Every Room row stores the instant beside the string, and that instant has no zone to get
        // wrong - so both records resolve to the drive that actually happened.
        assertEquals(instant, RecordTime.instantOf(instant, honestUtc))
        assertEquals(instant, RecordTime.instantOf(instant, dishonestZ))
        assertEquals(
            "both must DISPLAY as the same IST time",
            "2026-09-17 14:27:05",
            RecordTime.format("yyyy-MM-dd HH:mm:ss", RecordTime.instantOf(instant, honestUtc)!!)
        )
        assertEquals(
            "2026-09-17 14:27:05",
            RecordTime.format("yyyy-MM-dd HH:mm:ss", RecordTime.instantOf(instant, dishonestZ)!!)
        )
    }

    @Test
    fun aRecordWithNoMillisFallsBackToItsStringByTheDocumentedDefault() {
        // Imports and hand-edited CSVs can carry a stamp and no millis. There is nothing to
        // corroborate, so the documented default applies: an explicit offset is honoured, `Z` reads
        // as UTC, and a naive stamp reads as IST (it was always written in the device zone).
        assertEquals(instant, RecordTime.instantOf(null, "2026-09-17T08:57:05.123Z"))
        assertEquals(instant, RecordTime.instantOf(null, "2026-09-17T14:27:05.123+05:30"))
        assertEquals(instant, RecordTime.instantOf(null, "2026-09-17 14:27:05.123"))
    }

    @Test
    fun zeroAndNegativeMillisAreUnknownNotTheEpoch() {
        // 0 is 1970. Treating an unfilled column as a real instant would plot a drive forty years
        // before the car was built, and the string beside it is the better evidence.
        assertEquals(instant, RecordTime.instantOf(0L, "2026-09-17 14:27:05.123"))
        assertEquals(instant, RecordTime.instantOf(-1L, "2026-09-17 14:27:05.123"))
        assertNull("neither field yields an instant", RecordTime.instantOf(0L, null))
        assertNull(RecordTime.instantOf(null, "not a timestamp"))
    }

    @Test
    fun millisWinEvenWhenTheStringLooksMoreSpecific() {
        // A stale or hand-edited string must not outrank the instant the recorder captured.
        assertEquals(
            instant,
            RecordTime.instantOf(instant, "1999-01-01T00:00:00.000Z")
        )
    }

    // ── 3. Uptime is not an instant ────────────────────────────────────────────────────────

    /**
     * `TransactionRecord.timestampMonotonic` is filled with `SystemClock.elapsedRealtime()` by the
     * live transport and scheduler, and `RecordingManager` copied it into the indexed
     * `TelemetrySampleEntity.timestamp` column - which is the trend chart's X axis and the fuel
     * integrator's timeline. So every trip recorded before this fix holds UPTIME there. Nothing
     * crashed: uptime still increases, so the shape of every curve stayed right while each label on
     * it was a 1970 clock time.
     */
    @Test
    fun uptimeIsRejectedAsAnInstantAndTheStampDecidesInstead() {
        // Three hours of uptime, and eleven days - the realistic range for a phone.
        for (uptime in listOf(10_800_000L, 950_000_000L)) {
            assertEquals(
                "uptime must not be mistaken for an instant",
                instant,
                RecordTime.instantOf(uptime, "2026-09-17T14:27:05.123+05:30")
            )
            assertEquals(false, RecordTime.isPlausibleEpoch(uptime))
        }
        // And what that uptime USED to render as, so the regression is visible rather than implied.
        val asDate = RecordTime.format("yyyy-MM-dd HH:mm:ss", 10_800_000L)
        assertTrue(
            "the old axis label was a 1970 clock time, not a drive time: $asDate",
            asDate.startsWith("1970-01-01")
        )
    }

    @Test
    fun aRealEpochRowPassesThroughUntouched() {
        // Rows written since the write-path fix store a true instant; the repair must not disturb
        // them, or fixing old trips would break new ones.
        assertEquals(instant, RecordTime.instantOf(instant, "anything unparsable"))
        assertEquals(true, RecordTime.isPlausibleEpoch(instant))
        // The bounds are wide enough for any real record and tight enough to exclude uptime.
        assertEquals(true, RecordTime.isPlausibleEpoch(RecordTime.MIN_PLAUSIBLE_EPOCH_MS))
        assertEquals(true, RecordTime.isPlausibleEpoch(RecordTime.MAX_PLAUSIBLE_EPOCH_MS))
        assertEquals(false, RecordTime.isPlausibleEpoch(RecordTime.MIN_PLAUSIBLE_EPOCH_MS - 1))
        assertEquals(false, RecordTime.isPlausibleEpoch(RecordTime.MAX_PLAUSIBLE_EPOCH_MS + 1))
        assertEquals(false, RecordTime.isPlausibleEpoch(null))
    }

    @Test
    fun aStoredSampleRowResolvesToTheDriveNotToTheBoot() {
        val repaired = sample(timestamp = 10_800_000L) // uptime, as recorded before the fix
        assertEquals(instant, repaired.instantMs)
        assertEquals(
            "the chart axis must read the drive time in IST",
            "14:27:05",
            RecordTime.format("HH:mm:ss", repaired.instantMs)
        )

        // A row written since the fix already holds the instant.
        assertEquals(instant, sample(timestamp = instant).instantMs)

        // A row with neither a usable number nor a usable stamp keeps its value: an implausible
        // number still sorts, which beats dropping the sample and shortening a curve.
        val hopeless = sample(timestamp = 42L).copy(timestampUtc = "unparsable")
        assertEquals(42L, hopeless.instantMs)
    }

    private fun sample(timestamp: Long) = com.example.data.db.entities.TelemetrySampleEntity(
        tripId = "trip-1",
        timestamp = timestamp,
        timestampUtc = "2026-09-17T14:27:05.123+05:30",
        ecuCanId = "7E8",
        pid = "010D",
        parameterName = "Vehicle Speed",
        rawHex = "410D50",
        numericValue = 80.0,
        displayValue = "80",
        unit = "km/h"
    )

    // ── 4. What leaves the phone is IST too ────────────────────────────────────────────────

    @Test
    fun exportsRestateEveryLegacyShapeInIst() {
        val ist = "2026-09-17T14:27:05.123+05:30"
        // A trip recorded before the mandate: honest UTC from RecordingManager.
        assertEquals(ist, RecordTime.normalizeToIst(null, "2026-09-17T08:57:05.123Z"))
        // The dishonest writers: IST digits wearing a Z.
        assertEquals(ist, RecordTime.normalizeToIst(instant, "2026-09-17T14:27:05.123Z"))
        // A naive stamp was always written in the device zone, i.e. IST.
        assertEquals(ist, RecordTime.normalizeToIst(null, "2026-09-17 14:27:05.123"))
        // Uptime in the millis column, real instant in the stamp.
        assertEquals(ist, RecordTime.normalizeToIst(10_800_000L, "2026-09-17 14:27:05.123"))
    }

    @Test
    fun normalizingIsByteIdenticalForAStampThatIsAlreadyIst() {
        // The crash journal must stay byte-identical to the CSV export of the same session. A
        // normalizer that re-flowed a canonical stamp would have broken that for no gain, so an
        // already-IST stamp comes back character for character.
        val canonical = RecordTime.stamp(instant)
        assertEquals("2026-09-17T14:27:05.123+05:30", canonical)
        assertEquals(canonical, RecordTime.normalizeToIst(null, canonical))
        assertEquals(canonical, RecordTime.normalizeToIst(instant, canonical))
        // Same for a whole row's worth of shapes the recorder actually writes.
        for (ms in listOf(instant, instant + 1L, instant + 1_000L, instant + 86_400_000L)) {
            val written = RecordTime.stamp(ms)
            assertEquals("re-export must not alter a canonical stamp", written, RecordTime.normalizeToIst(ms, written))
        }
    }

    @Test
    fun normalizingNeverBlanksOutAValueItCannotRead() {
        // A caller keeps its original rather than writing an empty string over real data.
        assertNull(RecordTime.normalizeToIst(null, null))
        assertNull(RecordTime.normalizeToIst(0L, "not a timestamp"))
    }

    @Test
    fun aWholeTripHistorySpanningTheChangeHasNoFiveHourTear() {
        // The upgrade boundary: one drive recorded by 1.0.336 (honest UTC strings) and the next by
        // the IST build, an hour apart. Displayed times must be an hour apart, not 6.5 h.
        val oldBuild = instant
        val newBuild = instant + 3_600_000L

        val oldRow = Pair(oldBuild, inZone("UTC", oldBuild, "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"))
        val newRow = Pair(newBuild, RecordTime.stamp(newBuild))

        val shown = listOf(oldRow, newRow).map { (ms, stamp) ->
            RecordTime.instantOf(ms, stamp)?.let { RecordTime.format("yyyy-MM-dd HH:mm:ss", it) }
        }
        assertEquals(listOf("2026-09-17 14:27:05", "2026-09-17 15:27:05"), shown)
    }
}
