package com.example.data

/**
 * The review that runs BEFORE a merge, and the rules that decide whether the owner has to
 * confirm what he is about to do.
 *
 * Owner, 2026-09-22: *"When I merge fragmented same trip it's showing as recovered and merging
 * multiple times man do a proper review while merging. If more than 40 min difference between
 * merging files ask user to confirm. For different dates ask user confirmation are you sure."*
 *
 * Merging used to be a blind stitch: `mergeSessions` took the selected ids, concatenated every
 * transaction it could read, sorted by wall time and wrote one trip. Nothing looked at WHAT was
 * being joined. Three things followed, and all three are the owner's complaint:
 *
 *  1. **A merge of two unrelated drives looks exactly like a merge of two fragments.** One drive
 *     shredded by process deaths has seconds or a few minutes between the pieces; a Monday trip
 *     merged with a Tuesday trip has a day between them. Both produced the same silent success
 *     toast, so a wrong selection was only discovered later in the trip list.
 *  2. **Fragments that had already been merged came back.** The merged-away raw logs stayed in
 *     `files/raw_logs`, so the next launch's recovery pass rebuilt them as fresh "Recovered Run"
 *     trips and the owner merged the same drive again - each time doubling its telemetry.
 *     [MergeLedger] is the tombstone that stops it; this policy is what TELLS him when a
 *     selection contains one.
 *  3. **Nothing said what the result would be** - how many rows, which pieces were empty,
 *     whether two pieces claimed the same minutes.
 *
 * So the review is now a first-class result: [review] describes the selection in the owner's own
 * terms (dates, gaps, row counts) and [Review.needsConfirmation] says whether the UI must stop
 * and ask. Pure JVM - unit-tested in MergeReviewPolicyTest.
 */
object MergeReviewPolicy {

    /**
     * A gap LONGER than this between two consecutive fragments is not one drive that was cut
     * off - or at least it is not one the app may assume was. The owner picked 40 minutes:
     * long enough to cover a fuel stop, a school run and a tea break inside one journey, short
     * enough that two separate errands an hour apart are never silently glued together.
     *
     * Strictly greater-than: exactly 40 min is still treated as one drive, because the window
     * is measured between the last row of one fragment and the first row of the next, and a
     * poller that answers every few seconds makes the measured gap a floor, not an exact value.
     */
    const val GAP_CONFIRM_MS: Long = 40L * 60_000L

    /**
     * Two fragments whose window AND row count match exactly are the same drive twice - the
     * shape a re-recovered raw log has. Merging them would double every sample, so the review
     * names them instead of quietly concatenating.
     */
    data class Fragment(
        val sessionId: String,
        val name: String,
        /** First data row's instant (IST epoch millis), null when the fragment has none. */
        val startMs: Long?,
        /** Last data row's instant, null when the fragment has none. */
        val endMs: Long?,
        val transactionCount: Int,
        /**
         * True when [MergeLedger] already recorded this id as merged into another trip. Such a
         * fragment must never be merged again: it is a resurrection of data that is already
         * inside a saved trip.
         */
        val alreadyMergedAway: Boolean = false
    ) {
        /** A fragment with no rows contributes nothing and would only pad the merged trip's name. */
        val isEmpty: Boolean get() = transactionCount <= 0 || startMs == null || endMs == null

        /** IST calendar day this fragment belongs to, null when it has no usable instant. */
        val date: String? get() = startMs?.let { RecordTime.format("yyyy-MM-dd", it) }

        /** Days the fragment SPANS - a drive over midnight belongs to both. */
        val dates: List<String>
            get() {
                val a = startMs ?: return emptyList()
                val b = endMs ?: return listOf(RecordTime.format("yyyy-MM-dd", a))
                return distinctDays(a, b)
            }
    }

    /** The silence between two consecutive fragments, in the order they will be stitched. */
    data class Gap(
        val fromSessionId: String,
        val toSessionId: String,
        val fromName: String,
        val toName: String,
        /** Positive = silence between the two; negative = the two OVERLAP in time. */
        val gapMs: Long
    ) {
        val needsConfirm: Boolean get() = gapMs > GAP_CONFIRM_MS
        val isOverlap: Boolean get() = gapMs < 0L
    }

    /** Two selected fragments that carry the same window and the same row count. */
    data class Duplicate(val a: Fragment, val b: Fragment)

