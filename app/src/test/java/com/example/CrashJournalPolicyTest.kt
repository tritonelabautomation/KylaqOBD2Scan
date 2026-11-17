package com.example

import com.example.data.CrashJournalPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crash journal's pure core (owner 2026-09-20: "Again app is crashing when I open it
 * crashes. No app crash logs why it has crashed"): which starts open in safe mode, what a
 * record carries, and which two lines of a stack trace become the owner-visible reason.
 * The frame lines below use REAL tab characters, exactly like Throwable.printStackTrace
 * writes them, so the summariser is tested against the shape it will actually meet.
 */
class CrashJournalPolicyTest {

    private fun record(stack: String) = CrashJournalPolicy.formatRecord(
        istTime = "2026-09-20 11:04:05",
        versionName = "1.0.485",
        device = "motorola edge 20 (Android 13, SDK 33)",
        threadName = "main",
        stackTrace = stack
    )

    @Test
    fun safeModeOnlyAfterTwoConsecutiveDeadStarts() {
        assertFalse(CrashJournalPolicy.shouldEnterSafeMode(0))
        assertFalse(CrashJournalPolicy.shouldEnterSafeMode(1))
        assertTrue(CrashJournalPolicy.shouldEnterSafeMode(CrashJournalPolicy.SAFE_MODE_AFTER))
        assertTrue(CrashJournalPolicy.shouldEnterSafeMode(5))
        assertEquals(2, CrashJournalPolicy.SAFE_MODE_AFTER)
    }

    @Test
    fun crashFileNameCarriesTheEpochMillis() {
        assertEquals("crash_1758345600000.txt", CrashJournalPolicy.fileNameFor(1_758_345_600_000L))
    }

    @Test
    fun recordKeepsIstStampVersionDeviceThreadAndTheWholeStack() {
        val r = record("java.lang.IllegalStateException: boom\n\tat com.example.Foo.bar(Foo.kt:42)")
        assertTrue(r.contains("crashed_at: 2026-09-20 11:04:05"))
        assertTrue(r.contains("app_version: 1.0.485"))
        assertTrue(r.contains("device: motorola edge 20"))
        assertTrue(r.contains("thread: main"))
        assertTrue(r.contains("java.lang.IllegalStateException: boom"))
        assertEquals("2026-09-20 11:04:05", CrashJournalPolicy.crashedAt(r))
    }

    @Test
    fun summaryNamesTheDeepestCauseAndTheAppFrameBelowIt() {
        val r = record(
            listOf(
                "java.lang.RuntimeException: Unable to start activity ComponentInfo{...}",
                "\tat android.app.ActivityThread.performLaunchActivity(ActivityThread.java:1)",
                "\tat com.example.MainActivity.onCreate(MainActivity.kt:160)",
                "Caused by: java.lang.ClassCastException: java.lang.Long cannot be cast to java.lang.Float",
                "\tat android.app.SharedPreferencesImpl.getFloat(SharedPreferencesImpl.java:2)",
                "\tat com.example.data.SettingsRepository.<init>(SettingsRepository.kt:170)",
                "\tat com.example.di.AppContainer.init(AppContainer.kt:80)"
            ).joinToString("\n")
        )
        val summary = CrashJournalPolicy.summarize(r)!!
        assertTrue(summary.startsWith("Caused by: java.lang.ClassCastException"))
        // The culprit frame is the first com.example line BELOW the deepest cause -
        // the wrapper's own frames (MainActivity.onCreate) must not be blamed.
        assertTrue(summary.contains("com.example.data.SettingsRepository.<init>(SettingsRepository.kt:170)"))
        assertFalse(summary.contains("MainActivity.onCreate"))
        assertFalse(summary.contains("\t"))
    }

