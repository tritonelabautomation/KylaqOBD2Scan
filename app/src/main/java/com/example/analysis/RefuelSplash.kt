package com.example.analysis

/**
 * When did the tank level actually JUMP during this trip?
 *
 * Owner 2026-11-12: "you're showing fuel refill percentage change in all trips why? It
 * should be shown only in the trip where we have refilled right?" Exactly right. The old
 * cards printed "Fill 17.6 % → 93.7 % · PID 34.25 L vs pump 40.27 L" on a 2026-11-10
 * 40.7 km trip that started and ended at the pump level, because the receipt was merely
 * LINKED to the trip (last-refill-odometer semantics: "the batch currently in the tank")
 * and the linked receipt's window was rendered as if it had happened inside the trip.
 *
 * Linking a receipt to a trip is still needed (it names the fuel batch in the tank), but a
 * FILL is a physical event: a 012F rise. So the rise is detected from this trip's OWN tank
 * samples, and a linked receipt is only counted as a fill when the samples show a rise the
 * receipt can explain. No samples, or no rise ⇒ no fill on this trip, whatever the linker
 * says.
 */
object RefuelSplash {

    /** A rise must clear sensor slosh. 012F steps are ~0.39 pt, drive wiggle ±1-2 pt. */
    const val MIN_RISE_PCT = 4.0

    /** The level must sit still (≤ this much wobble) either side of the jump. */
    const val SETTLE_PCT = 1.5

    /** Keep this much time on each side of the jump for the "before"/"after" medians. */
    const val SETTLE_MS = 60_000L

    data class Splash(
        /** Last sample before the rise. */
        val beforePct: Double,
        /** First sample after the rise. */
        val afterPct: Double,
        val atMs: Long,
        /** True when the rise was still climbing when the recording ended. */
        val cutOff: Boolean = false
    ) {
        val risePct: Double get() = afterPct - beforePct
    }

    private fun median(list: List<Double>): Double? {
        if (list.isEmpty()) return null
        val s = list.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2.0
    }

    /**
     * Finds the largest level rise inside [levelSeries] (ts → 012F %).
     *
     * A jump counts as a fill only when the level settled both before and after it: a
     * sustained climb is a fill, an up-down spike (slosh, tilt, cornering) settles back and
     * is rejected. A climb that is still going at the end of the recording is accepted but
     * flagged [Splash.cutOff] so the caller can say the "after" number is a floor.
     */
    fun detect(levelSeries: List<Pair<Long, Double>>, minRisePct: Double = MIN_RISE_PCT): Splash? {
        val samples = levelSeries
            .filter { validPct(it.second) }
            .sortedBy { it.first }
        if (samples.size < 3) return null

        val firstTs = samples.first().first
        val lastTs = samples.last().first
        val origin = samples.first().second // level before any rise in this trip

        var best: Splash? = null
        var bestRise = minRisePct
        var i = 0
        while (i < samples.size - 1) {
            // Start of a candidate climb: a step up by more than the sensor wobble.
            if (samples[i + 1].second - samples[i].second <= SETTLE_PCT) {
                i++
                continue
            }
            val before = median(
                samples.subList(0, i + 1)
                    .filter { it.first >= samples[i].first - SETTLE_MS }
                    .map { it.second }
            ) ?: samples[i].second

            var j = i + 1
            var peak = samples[j].second
            var peakTs = samples[j].first
            while (j < samples.size && samples[j].second >= peak - SETTLE_PCT) {
                if (samples[j].second > peak) {
                    peak = samples[j].second
                    peakTs = samples[j].first
                }
                j++
            }
            val afterWindow = samples.subList(j - 1, samples.size)
                .filter { it.first <= peakTs + SETTLE_MS }
                .map { it.second }
            val after = median(afterWindow) ?: peak
            val cutOff = peakTs == lastTs || (lastTs - peakTs) < 10_000L
            val rise = after - before

            if (rise > bestRise) {
                bestRise = rise
                best = Splash(
                    beforePct = before,
                    afterPct = after,
                    atMs = peakTs,
                    cutOff = cutOff
                )
            }
            i = maxOf(j, i + 1)
            if (j >= samples.size) break
        }

        // A recording that STARTS just before the pump (level climbs from the first pair of
        // samples and never settles below the end) still shows a real fill: compare the
        // opening level with the settled level, as long as the opening was on the road.
        if (best == null && samples.size >= 3) {
            val settledTail = median(
                samples.filter { it.first >= lastTs - SETTLE_MS }.map { it.second }
            ) ?: samples.last().second
            val riseFromStart = settledTail - origin
            if (riseFromStart > minRisePct) {
                best = Splash(
                    beforePct = origin,
                    afterPct = settledTail,
                    atMs = samples.last().first,
                    cutOff = (lastTs - samples.first().first) < 10_000L
                )
            }
        }
        return best
    }

