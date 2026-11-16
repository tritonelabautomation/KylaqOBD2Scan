package com.example

import com.example.data.HeapPressure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind the memory watchdog (owner report 2026-11-16: repeated
 * `OutOfMemoryError` at `target footprint 536870912` and a GcWatcher finalizer timeout).
 *
 * These are the numbers that decide WHEN the app sheds RAM, so they are tested directly rather
 * than inferred from a stack trace after the fact - the previous crashes could not say how close
 * to the ceiling the heap had been, which is exactly what made them unfixable by inspection.
 */
class HeapPressureTest {

    private val mb = 1024L * 1024L

    /** The owner's actual `largeHeap` ceiling on the OnePlus CPH2793. */
    private val largeHeap = 512L * mb

    @Test
    fun usedBytesIsTotalMinusFree() {
        assertEquals(300L * mb, HeapPressure.usedBytes(400L * mb, 100L * mb))
    }

    @Test
    fun usedBytesNeverGoesNegative() {
        // A Runtime that reports free > total is nonsense; it must not produce a negative
        // "usage", which would read as the safest possible heap.
        assertEquals(0L, HeapPressure.usedBytes(100L, 200L))
    }

    @Test
    fun usageFractionIsTheShareOfTheCeiling() {
        assertEquals(0.5, HeapPressure.usageFraction(256L * mb, largeHeap), 1e-9)
        assertEquals(1.0, HeapPressure.usageFraction(largeHeap, largeHeap), 1e-9)
        assertEquals(0.0, HeapPressure.usageFraction(0L, largeHeap), 1e-9)
    }

    @Test
    fun usageFractionClampsAndSurvivesANonsenseLimit() {
        // Over the stated limit clamps to 1.0 rather than reporting >100 %.
        assertEquals(1.0, HeapPressure.usageFraction(largeHeap * 2, largeHeap), 1e-9)
        // maxMemory() of 0 would be a divide-by-zero; assume the worst instead.
        assertEquals(1.0, HeapPressure.usageFraction(1L, 0L), 1e-9)
        assertEquals(1.0, HeapPressure.usageFraction(1L, -5L), 1e-9)
    }

    @Test
    fun bandsAreSeventyAndEightyFivePercent() {
        // A ceiling of 100 units makes every percentage exact in double arithmetic, so this
        // asserts the BAND EDGES rather than a rounding accident of 512 MB / 100.
        assertEquals(HeapPressure.Level.NORMAL, HeapPressure.levelOf(0L, 100L))
        assertEquals(HeapPressure.Level.NORMAL, HeapPressure.levelOf(69L, 100L))
        // The boundary belongs to the DANGEROUS side: shedding a rebuildable cache is free,
        // an OutOfMemoryError is not.
        assertEquals(HeapPressure.Level.ELEVATED, HeapPressure.levelOf(70L, 100L))
        assertEquals(HeapPressure.Level.ELEVATED, HeapPressure.levelOf(84L, 100L))
        assertEquals(HeapPressure.Level.CRITICAL, HeapPressure.levelOf(85L, 100L))
        assertEquals(HeapPressure.Level.CRITICAL, HeapPressure.levelOf(100L, 100L))
        // And the same edges on the owner's real 512 MB ceiling, at a whole number of MB.
        assertEquals(HeapPressure.Level.NORMAL, HeapPressure.levelOf(340L * mb, largeHeap))   // 66 %
        assertEquals(HeapPressure.Level.ELEVATED, HeapPressure.levelOf(370L * mb, largeHeap)) // 72 %
        assertEquals(HeapPressure.Level.CRITICAL, HeapPressure.levelOf(460L * mb, largeHeap)) // 90 %
    }

    @Test
    fun theOwnersCrashWouldHaveBeenCriticalLongBeforeItThrew() {
        // The failing allocation had 73728 bytes free of a 512 MB ceiling - 0.014 % free.
        val used = largeHeap - 73_728L
        assertEquals(HeapPressure.Level.CRITICAL, HeapPressure.levelOf(used, largeHeap))
        // And the guard would have started shedding at 70 %, i.e. with ~150 MB still spare.
        val shedAt = (largeHeap * HeapPressure.ELEVATED_FRACTION).toLong()
        assertTrue("only ${(largeHeap - shedAt) / mb} MB spare at the shed threshold",
            largeHeap - shedAt > 150L * mb)
    }