    @Test
    fun summaryWithoutCauseChainUsesTheExceptionLineItself() {
        val r = record(
            listOf(
                "java.lang.IllegalStateException: boom",
                "\tat android.os.Looper.loop(Looper.java:9)",
                "\tat com.example.data.Foo.bar(Foo.kt:42)"
            ).joinToString("\n")
        )
        val summary = CrashJournalPolicy.summarize(r)!!
        assertTrue(summary.startsWith("java.lang.IllegalStateException: boom"))
        assertTrue(summary.contains("com.example.data.Foo.bar(Foo.kt:42)"))
    }

    @Test
    fun summaryOfForeignFramesStillNamesTheException() {
        val r = record(
            listOf(
                "java.lang.NullPointerException: Attempt to invoke virtual method on a null object",
                "\tat android.app.SharedPreferencesImpl.getFloat(SharedPreferencesImpl.java:2)"
            ).joinToString("\n")
        )
        assertEquals(
            "java.lang.NullPointerException: Attempt to invoke virtual method on a null object",
            CrashJournalPolicy.summarize(r)
        )
    }

    @Test
    fun aRecordCanCarryTheHeapStateThatMadeAnOomFixable() {
        // Owner crashes 2026-11-16: the stack said only that a 56-byte allocation failed. Without
        // the heap numbers there was no way to tell a leak from a legitimately full drive, which
        // is exactly why they recurred. The heap line must be recorded and must NOT be mistaken
        // for part of the stack when summarising.
        val r = CrashJournalPolicy.formatRecord(
            istTime = "2026-11-16 20:35:00",
            versionName = "1.0.860",
            device = "OnePlus CPH2793 (Android 16, SDK 36)",
            threadName = "binder:5625_1",
            stackTrace = "java.lang.OutOfMemoryError: Failed to allocate a 56 byte allocation\n" +
                "\tat android.os.Parcel.obtain(Parcel.java:5591)\n" +
                "\tat com.example.data.RecordingManager.recordTransaction(RecordingManager.kt:495)",
            heapState = "used=498MB max=512MB (97%) level=CRITICAL trim=15 sheds=12"
        )
        assertTrue(r.contains("heap: used=498MB max=512MB (97%) level=CRITICAL"))
        assertEquals("2026-11-16 20:35:00", CrashJournalPolicy.crashedAt(r))
        val summary = CrashJournalPolicy.summarize(r)!!
        assertTrue(summary.startsWith("java.lang.OutOfMemoryError"))
        assertFalse("heap line is not a stack frame", summary.contains("level=CRITICAL"))
    }

    @Test
    fun aPressureTailAppendsAfterTheStackAndIsIgnoredBySummarize() {
        val r = CrashJournalPolicy.formatRecord(
            istTime = "2026-11-16 12:32:00",
            versionName = "1.0.860",
            device = "OnePlus CPH2793",
            threadName = "FinalizerWatchdogDaemon",
            stackTrace = "java.util.concurrent.TimeoutException: GcWatcher timed out\n" +
                "\tat com.example.data.RecordingManager.recordTransaction(RecordingManager.kt:495)",
            pressureTail = "2026-11-16 12:31:50+05:30 sample level=CRITICAL used=500MB max=512MB (97%)"
        )
        assertTrue(r.contains("memory pressure log"))
        val summary = CrashJournalPolicy.summarize(r)!!
        assertTrue(summary.startsWith("java.util.concurrent.TimeoutException"))
        assertFalse(summary.contains("pressure log"))
    }

    @Test
    fun recordsWithoutHeapStateAreUnchanged() {
        // Older records (and older callers) must still parse exactly as before.
        val r = record("java.lang.IllegalStateException: boom")
        assertFalse(r.contains("heap:"))
        assertEquals("java.lang.IllegalStateException: boom", CrashJournalPolicy.summarize(r))
    }

    @Test
    fun noRecordNoSummaryNoStamp() {
        assertNull(CrashJournalPolicy.summarize(null))
        assertNull(CrashJournalPolicy.summarize("   "))
        assertNull(CrashJournalPolicy.crashedAt(null))
        assertNull(CrashJournalPolicy.crashedAt("thread: main\nno stamp here"))
    }
}
