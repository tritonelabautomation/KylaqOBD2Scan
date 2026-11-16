package com.example.data

/**
 * Pure arithmetic behind the live-memory watchdog. No Android types, so every rule below is
 * unit-tested on the JVM.
 *
 * ## Why this exists (owner report 2026-11-16)
 *
 * Two crash records arrived from the owner's phone on the same day:
 *
 * ```
 * java.lang.OutOfMemoryError: Failed to allocate a 56 byte allocation with 73728 free bytes
 *   and 72KB until OOM, target footprint 536870912, growth limit 536870912;
 *   giving up on allocation because <1% of heap free after GC.
 *     at android.os.Parcel.obtain(Parcel.java:5591)          <- binder thread, 56 bytes
 * ```
 * and
 * ```
 * java.util.concurrent.TimeoutException:
 *   com.android.internal.os.BinderInternal$GcWatcher.finalize() timed out after 10 seconds
 * ```
 *
 * Neither stack is the culprit - the binder thread and the finalizer are just the first places
 * that happened to allocate while the heap was full. A **56-byte** allocation failing is the
 * signature of a heap that is full of long-lived data, with GC having already given up. The
 * second record is the same disease: with the heap permanently near its limit, ART spends the
 * whole time collecting, which starves the finalizer watchdog and turns a slow app into a
 * "crash".
 *
 * So the question this object answers is not "which allocation failed" but "how close to the
 * ceiling are we, and what must be dropped before the next allocation fails". [HeapPressure]
 * answers with arithmetic; [com.example.data.MemoryGuard] feeds it real numbers from
 * `Runtime` + `ComponentCallbacks2`, and the managers that own RAM rings respond by shrinking.
 *
 * ## The thresholds
 *
 * `used / max` is sampled AFTER a GC-ish moment, so the numbers are stable enough to act on:
 *
 *  - **[Level.NORMAL]** under 70 %: nothing to do. A healthy recording lives well below this.
 *  - **[Level.ELEVATED]** 70-85 %: drop the *derived* copies first - the log ring the console
 *    renders, the research history - because they are rebuilt from sources that are still on
 *    disk. Nothing the owner recorded is lost.
 *  - **[Level.CRITICAL]** 85 % and above: keep only what the next few seconds of a drive need.
 *    The journal on disk is already the full drive (flushed per line), so a smaller RAM ring
 *    costs the owner nothing.
 *
 * The bands are deliberately conservative. The app used to request `largeHeap` and ran at a
 * 512 MB ceiling - that ceiling is where it died - so the numbers were tuned against it: at
 * 512 MB, 85 % is ~435 MB, still ~77 MB of room for the allocation that would otherwise throw.
 * `largeHeap` is gone now, but the relative thresholds are what matter, not the absolute size.
 */
object HeapPressure {

    /** How full the heap is, coarsely. Ordered from safe to dangerous. */
    enum class Level { NORMAL, ELEVATED, CRITICAL }

    /** Fraction of the heap in use at or above which derived caches are shed. */
    const val ELEVATED_FRACTION = 0.70

    /** Fraction of the heap in use at or above which only the essential rings are kept. */
    const val CRITICAL_FRACTION = 0.85

    /** Bytes currently held by live objects, from `Runtime.totalMemory() - freeMemory()`. */
    fun usedBytes(totalMemory: Long, freeMemory: Long): Long =
        (totalMemory - freeMemory).coerceAtLeast(0L)

    /**
     * `used / max`, clamped to 0..1. Returns 1.0 for a nonsensical `maxBytes` (0 or negative)
     * rather than dividing by zero: a Runtime that cannot state its limit is treated as
     * "assume the worst", because under-reporting pressure is what kills the process.
     */
    fun usageFraction(usedBytes: Long, maxBytes: Long): Double {
        if (maxBytes <= 0L) return 1.0
        return (usedBytes.toDouble() / maxBytes.toDouble()).coerceIn(0.0, 1.0)
    }

    /**
     * The band [usedBytes] of [maxBytes] falls in.
     *
     * `>=` on each boundary, so a value exactly on 70 % is already ELEVATED - when in doubt,
     * shed earlier. The cost of shedding a rebuildable cache is zero; the cost of guessing
     * wrong is an `OutOfMemoryError`.
     */
    fun levelOf(usedBytes: Long, maxBytes: Long): Level {
        val fraction = usageFraction(usedBytes, maxBytes)
        return when {
            fraction >= CRITICAL_FRACTION -> Level.CRITICAL
            fraction >= ELEVATED_FRACTION -> Level.ELEVATED
            else -> Level.NORMAL
        }
    }

