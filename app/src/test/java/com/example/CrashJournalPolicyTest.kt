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
    fun noRecordNoSummaryNoStamp() {
        assertNull(CrashJournalPolicy.summarize(null))
        assertNull(CrashJournalPolicy.summarize("   "))
        assertNull(CrashJournalPolicy.crashedAt(null))
        assertNull(CrashJournalPolicy.crashedAt("thread: main\nno stamp here"))
    }
}