    data class Review(
        /** Every selected fragment, in the order it will be stitched (by start instant). */
        val fragments: List<Fragment>,
        val gaps: List<Gap>,
        val duplicates: List<Duplicate>,
        /** IST calendar days covered by the selection, chronological and deduplicated. */
        val dates: List<String>,
        val startMs: Long?,
        val endMs: Long?,
        val totalTransactions: Int
    ) {
        /** Fragments that actually carry rows and are not a resurrection. */
        val usable: List<Fragment>
            get() = fragments.filter { !it.isEmpty && !it.alreadyMergedAway }

        val emptyFragments: List<Fragment> get() = fragments.filter { it.isEmpty }

        val mergedAway: List<Fragment> get() = fragments.filter { it.alreadyMergedAway }

        /** True when there is anything worth writing at all. */
        val mergeable: Boolean get() = usable.size >= 2 && totalTransactions >= 2

        val maxGapMs: Long get() = gaps.maxOfOrNull { it.gapMs } ?: 0L

        val confirmGaps: List<Gap> get() = gaps.filter { it.needsConfirm }

        val overlaps: List<Gap> get() = gaps.filter { it.isOverlap }

        val spansMultipleDates: Boolean get() = dates.size > 1

        /**
         * Whether the UI must stop and ask. Anything that could silently join two unrelated
         * drives, or silently double one drive, is in here - the merge itself never decides
         * this, it only refuses to run unconfirmed when this is true.
         */
        val needsConfirmation: Boolean
            get() = !mergeable ||
                confirmGaps.isNotEmpty() ||
                spansMultipleDates ||
                mergedAway.isNotEmpty() ||
                duplicates.isNotEmpty() ||
                overlaps.isNotEmpty()

        /** One line for the dialog title / toast. */
        fun headline(): String = when {
            // The MOST SPECIFIC reason wins. A selection containing a trip that was already merged
            // away is not an empty selection - it is a resurrection of data that is already inside
            // another trip - and "no OBD rows" would send the owner looking in the wrong place.
            // This is the exact case that produced the loop he reported: re-recovered fragments
            // come back with rows, so the generic branch never described what was actually wrong.
            mergedAway.isNotEmpty() ->
                "${mergedAway.size} selected trip(s) were ALREADY merged into another trip - " +
                    "merging them again would duplicate that drive."
            !mergeable && fragments.size < 2 -> "Select at least 2 trips to merge."
            !mergeable ->
                "Nothing to merge: " +
                    (if (emptyFragments.isNotEmpty()) "${emptyFragments.size} of the selected trips hold no OBD rows" else "no OBD rows") +
                    "."
            duplicates.isNotEmpty() ->
                "${duplicates.size} pair(s) of the selected trips look like the SAME drive twice."
            confirmGaps.isNotEmpty() && spansMultipleDates ->
                "These trips are on ${dates.size} different dates AND have a gap over " +
                    "${GAP_CONFIRM_MS / 60_000L} min between them."
            confirmGaps.isNotEmpty() ->
                "There is a ${formatDuration(maxGapMs)} silence between two of these trips " +
                    "(over ${GAP_CONFIRM_MS / 60_000L} min)."
            spansMultipleDates -> "These trips are on ${dates.size} different dates: ${dates.joinToString(", ")}."
            overlaps.isNotEmpty() -> "Two of these trips overlap in time - they may be the same drive recorded twice."
            else -> "${usable.size} trips, ${formatDuration(endMs!! - startMs!!)} of data, $totalTransactions OBD rows."
        }

        /**
         * The body of the confirmation dialog: what will be joined, what is wrong with it, and
         * what happens to the originals. Nothing here is a guess - every line is a fact the
         * review measured.
         */
        fun bullets(): List<String> {
            val out = mutableListOf<String>()
            if (startMs != null && endMs != null) {
                out += "Merged window: ${RecordTime.display(startMs)} → ${RecordTime.display(endMs)} " +
                    "(${formatDuration(endMs - startMs)}), $totalTransactions OBD rows."
            }
            usable.forEachIndexed { i, f ->
                out += "${i + 1}. ${f.name} · ${RecordTime.display(f.startMs!!)} → " +
                    "${RecordTime.display(f.endMs!!)} · ${f.transactionCount} rows"
            }
            emptyFragments.forEach {
                out += "SKIPPED (no OBD rows): ${it.name} [${it.sessionId}]"
            }
            mergedAway.forEach {
                out += "ALREADY MERGED AWAY - this is a recovered copy of a drive that is already " +
                    "inside another trip: ${it.name} [${it.sessionId}]"
            }
            duplicates.forEach {
                out += "SAME DRIVE TWICE? ${it.a.name} and ${it.b.name} cover the identical window " +
                    "with the identical row count."
            }
            confirmGaps.forEach {
                out += "GAP ${formatDuration(it.gapMs)} between '${it.fromName}' and '${it.toName}' - " +
                    "more than ${GAP_CONFIRM_MS / 60_000L} minutes. A single drive cut off by a killed " +
                    "process does not usually leave a silence this long."
            }
            overlaps.forEach {
                out += "OVERLAP ${formatDuration(-it.gapMs)} between '${it.fromName}' and " +
                    "'${it.toName}' - both claim the same minutes."
            }
            if (spansMultipleDates) {
                out += "DIFFERENT DATES: ${dates.joinToString(", ")}. Fragments of ONE drive share a " +
                    "date unless the drive crossed midnight."
            }
            out += "The selected trips and their recovered raw logs are DELETED once the merged trip " +
                "is saved, and a backup runs immediately after."
            return out
        }
    }