    @Test
    fun levelForCombinesRuntimeNumbers() {
        val total = 460L * mb
        val free = 30L * mb       // 430 MB used of 512 -> 84 % -> ELEVATED
        assertEquals(HeapPressure.Level.ELEVATED, HeapPressure.levelFor(total, free, largeHeap))
    }

    @Test
    fun ringSizeFollowsTheBandAndNeverGoesNegative() {
        assertEquals(200, HeapPressure.ringSizeAt(HeapPressure.Level.NORMAL, 200, 80, 20))
        assertEquals(80, HeapPressure.ringSizeAt(HeapPressure.Level.ELEVATED, 200, 80, 20))
        assertEquals(20, HeapPressure.ringSizeAt(HeapPressure.Level.CRITICAL, 200, 80, 20))
        assertEquals(0, HeapPressure.ringSizeAt(HeapPressure.Level.CRITICAL, 200, 80, -10))
    }

    @Test
    fun aThrottledPublisherPublishesOnFirstCallAndThenRespectsTheInterval() {
        // Never published: always publish, so a screen is never blank waiting for a window.
        assertTrue(HeapPressure.shouldPublish(0L, 1_000L, 250L))
        // Inside the window: suppressed. This is the per-frame churn being removed.
        assertFalse(HeapPressure.shouldPublish(1_000L, 1_100L, 250L))
        // Window elapsed: publish.
        assertTrue(HeapPressure.shouldPublish(1_000L, 1_250L, 250L))
        assertTrue(HeapPressure.shouldPublish(1_000L, 5_000L, 250L))
    }

    @Test
    fun aClockThatMovesBackwardsStillPublishes() {
        // SystemClock.elapsedRealtime is monotonic, but a caller may hand over wall time.
        // A stuck UI is worse than one extra list.
        assertTrue(HeapPressure.shouldPublish(10_000L, 9_000L, 250L))
    }

    @Test
    fun aZeroIntervalDisablesThrottling() {
        assertTrue(HeapPressure.shouldPublish(1_000L, 1_000L, 0L))
    }

    @Test
    fun publishIntervalSlowsDownAsPressureRises() {
        assertEquals(250L, HeapPressure.publishIntervalAt(HeapPressure.Level.NORMAL, 250L, 1_000L, 2_000L))
        assertEquals(1_000L, HeapPressure.publishIntervalAt(HeapPressure.Level.ELEVATED, 250L, 1_000L, 2_000L))
        assertEquals(2_000L, HeapPressure.publishIntervalAt(HeapPressure.Level.CRITICAL, 250L, 1_000L, 2_000L))
        assertEquals(0L, HeapPressure.publishIntervalAt(HeapPressure.Level.CRITICAL, 250L, 1_000L, -1L))
    }

    @Test
    fun everyTrimLevelTheSystemSendsMapsToSomethingUseful() {
        // ComponentCallbacks2 constants: RUNNING_MODERATE 5, RUNNING_LOW 10, RUNNING_CRITICAL 15,
        // UI_HIDDEN 20, BACKGROUND 40, MODERATE 60, COMPLETE 80.
        assertEquals(HeapPressure.Level.ELEVATED, HeapPressure.levelForTrim(5))
        assertEquals(HeapPressure.Level.ELEVATED, HeapPressure.levelForTrim(10))
        assertEquals(HeapPressure.Level.ELEVATED, HeapPressure.levelForTrim(15))
        // BACKGROUND means "this process is on the LRU list and memory is short". It is a real
        // signal, so it sheds - but it is NOT terminal: the recording is a foreground service and
        // keeps running. Dropping the live HUD to its 20-row emergency size on every background
        // trim would degrade the dashboards for no gain, so it stays at ELEVATED.
        assertEquals(HeapPressure.Level.ELEVATED, HeapPressure.levelForTrim(40))
        // MODERATE / COMPLETE: this process is near or at the front of the kill queue.
        assertEquals(HeapPressure.Level.CRITICAL, HeapPressure.levelForTrim(60))
        assertEquals(HeapPressure.Level.CRITICAL, HeapPressure.levelForTrim(80))
        assertEquals(HeapPressure.Level.NORMAL, HeapPressure.levelForTrim(0))
    }

    @Test
    fun losingForegroundIsNotACrisis() {
        // TRIM_MEMORY_UI_HIDDEN (20) only means the UI is gone; the recording must NOT be
        // shrunk for that, or every time the owner pockets the phone the HUD would reset.
        assertEquals(HeapPressure.Level.NORMAL, HeapPressure.levelForTrim(20))
    }
}