    /** Convenience for callers holding raw Runtime numbers. */
    fun levelFor(totalMemory: Long, freeMemory: Long, maxMemory: Long): Level =
        levelOf(usedBytes(totalMemory, freeMemory), maxMemory)

    /**
     * How many rows a live RAM ring may keep at [level].
     *
     * The ring is a HUD convenience, never the record: `RecordingManager` flushes every frame to
     * the on-disk journal before it returns, and `SessionJournal` does the same, so shrinking
     * this cannot lose a drive. [critical] is deliberately small (a few seconds of polling) -
     * the live screens show a window, not a history.
     */
    fun ringSizeAt(level: Level, normal: Int, elevated: Int, critical: Int): Int = when (level) {
        Level.NORMAL -> normal
        Level.ELEVATED -> elevated
        Level.CRITICAL -> critical
    }.coerceAtLeast(0)

    /**
     * Whether a throttled publisher may publish now.
     *
     * The live paths used to republish a whole list on EVERY frame - at 40-60 frames per second
     * that is 40-60 fresh lists (and their `toList()` copies) per second, forever, for the length
     * of a drive. That churn is what keeps the heap full between collections. A publisher that
     * only runs when [minIntervalMs] has elapsed emits the same information to a human eye while
     * allocating orders of magnitude less.
     *
     * A [lastPublishMs] of 0 means "never published", which always publishes. Time that appears
     * to run backwards (clock change) also publishes, because a stuck UI is worse than a copy.
     */
    fun shouldPublish(lastPublishMs: Long, nowMs: Long, minIntervalMs: Long): Boolean {
        if (lastPublishMs == 0L) return true
        if (minIntervalMs <= 0L) return true
        val elapsed = nowMs - lastPublishMs
        return elapsed < 0L || elapsed >= minIntervalMs
    }

    /**
     * The publish interval to use at [level]: normal cadence when healthy, slower when the heap
     * is tight, so the guard reduces allocation pressure before it has to drop data.
     */
    fun publishIntervalAt(level: Level, normalMs: Long, elevatedMs: Long, criticalMs: Long): Long =
        when (level) {
            Level.NORMAL -> normalMs
            Level.ELEVATED -> elevatedMs
            Level.CRITICAL -> criticalMs
        }.coerceAtLeast(0L)

    // The ComponentCallbacks2 constants, mirrored as plain Ints so this object stays free of
    // Android imports (and therefore unit-testable). Values are from the platform docs.
    const val TRIM_MEMORY_RUNNING_MODERATE = 5
    const val TRIM_MEMORY_RUNNING_LOW = 10
    const val TRIM_MEMORY_RUNNING_CRITICAL = 15
    const val TRIM_MEMORY_UI_HIDDEN = 20
    const val TRIM_MEMORY_BACKGROUND = 40
    const val TRIM_MEMORY_MODERATE = 60
    const val TRIM_MEMORY_COMPLETE = 80

    /**
     * Maps [android.content.ComponentCallbacks2] trim levels onto a [Level].
     *
     * Three bands, because the system's signal means three different things:
     *
     *  - `RUNNING_MODERATE`(5) / `LOW`(10) / `CRITICAL`(15): the DEVICE - not just this process -
     *    is short of memory while we are in the foreground. [Level.ELEVATED].
     *  - `BACKGROUND`(40): this process is on the LRU list. Real pressure, so shed - but the
     *    recording is a foreground service and keeps running, so the live HUD must NOT collapse
     *    to its emergency size on every trip to the background.
     *  - `MODERATE`(60) / `COMPLETE`(80): this process is at or near the front of the kill queue.
     *    [Level.CRITICAL] - keep only what the next few seconds need.
     *
     * `TRIM_MEMORY_UI_HIDDEN`(20) is deliberately NORMAL: it only says the UI went away, which
     * happens every time the owner pockets the phone mid-drive. Treating that as pressure would
     * shrink the live HUD on every screen-off, which is a bug, not a rescue.
     */
    fun levelForTrim(trimLevel: Int): Level = when {
        trimLevel == TRIM_MEMORY_UI_HIDDEN -> Level.NORMAL
        trimLevel >= TRIM_MEMORY_MODERATE -> Level.CRITICAL
        trimLevel >= TRIM_MEMORY_RUNNING_MODERATE -> Level.ELEVATED
        else -> Level.NORMAL
    }
}