    /**
     * Reviews a selection. Fragments arrive in any order and are sorted by their own data
     * window, because that is the order the merged trip will be stitched in - a fragment with
     * no window at all sorts last and is reported as empty rather than silently dropped.
     */
    fun review(fragmentsIn: List<Fragment>): Review {
        val fragments = fragmentsIn
            .distinctBy { it.sessionId }
            .sortedWith(compareBy({ it.startMs ?: Long.MAX_VALUE }, { it.sessionId }))
        val usable = fragments.filter { !it.isEmpty && !it.alreadyMergedAway }

        val gaps = usable.zipWithNext { a, b ->
            Gap(
                fromSessionId = a.sessionId,
                toSessionId = b.sessionId,
                fromName = a.name,
                toName = b.name,
                gapMs = (b.startMs!!) - (a.endMs!!)
            )
        }

        val duplicates = mutableListOf<Duplicate>()
        for (i in fragments.indices) {
            for (j in i + 1 until fragments.size) {
                val a = fragments[i]
                val b = fragments[j]
                if (a.isEmpty || b.isEmpty) continue
                if (a.startMs == b.startMs && a.endMs == b.endMs &&
                    a.transactionCount == b.transactionCount
                ) {
                    duplicates += Duplicate(a, b)
                }
            }
        }

        val dates = usable.flatMap { it.dates }.distinct().sorted()
        return Review(
            fragments = fragments,
            gaps = gaps,
            duplicates = duplicates,
            dates = dates,
            startMs = usable.firstOrNull()?.startMs,
            endMs = usable.lastOrNull()?.endMs,
            totalTransactions = usable.sumOf { it.transactionCount }
        )
    }

    /**
     * Every IST calendar day from [fromMs] to [toMs] inclusive. A drive that crosses midnight
     * belongs to two days, and the "different dates" question must be asked about it - the
     * owner decides whether a 23:40 → 00:35 fragment pair is one journey.
     */
    fun distinctDays(fromMs: Long, toMs: Long): List<String> {
        if (toMs < fromMs) return distinctDays(toMs, fromMs)
        val dayMs = 86_400_000L
        val out = mutableListOf<String>()
        // Step by whole IST days from the start instant; the last day is added explicitly so a
        // span shorter than a day still reports the day it happened on.
        var cursor = fromMs
        while (cursor <= toMs) {
            val day = RecordTime.format("yyyy-MM-dd", cursor)
            if (day !in out) out += day
            // Jump to the next IST midnight rather than adding 24 h, so a DST-free zone cannot
            // drift and a zone that ever changes offset still lands on real day boundaries.
            val next = RecordTime.parseMillis(
                RecordTime.format("yyyy-MM-dd", cursor + dayMs) + "T00:00:00.000+05:30"
            ) ?: (cursor + dayMs)
            cursor = if (next > cursor) next else cursor + dayMs
        }
        val lastDay = RecordTime.format("yyyy-MM-dd", toMs)
        if (lastDay !in out) out += lastDay
        return out
    }

    /** `45s` / `12 min` / `1 h 05 min` / `2 d 03 h` - the shape the owner reads on a card. */
    fun formatDuration(ms: Long): String {
        if (ms < 0L) return "-" + formatDuration(-ms)
        val totalSec = ms / 1000L
        if (totalSec < 60L) return "${totalSec}s"
        val totalMin = totalSec / 60L
        if (totalMin < 60L) return "${totalMin} min"
        val hours = totalMin / 60L
        val mins = totalMin % 60L
        if (hours < 24L) return "${hours} h ${"%02d".format(mins)} min"
        val days = hours / 24L
        return "${days} d ${"%02d".format(hours % 24L)} h"
    }
}