    /**
     * The fill to SHOW for this trip. A rise in this trip's own samples is the only evidence
     * that a fill happened here; the linked receipt supplies station/pump litres when the two
     * agree (the sample rise is at least as big as the receipt window, within float slop),
     * and is otherwise only "the batch in the tank", never a fill card.
     */
    fun resolve(
        levelSeries: List<Pair<Long, Double>>,
        fillBeforePct: Double?,
        fillAfterPct: Double?,
        pumpLitres: Double?,
        linkedReceiptOnTrip: Boolean
    ): Result {
        val splash = detect(levelSeries)
        val receiptWindow = if (fillBeforePct != null && fillAfterPct != null &&
            fillAfterPct - fillBeforePct >= MIN_RISE_PCT
        ) fillAfterPct - fillBeforePct else null

        // A trip that HAS a usable tank series is judged by it alone: no rise in its own
        // samples means no fill here, even if the linker says the receipt landed inside the
        // trip window (that is exactly the 2026-11-10 / 2026-11-12 false positive).
        val samplesSpeak = levelSeries.count { validPct(it.second) } >= 3
        val shown = when {
            splash != null -> splash
            // No usable tank series at all: the trip's own linked window is the only evidence.
            !samplesSpeak && linkedReceiptOnTrip && fillBeforePct != null && fillAfterPct != null -> Splash(
                beforePct = fillBeforePct,
                afterPct = fillAfterPct,
                atMs = 0L,
                cutOff = false
            )
            else -> null
        }

        if (shown == null) {
            // Nothing to show: no fill window, no pump litres — the caller may still name the
            // batch, but must not print a "Fill x → y" line.
            return Result(
                splash = null,
                receiptMatches = false,
                fillBeforePct = null,
                fillAfterPct = null,
                pumpLitres = null,
                evidence = Evidence.NONE
            )
        }

        // Does the receipt describe this rise? The float can under-read a brim (slosh, tilt,
        // the nozzle's last clicks), so a sample rise a little UNDER the receipt window is
        // still the same fill; a wildly larger one is a different event (or a mis-keyed odo).
        val receiptMatches = receiptWindow != null &&
            shown.risePct >= receiptWindow * 0.85

        return Result(
            splash = shown,
            receiptMatches = receiptMatches,
            fillBeforePct = if (receiptMatches) fillBeforePct else shown.beforePct,
            fillAfterPct = if (receiptMatches) fillAfterPct else shown.afterPct,
            pumpLitres = if (receiptMatches) pumpLitres else null,
            evidence = if (splash != null) Evidence.SAMPLES else Evidence.LINKED_WINDOW
        )
    }

    enum class Evidence {
        /** A 012F rise this trip recorded itself. */
        SAMPLES,

        /** No usable samples; the trip's own linked window is the only evidence. */
        LINKED_WINDOW,

        /** No fill on this trip. */
        NONE
    }

    data class Result(
        val splash: Splash?,
        /** True when the linked receipt's window is the one shown (pump litres are trustworthy). */
        val receiptMatches: Boolean,
        /** Tank % before the pump for the shown fill (this trip's samples, else the receipt). */
        val fillBeforePct: Double?,
        /** Tank % at/after auto-cut for the shown fill. */
        val fillAfterPct: Double?,
        /** Pump litres, only when the receipt belongs to the shown rise. */
        val pumpLitres: Double?,
        val evidence: Evidence
    ) {
        val happenedOnThisTrip: Boolean get() = splash != null
    }

    fun validPct(v: Double?): Boolean = v != null && v in 0.0..100.0
}
