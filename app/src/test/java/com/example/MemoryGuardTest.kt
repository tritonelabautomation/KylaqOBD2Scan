package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.HeapGuard
import com.example.data.HeapPressure
import com.example.data.MemoryGuard
import com.example.data.RawLogManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The memory watchdog, end to end on the JVM.
 *
 * Owner report 2026-11-16 (two crashes from the same phone on the same day):
 *
 * ```
 * java.lang.OutOfMemoryError: Failed to allocate a 56 byte allocation ... target footprint 536870912
 * java.util.concurrent.TimeoutException: ...GcWatcher.finalize() timed out after 10 seconds
 * ```
 *
 * The 56-byte allocation is the tell: the heap was already full of LONG-LIVED data, so the fix is
 * not "free one object" but "notice the ceiling coming and shed before it". These tests pin the
 * three things that make that real:
 *
 *  1. a shed is actually delivered to the rings that own RAM;
 *  2. those rings really do shrink, and shrink back no further than the guard allows;
 *  3. the console the owner reads keeps working throughout - a "fix" that blanks the live HUD
 *     would trade a crash for a silent app, which is worse.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryGuardTest {

    private lateinit var context: Context
    private lateinit var rawLogDir: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        MemoryGuard.resetForTests()
        rawLogDir = File(context.filesDir, "guard_raw_logs_${System.nanoTime()}").apply { mkdirs() }
    }

    /**
     * Feeds [n] frames through the real listener entry point.
     *
     * Publishing is deliberately throttled to [RawLogManager.PUBLISH_INTERVAL_MS] (that throttle
     * IS the fix - the old code rebuilt the list on every frame), so a burst of frames does not
     * each publish. Callers that assert on [RawLogManager.logs] cross the window first via
     * [waitForPublishWindow].
     */
    private fun feed(manager: RawLogManager, n: Int) {
        for (i in 0 until n) {
            manager.onRawLog(
                timestampUtc = "2026-11-16T20:35:00.000+05:30",
                timestampMonotonic = i.toLong(),
                isTx = false,
                canId = "7E8",
                rawText = "04410C0F28",
                status = "OK"
            )
        }
    }

    /** Crosses one publish window so the next frame definitely republishes. */
    private fun waitForPublishWindow() {
        Thread.sleep(RawLogManager.PUBLISH_INTERVAL_MS + 20L)
    }

    @Test
    fun aRegisteredShedderIsCalledImmediatelyWithTheCurrentBand() {
        val seen = mutableListOf<HeapPressure.Level>()
        val shedder: (HeapPressure.Level) -> Unit = { seen.add(it) }
        MemoryGuard.addShedder(shedder)
        assertEquals(listOf(HeapPressure.Level.NORMAL), seen)
        // Keep a strong reference for the GC's benefit, exactly as the real owners do.
        assertNotNull(shedder)
    }

    @Test
    fun aForcedBandReachesTheShedder() {
        val seen = mutableListOf<HeapPressure.Level>()
        val shedder: (HeapPressure.Level) -> Unit = { seen.add(it) }
        MemoryGuard.addShedder(shedder)
        seen.clear()
        MemoryGuard.forceBandForTest(HeapPressure.Level.CRITICAL)
        assertEquals(HeapPressure.Level.CRITICAL, seen.last())
        assertEquals(HeapPressure.Level.CRITICAL, MemoryGuard.currentLevel())
    }

    @Test
    fun theRawLogRingShrinksUnderPressureAndKeepsShowingFrames() {
        val manager = RawLogManager(rawLogDir)
        feed(manager, HeapGuard.RAW_LOG_RING)
        // One more frame, after a full window, so the published list reflects the burst.
        waitForPublishWindow()
        feed(manager, 1)
        assertEquals(HeapGuard.RAW_LOG_RING, manager.logs.value.size)

        MemoryGuard.forceBandForTest(HeapPressure.Level.CRITICAL)

        // Shrunk to the emergency cap...
        assertTrue(
            "expected <= ${HeapGuard.RAW_LOG_RING_CRITICAL}, was ${manager.logs.value.size}",
            manager.logs.value.size <= HeapGuard.RAW_LOG_RING_CRITICAL
        )
        // ...but not blanked: the console still has frames to draw.
        assertFalse(manager.logs.value.isEmpty())

        // And the live HUD keeps moving while the heap is tight.
        waitForPublishWindow()
        feed(manager, 10)
        assertTrue(manager.logs.value.isNotEmpty())
        assertTrue(manager.logs.value.size <= HeapGuard.RAW_LOG_RING_CRITICAL)
        // The ring itself is capped, not just the published view.
        assertEquals(HeapGuard.RAW_LOG_RING_CRITICAL, manager.currentRingLimit)
    }

    @Test
    fun theRawLogRingComesBackWhenPressureClears() {
        val manager = RawLogManager(rawLogDir)
        MemoryGuard.forceBandForTest(HeapPressure.Level.CRITICAL)
        assertEquals(HeapGuard.RAW_LOG_RING_CRITICAL, manager.currentRingLimit)
        feed(manager, HeapGuard.RAW_LOG_RING)
        assertTrue(manager.logs.value.size <= HeapGuard.RAW_LOG_RING_CRITICAL)

        // The squeeze ends. NORMAL must be DELIVERED to the rings, not swallowed - an early-return
        // for NORMAL would leave every ring small for the rest of the process, so the owner would
        // silently lose the full console scrollback after the first memory warning of a drive.
        MemoryGuard.forceBandForTest(HeapPressure.Level.NORMAL)
        assertEquals(HeapGuard.RAW_LOG_RING, manager.currentRingLimit)
        assertEquals(HeapPressure.Level.NORMAL, MemoryGuard.currentLevel())

        // And the ring really refills to the restored cap.
        waitForPublishWindow()
        feed(manager, HeapGuard.RAW_LOG_RING)
        waitForPublishWindow()
        feed(manager, 1)
        assertTrue(
            "expected the ring to refill past the emergency cap, was ${manager.logs.value.size}",
            manager.logs.value.size > HeapGuard.RAW_LOG_RING_CRITICAL
        )
    }

    @Test
    fun theRingLimitsFollowTheBandInBothDirections() {
        val manager = RawLogManager(rawLogDir)
        assertEquals(HeapGuard.RAW_LOG_RING, manager.currentRingLimit)

        MemoryGuard.forceBandForTest(HeapPressure.Level.ELEVATED)
        assertEquals(HeapGuard.RAW_LOG_RING_ELEVATED, manager.currentRingLimit)

        MemoryGuard.forceBandForTest(HeapPressure.Level.CRITICAL)
        assertEquals(HeapGuard.RAW_LOG_RING_CRITICAL, manager.currentRingLimit)

        MemoryGuard.forceBandForTest(HeapPressure.Level.ELEVATED)
        assertEquals(HeapGuard.RAW_LOG_RING_ELEVATED, manager.currentRingLimit)

        MemoryGuard.forceBandForTest(HeapPressure.Level.NORMAL)
        assertEquals(HeapGuard.RAW_LOG_RING, manager.currentRingLimit)
    }

    @Test
    fun shrinkingTheRingNeverLosesTheFile() {
        // The whole design rests on this: the ring is a HUD window over a file that already has
        // every line. If a shed could lose disk data the trade would be indefensible.
        val manager = RawLogManager(rawLogDir)
        manager.startFileLogging("guardtest")
        try {
            feed(manager, 50)
            MemoryGuard.forceBandForTest(HeapPressure.Level.CRITICAL)
            feed(manager, 50)
            val file = manager.stopFileLogging()
            assertNotNull(file)
            val lines = file!!.readLines().filter { it.contains("RX <") }
            assertEquals("every frame must still be on disk", 100, lines.size)
        } finally {
            runCatching { manager.stopFileLogging() }
        }
    }

    @Test
    fun publishingIsThrottledButTheFirstCallAlwaysPublishes() {
        // Never leave a screen blank waiting for a window to open.
        assertTrue(MemoryGuard.shouldPublish(0L, 1_000L))
        // 250 ms window at NORMAL.
        assertFalse(MemoryGuard.shouldPublish(1_000L, 1_050L))
        assertTrue(MemoryGuard.shouldPublish(1_000L, 1_250L))
    }

    @Test
    fun theGuardDescribesItselfForACrashRecord() {
        val state = MemoryGuard.describeState()
        // The fields the two 2026-11-16 reports were MISSING.
        assertTrue(state, state.contains("used="))
        assertTrue(state, state.contains("max="))
        assertTrue(state, state.contains("level="))
        assertTrue(state, state.contains("sheds="))
    }

    @Test
    fun pressureLogIsBoundedAndReadable() {
        MemoryGuard.start(context)
        try {
            MemoryGuard.forceBandForTest(HeapPressure.Level.CRITICAL)
            val tail = MemoryGuard.pressureLogTail(2_000)
            assertNotNull("the guard must leave a diagnostic trail", tail)
            assertTrue(tail!!, tail.contains("level=CRITICAL"))
            // The diagnostic file lives outside crash_logs/ and stays bounded - assert the name
            // so a future rename cannot quietly start writing huge files into the crash dir.
            assertEquals("memory_pressure.log", MemoryGuard.PRESSURE_LOG_NAME)
        } finally {
            // Never leave a 5-second sampler running for the next test class in this JVM.
            MemoryGuard.resetForTests()
        }
    }

    @Test
    fun theDefaultRingIsTheFullOneUntilTheGuardSaysOtherwise() {
        // Sanity on the caps themselves: the guard must not be shrinking anything by default.
        assertEquals(1000, HeapGuard.RAW_LOG_RING)
        assertTrue(HeapGuard.RAW_LOG_RING_ELEVATED < HeapGuard.RAW_LOG_RING)
        assertTrue(HeapGuard.RAW_LOG_RING_CRITICAL < HeapGuard.RAW_LOG_RING_ELEVATED)
        assertTrue(HeapGuard.LIVE_RAM_ROWS_CRITICAL < HeapGuard.LIVE_RAM_ROWS_ELEVATED)
        assertTrue(HeapGuard.LIVE_RAM_ROWS_ELEVATED < HeapGuard.LIVE_RAM_ROWS)
    }
}
