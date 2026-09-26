package com.example.data

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * The app's own crash recorder - the direct answer to "No app crash logs why it has
 * crashed" (owner 2026-09-20). Installed from [com.example.KylaqApplication] before any
 * activity, service or receiver runs, so EVERY uncaught exception in the process is
 * written to `files/crash_logs/crash_<epochMs>.txt` FIRST - main thread, background
 * thread, coroutine, keep-alive service, even the Android Auto host's callbacks - and
 * only then handed to Android's own handler, so system behaviour (process death, sticky
 * service restart, "App keeps stopping") is unchanged.
 *
 * The files are never deleted by the app (never-lose-logs mandate), ride along in every
 * Drive backup, and drive two UI surfaces: the "Previous run crashed" card on a normal
 * start, and the dependency-free safe-mode screen after
 * [CrashJournalPolicy.SAFE_MODE_AFTER] consecutive starts died before their first frame -
 * which is what turns "when I open it crashes" from a dead end into a readable reason.
 */
object CrashJournal {

    private const val DIR_NAME = "crash_logs"
    private const val STREAK_FILE = "crash_streak.txt"

    fun journalDir(context: Context): File = File(context.filesDir, DIR_NAME)

    /** Installs the process-wide recorder. Call exactly once, from Application.onCreate. */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { record(appContext, thread, throwable) }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(2)
            }
        }
    }

    private fun record(context: Context, thread: Thread, throwable: Throwable) {
        val now = System.currentTimeMillis()
        val stack = StringWriter().also { sw -> throwable.printStackTrace(PrintWriter(sw)) }.toString()
        val dir = journalDir(context)
        dir.mkdirs()
        // IST, not UTC (owner mandate 2026-09-17): the crash record says when it happened
        // on the owner's own clock.
        File(dir, CrashJournalPolicy.fileNameFor(now)).writeText(
            CrashJournalPolicy.formatRecord(
                istTime = RecordTime.display(now),
                versionName = com.example.BuildConfig.VERSION_NAME,
                device = "${Build.MANUFACTURER} ${Build.MODEL} " +
                    "(Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})",
                threadName = thread.name,
                stackTrace = stack
            )
        )
        writeStreak(context, readStreak(context) + 1)
    }

    /** Called once the UI survived its first seconds: this start was not a crash. */
    fun startupCompleted(context: Context) { writeStreak(context, 0) }

    /** "Try normal start" on the safe-mode screen: forgive the streak and retry. */
    private const val PREFS_NAME = "crash_journal_prefs"
    private const val KEY_DISMISSED_CRASH = "last_dismissed_crash_file"

    fun enterNormalModeAgain(context: Context) { writeStreak(context, 0) }

    fun shouldEnterSafeMode(context: Context): Boolean =
        CrashJournalPolicy.shouldEnterSafeMode(readStreak(context))

    /** Recorded crashes, newest first. */
    fun crashFiles(context: Context): List<File> =
        journalDir(context).listFiles()
            ?.filter { it.isFile && it.name.startsWith("crash_") && it.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun latestCrashFile(context: Context): File? =
        crashFiles(context).firstOrNull()

    fun latestCrashText(context: Context): String? =
        latestCrashFile(context)?.let { runCatching { it.readText() }.getOrNull() }

    fun lastCrashSummary(context: Context): String? =
        CrashJournalPolicy.summarize(latestCrashText(context))

    fun lastCrashedAt(context: Context): String? =
        CrashJournalPolicy.crashedAt(latestCrashText(context))

    /** True if the newest crash file has not been dismissed yet. */
    fun hasUnseenCrash(context: Context): Boolean {
        val latest = latestCrashFile(context) ?: return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dismissed = prefs.getString(KEY_DISMISSED_CRASH, null)
        return latest.name != dismissed
    }

    /** Marks the newest crash file as seen/dismissed so the notice does not repeat on every launch. */
    fun dismissCrashNotice(context: Context) {
        val latest = latestCrashFile(context) ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_DISMISSED_CRASH, latest.name).apply()
    }

    private fun streakFile(context: Context) = File(context.filesDir, STREAK_FILE)

    private fun readStreak(context: Context): Int =
        runCatching { streakFile(context).readText().trim().toInt() }.getOrDefault(0)

    private fun writeStreak(context: Context, value: Int) {
        runCatching { streakFile(context).writeText(value.toString()) }
    }
}
