package com.example

import com.example.data.MergeLedger
import com.example.data.MergeReviewPolicy
import com.example.data.RecordTime
import com.example.data.SessionRecoveryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * "Do a proper review while merging" (owner 2026-09-22).
 *
 * Merging used to be a blind stitch: `mergeSessions` concatenated whatever it could read, sorted
 * by wall time and wrote one trip, so a Monday drive merged with a Tuesday drive looked exactly
 * like two fragments of one journey - and the fragments' raw logs stayed on disk, so the next
 * launch rebuilt them as fresh "Recovered Run" trips and the owner merged the same drive again,
 * doubling it every pass.
 *
 * These tests pin the three rules he asked for and the tombstone that ends the loop:
 *  1. a silence longer than 40 minutes between two pieces must be confirmed;
 *  2. pieces on different dates must be confirmed;
 *  3. a piece already merged into another trip must be recognised and refused, and recovery must
 *     never resurrect it ([MergeLedger] + [SessionRecoveryPolicy.attachAltitude] below).
 *
 * Pure JVM: [MergeReviewPolicy], [MergeLedger] and the recovery policy are all Android-free apart
 * from one log call on a write failure, which a TemporaryFolder never triggers.
 */
class MergeReviewPolicyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 2026-09-21 08:00:00 IST - the day of the owner's shredded 31 km drive. */
    private val day1 = RecordTime.parseMillis("2026-09-21T08:00:00.000+05:30")!!
    private val day2 = RecordTime.parseMillis("2026-09-22T08:00:00.000+05:30")!!

    private fun frag(
        id: String,
        startMs: Long,
        endMs: Long,
        rows: Int = 500,
        mergedAway: Boolean = false,
        name: String = "Recovered Run ${RecordTime.display(startMs)}"
    ) = MergeReviewPolicy.Fragment(
        sessionId = id,
        name = name,
        startMs = startMs,
        endMs = endMs,
        transactionCount = rows,
        alreadyMergedAway = mergedAway
    )

    // ── 1. the 40-minute rule ─────────────────────────────────────────────────────────

    @Test
    fun fragmentsOfOneDriveMergeWithoutBeingAsked() {
        // Two pieces of the same journey: 90 seconds of silence between them, same date.
        val a = frag("aaa11111", day1, day1 + 12 * 60_000L)
        val b = frag("bbb22222", day1 + 12 * 60_000L + 90_000L, day1 + 31 * 60_000L)
        val review = MergeReviewPolicy.review(listOf(a, b))

        assertTrue("one drive cut in two must be mergeable: ${review.headline()}", review.mergeable)
        assertFalse("and must not stop to ask: ${review.bullets()}", review.needsConfirmation)
        assertEquals(1000, review.totalTransactions)
        assertEquals(day1, review.startMs)
        assertEquals(day1 + 31 * 60_000L, review.endMs)
        assertEquals(listOf("2026-09-21"), review.dates)
        assertEquals(90_000L, review.maxGapMs)
    }

    @Test
    fun exactlyFortyMinutesIsStillOneDrive() {
        val a = frag("aaa11111", day1, day1 + 60_000L)
        val b = frag("bbb22222", day1 + 60_000L + MergeReviewPolicy.GAP_CONFIRM_MS, day1 + 20 * 60_000L)
        val review = MergeReviewPolicy.review(listOf(a, b))
        assertEquals(MergeReviewPolicy.GAP_CONFIRM_MS, review.maxGapMs)
        assertTrue("the threshold is strictly greater-than", review.confirmGaps.isEmpty())
        assertFalse(review.needsConfirmation)
    }

    @Test
    fun aGapOverFortyMinutesMustBeConfirmed() {
        val a = frag("aaa11111", day1, day1 + 10 * 60_000L)
        // 41 minutes of silence: one minute past the owner's threshold.
        val b = frag("bbb22222", day1 + 51 * 60_000L, day1 + 70 * 60_000L)
        val review = MergeReviewPolicy.review(listOf(a, b))

        assertTrue(review.mergeable)
        assertTrue("41 min must be flagged", review.needsConfirmation)
        assertEquals(1, review.confirmGaps.size)
        assertEquals(41 * 60_000L, review.confirmGaps.single().gapMs)
        assertTrue(review.headline().contains("40 min"))
        val gapLine = review.bullets().single { it.startsWith("GAP 41 min") }
        assertTrue("the dialog must say how long the silence was", gapLine.contains("more than 40 minutes"))
        assertTrue("and which two trips it separates: $gapLine", gapLine.contains(a.name) && gapLine.contains(b.name))
    }

    @Test
    fun aThreeHourGapBetweenTwoErrandsIsReportedInHours() {
        val a = frag("morning", day1, day1 + 20 * 60_000L)
        val b = frag("evening", day1 + 200 * 60_000L, day1 + 240 * 60_000L)
        val review = MergeReviewPolicy.review(listOf(a, b))
        assertTrue(review.needsConfirmation)
        assertEquals("3 h 00 min", MergeReviewPolicy.formatDuration(review.maxGapMs))
    }

    // ── 2. different dates ────────────────────────────────────────────────────────────

    @Test
    fun fragmentsOnDifferentDatesMustBeConfirmed() {
        val a = frag("mon", day1, day1 + 30 * 60_000L)
        val b = frag("tue", day2, day2 + 30 * 60_000L)
        val review = MergeReviewPolicy.review(listOf(a, b))

        assertTrue(review.mergeable)
        assertTrue("two dates must stop and ask", review.needsConfirmation)
        assertTrue(review.spansMultipleDates)
        assertEquals(listOf("2026-09-21", "2026-09-22"), review.dates)
        assertTrue(review.headline().contains("different dates"))
        assertTrue(review.bullets().any { it.startsWith("DIFFERENT DATES") })
    }

    @Test
    fun aDriveThatCrossesMidnightCountsBothDaysAndStillAsks() {
        // 23:40 → 00:35 is ONE journey, but it is on two dates - so the app asks and the owner
        // decides, instead of either silently splitting it or silently gluing two days together.
        val start = RecordTime.parseMillis("2026-09-21T23:40:00.000+05:30")!!
        val end = RecordTime.parseMillis("2026-09-22T00:35:00.000+05:30")!!
        val review = MergeReviewPolicy.review(listOf(frag("night", start, end), frag("next", end + 60_000L, end + 20 * 60_000L)))
        assertEquals(listOf("2026-09-21", "2026-09-22"), review.dates)
        assertTrue(review.needsConfirmation)
    }

    @Test
    fun distinctDaysNeverInventsADayBetweenTwoInstants() {
        val from = RecordTime.parseMillis("2026-09-21T08:00:00.000+05:30")!!
        assertEquals(listOf("2026-09-21"), MergeReviewPolicy.distinctDays(from, from + 60_000L))
        assertEquals(
            listOf("2026-09-21", "2026-09-22", "2026-09-23"),
            MergeReviewPolicy.distinctDays(from, from + 2 * 86_400_000L + 3_600_000L)
        )
        // Reversed arguments are the same span, not a negative one.
        assertEquals(
            listOf("2026-09-21", "2026-09-22"),
            MergeReviewPolicy.distinctDays(from + 86_400_000L + 3_600_000L, from)
        )
    }

    // ── 3. the loop: a fragment already merged away ───────────────────────────────────

    @Test
    fun aTripAlreadyMergedAwayIsRefusedNotRestitched() {
        val live = frag("merged1", day1, day1 + 30 * 60_000L)
        val zombie = frag("frag1", day1, day1 + 12 * 60_000L, mergedAway = true)
        val review = MergeReviewPolicy.review(listOf(live, zombie))

        assertTrue("the tombstoned fragment must not count as usable", review.needsConfirmation)
        assertEquals(listOf("merged1"), review.usable.map { it.sessionId })
        assertFalse("one usable fragment cannot be merged", review.mergeable)
        assertTrue(review.headline().contains("ALREADY merged"))
    }

    @Test
    fun theSameDriveTwiceIsNamedBeforeItCanBeDoubled() {
        // Identical window AND identical row count: what a re-recovered raw log looks like.
        val a = frag("aaa", day1, day1 + 30 * 60_000L, rows = 13717)
        val b = frag("bbb", day1, day1 + 30 * 60_000L, rows = 13717)
        val review = MergeReviewPolicy.review(listOf(a, b))
        assertEquals(1, review.duplicates.size)
        assertTrue(review.needsConfirmation)
        assertTrue(review.headline().contains("SAME drive twice"))
    }

    @Test
    fun overlappingFragmentsAreReportedInsteadOfQuietlyDoubled() {
        val a = frag("aaa", day1, day1 + 30 * 60_000L, rows = 100)
        val b = frag("bbb", day1 + 10 * 60_000L, day1 + 40 * 60_000L, rows = 200)
        val review = MergeReviewPolicy.review(listOf(a, b))
        assertEquals(1, review.overlaps.size)
        assertTrue(review.needsConfirmation)
        assertTrue(review.bullets().any { it.startsWith("OVERLAP") })
    }

    // ── selection hygiene ─────────────────────────────────────────────────────────────

    @Test
    fun emptyFragmentsAreSkippedAndSaidSo() {
        val real1 = frag("aaa", day1, day1 + 10 * 60_000L, rows = 400)
        val real2 = frag("bbb", day1 + 11 * 60_000L, day1 + 20 * 60_000L, rows = 500)
        val stub = frag("ccc", day1 + 21 * 60_000L, day1 + 21 * 60_000L, rows = 0)
        val review = MergeReviewPolicy.review(listOf(real1, stub, real2))

        assertEquals(900, review.totalTransactions)
        assertEquals(listOf("aaa", "bbb"), review.usable.map { it.sessionId })
        assertEquals(listOf("ccc"), review.emptyFragments.map { it.sessionId })
        assertTrue(review.bullets().any { it.startsWith("SKIPPED") })
    }

    @Test
    fun aSelectionWithNothingInItIsNotMergeable() {
        val review = MergeReviewPolicy.review(emptyList())
        assertFalse(review.mergeable)
        assertTrue(review.needsConfirmation)
        assertEquals("Select at least 2 trips to merge.", review.headline())
    }

    @Test
    fun fragmentsAreStitchedInTimeOrderWhateverOrderTheyWereSelectedIn() {
        val late = frag("late", day1 + 60 * 60_000L, day1 + 90 * 60_000L)
        val early = frag("early", day1, day1 + 30 * 60_000L)
        val review = MergeReviewPolicy.review(listOf(late, early))
        assertEquals(listOf("early", "late"), review.fragments.map { it.sessionId })
        assertEquals(day1, review.startMs)
        assertEquals(day1 + 90 * 60_000L, review.endMs)
        assertEquals(1, review.gaps.size)
        assertEquals("early", review.gaps.single().fromSessionId)
    }

    @Test
    fun theSameIdSelectedTwiceIsOneFragment() {
        val a = frag("aaa", day1, day1 + 10 * 60_000L)
        val review = MergeReviewPolicy.review(listOf(a, a.copy()))
        assertEquals(1, review.fragments.size)
        assertFalse(review.mergeable)
    }

    @Test
    fun aFragmentWithNoReadableWindowIsReportedEmptyNotGuessed() {
        val noWindow = MergeReviewPolicy.Fragment("ghost", "session ghost", null, null, 0)
        val real = frag("aaa", day1, day1 + 10 * 60_000L)
        val review = MergeReviewPolicy.review(listOf(noWindow, real))
        assertTrue(noWindow.isEmpty)
        assertNull(noWindow.date)
        assertEquals(listOf("ghost"), review.emptyFragments.map { it.sessionId })
        assertFalse(review.mergeable)
    }

    @Test
    fun durationsReadTheWayTheOwnerReadsThem() {
        assertEquals("45s", MergeReviewPolicy.formatDuration(45_000L))
        assertEquals("12 min", MergeReviewPolicy.formatDuration(12 * 60_000L))
        assertEquals("1 h 05 min", MergeReviewPolicy.formatDuration(65 * 60_000L))
        assertEquals("2 d 03 h", MergeReviewPolicy.formatDuration(51 * 3_600_000L))
        assertEquals("-41 min", MergeReviewPolicy.formatDuration(-41 * 60_000L))
    }

    // ── the tombstone list itself ─────────────────────────────────────────────────────

    @Test
    fun theLedgerRemembersEveryIdAMergeConsumed() {
        val ledger = MergeLedger(tmp.newFile("merged_sessions.log"))
        assertTrue(ledger.ids().isEmpty())

        ledger.record(listOf("aaa", "bbb"), intoId = "merged1", atMs = 1_800_000_000_000L)
        assertTrue(ledger.contains("aaa"))
        assertTrue(ledger.contains("bbb"))
        assertFalse(ledger.contains("merged1"))

        // A NEW process reads the same file - which is the whole point: recovery runs from the
        // keep-alive service and a boot receiver, neither of which saw the merge happen.
        val restarted = MergeLedger(java.io.File(tmp.root, "merged_sessions.log"))
        assertEquals(setOf("aaa", "bbb"), restarted.ids())
        assertEquals("merged1", restarted.entries().first { it.mergedAwayId == "aaa" }.intoId)
    }

    @Test
    fun recordingTheSameIdTwiceKeepsTheFirstEntry() {
        val file = tmp.newFile("ledger.log")
        val ledger = MergeLedger(file)
        ledger.record(listOf("aaa"), intoId = "first", atMs = 1L)
        ledger.record(listOf("aaa"), intoId = "second", atMs = 2L)
        assertEquals(1, ledger.entries().size)
        assertEquals("first", ledger.entries().single().intoId)
    }

    @Test
    fun aTruncatedOrMalformedLedgerLineNeverCostsTheRest() {
        val file = tmp.newFile("ledger.log")
        file.writeText("aaa|merged1|10\n\ngarbage\n|merged2|20\nbbb|merged2\nccc|merged3|notANumber\n")
        val ledger = MergeLedger(file)
        val entries = ledger.entries()
        assertEquals(listOf("aaa", "bbb", "ccc"), entries.map { it.mergedAwayId })
        assertEquals(0L, entries.single { it.mergedAwayId == "ccc" }.atMs)
    }

    @Test
    fun deletingAMergedTripReleasesItsTombstones() {
        val ledger = MergeLedger(tmp.newFile("ledger.log"))
        ledger.record(listOf("aaa", "bbb"), intoId = "merged1")
        ledger.record(listOf("ccc"), intoId = "merged2")
        // merged1 was deleted by the owner; merged2 is still a live trip.
        ledger.retainOnly(setOf("merged2"))
        assertEquals(setOf("ccc"), ledger.ids())
    }

    @Test
    fun aLedgerFileThatDoesNotExistYetIsSimplyEmpty() {
        val ledger = MergeLedger(java.io.File(tmp.root, "never/written.log"))
        assertTrue(ledger.entries().isEmpty())
        assertFalse(ledger.contains("anything"))
    }

    // ── counting a journal without parsing it (the stop path's memory fix) ────────────

    @Test
    fun dataRowCountCountsRowsWithoutParsingThem() {
        val file = tmp.newFile("journal.csv")
        file.writeText("header,a,b\n1,2,3\n4,5,6\n7,8,9\n")
        assertEquals(3, SessionRecoveryPolicy.dataRowCount(file))
    }

    @Test
    fun aHeaderOnlyJournalHoldsZeroRows() {
        val file = tmp.newFile("header_only.csv")
        file.writeText("header,a,b\n")
        assertEquals(0, SessionRecoveryPolicy.dataRowCount(file))
        assertEquals(0, SessionRecoveryPolicy.dataRowCount(tmp.newFile("empty.csv")))
    }

    @Test
    fun aMissingLastNewlineStillCountsItsRow() {
        val file = tmp.newFile("no_trailing_nl.csv")
        file.writeText("header\nrow1\nrow2")
        assertEquals(2, SessionRecoveryPolicy.dataRowCount(file))
    }

    @Test
    fun aMissingFileCountsAsZeroNotAsAnError() {
        assertEquals(0, SessionRecoveryPolicy.dataRowCount(java.io.File(tmp.root, "never/written.csv")))
    }

    // ── altitude re-association during a merge ────────────────────────────────────────

    private data class Row(val ts: Long, val alt: Double?)

    @Test
    fun aRowWithNoAltitudeTakesTheFragmentFixBesideIt() {
        val rows = listOf(Row(1_000L, null), Row(1_400L, null), Row(9_000L, null))
        val fixes = listOf(1_100L to 512.0, 9_200L to 604.5)
        val out = SessionRecoveryPolicy.attachAltitude(
            rows = rows,
            timestamp = { it.ts },
            hasAltitude = { it.alt != null },
            fixes = fixes,
            withAltitude = { r, a -> r.copy(alt = a) }
        )
        assertEquals(512.0, out[0].alt!!, 0.0001)
        assertEquals(512.0, out[1].alt!!, 0.0001)
        assertEquals(604.5, out[2].alt!!, 0.0001)
    }

    @Test
    fun aRowFarFromAnyFixStaysNullRatherThanBorrowingAnElevation() {
        val rows = listOf(Row(1_000L, null), Row(60_000L, null))
        val fixes = listOf(1_200L to 512.0)
        val out = SessionRecoveryPolicy.attachAltitude(
            rows = rows,
            timestamp = { it.ts },
            hasAltitude = { it.alt != null },
            fixes = fixes,
            withAltitude = { r, a -> r.copy(alt = a) }
        )
        assertEquals(512.0, out[0].alt!!, 0.0001)
        assertNull("a fix from a minute ago is not this row's elevation", out[1].alt)
    }

    @Test
    fun anAltitudeARowAlreadyCarriesIsNeverOverwritten() {
        val rows = listOf(Row(1_000L, 300.0), Row(1_100L, null))
        val fixes = listOf(1_000L to 999.0, 1_100L to 999.0)
        val out = SessionRecoveryPolicy.attachAltitude(
            rows = rows,
            timestamp = { it.ts },
            hasAltitude = { it.alt != null },
            fixes = fixes,
            withAltitude = { r, a -> r.copy(alt = a) }
        )
        assertEquals(300.0, out[0].alt!!, 0.0001)
        assertEquals(999.0, out[1].alt!!, 0.0001)
    }

    @Test
    fun unorderedInputStillPairsEachRowWithItsNearestFix() {
        val rows = listOf(Row(9_000L, null), Row(1_000L, null), Row(5_000L, null))
        val fixes = listOf(9_100L to 600.0, 900L to 500.0)
        val out = SessionRecoveryPolicy.attachAltitude(
            rows = rows,
            timestamp = { it.ts },
            hasAltitude = { it.alt != null },
            fixes = fixes,
            withAltitude = { r, a -> r.copy(alt = a) }
        )
        assertEquals(600.0, out[0].alt!!, 0.0001)
        assertEquals(500.0, out[1].alt!!, 0.0001)
        assertNull("5 s away from either fix: outside the 1.5 s tolerance", out[2].alt)
    }

    @Test
    fun noFixesAtAllLeavesEveryRowExactlyAsItWas() {
        val rows = listOf(Row(1_000L, null), Row(2_000L, 400.0))
        val out = SessionRecoveryPolicy.attachAltitude(
            rows = rows,
            timestamp = { it.ts },
            hasAltitude = { it.alt != null },
            fixes = emptyList(),
            withAltitude = { r, a -> r.copy(alt = a) }
        )
        assertEquals(rows, out)
    }
}
