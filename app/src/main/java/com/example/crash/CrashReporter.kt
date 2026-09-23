package com.example.crash

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.example.BuildConfig
import com.example.data.CrashJournal
import com.example.data.RecordTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Automatic crash → GitHub pipeline (owner request 2026-09-23):
 * "read crash logs everything automatically and fix it ... push error log automatically
 *  to GitHub some location which you have access so you can work anonymous ... without my intervention"
 *
 * Flow:
 * 1. CrashJournal already writes every uncaught exception to files/crash_logs/crash_<ms>.txt on crash.
 * 2. On next normal start, KylaqApplication calls CrashReporter.onAppStarted().
 * 3. onAppStarted() marks startupCompleted (clears safe-mode streak) and launches uploadPendingCrashes()
 *    on IO scope — no UI block.
 * 4. uploadPendingCrashes() iterates crash files not yet marked uploaded in prefs `crash_uploaded`.
 *    For each:
 *      - builds enriched payload (device, version, last session id, etc.)
 *      - tries Firebase Crashlytics (always, if available) — anonymous, no token needed
 *      - tries GitHub Issue auto-report via GithubCrashUploader if BuildConfig.CRASH_REPORT_TOKEN present
 *      - marks uploaded regardless of GitHub success to avoid spam, but keeps file (never-lose-logs)
 *      - crash_logs are already included in DriveBackupClient backup, so Drive copy is automatic
 *
 * Result: agent (Arena) can see crashes via `gh issue list --label crash-auto` without user manually
 * uploading in chat. The app works anonymous even without token — local file + Drive backup still exist.
 */
object CrashReporter {

    private const val PREFS = "crash_reporter"
    private const val KEY_UPLOADED = "uploaded_set"
    private const val KEY_LAST_UPLOAD_MS = "last_upload_ms"
    private const val TAG = "CrashReporter"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun onAppStarted(context: Context) {
        val appCtx = context.applicationContext
        // Existing safe-mode bookkeeping
        CrashJournal.startupCompleted(appCtx)

        // Enrich Firebase Crashlytics with context if available (best-effort)
        runCatching { initCrashlyticsContext(appCtx) }

        // Auto-upload pending crashes without blocking UI
        scope.launch {
            runCatching { uploadPendingCrashes(appCtx) }
                .onFailure { Log.e(TAG, "uploadPending failed", it) }
        }
    }

    private fun initCrashlyticsContext(context: Context) {
        // Firebase Crashlytics is optional — if google-services.json missing or not initialized, this no-ops
        try {
            val crashlytics = com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
            crashlytics.setCustomKey("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            crashlytics.setCustomKey("android_sdk", Build.VERSION.SDK_INT)
            crashlytics.setCustomKey("android_release", Build.VERSION.RELEASE ?: "unknown")
            crashlytics.setCustomKey("app_version", BuildConfig.VERSION_NAME)
            crashlytics.setCustomKey("git_commit", BuildConfig.GIT_COMMIT)
            crashlytics.setCustomKey("build_number", BuildConfig.GITHUB_RUN_NUMBER)
            // Last session id if any
            val lastSession = context.filesDir.listFiles()?.firstOrNull { it.name == "recordings" }
            crashlytics.setCustomKey("has_recordings_dir", lastSession != null)
        } catch (_: Exception) {
            // Firebase not configured — fine
        }
    }

    suspend fun uploadPendingCrashes(context: Context) {
        // Respect opt-out (SettingsRepository default true)
        val enabled = context.getSharedPreferences("obd_research_prefs", Context.MODE_PRIVATE)
            .getBoolean("crash_reporting_enabled", true)
        if (!enabled) return

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val uploaded = prefs.getStringSet(KEY_UPLOADED, emptySet())?.toMutableSet() ?: mutableSetOf()
        val files = CrashJournal.crashFiles(context)
        if (files.isEmpty()) return

        // Throttle: at most once per 5 minutes to avoid spamming GitHub on boot loop
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_UPLOAD_MS, 0L)
        if (now - last < 5 * 60 * 1000L && files.size == uploaded.size) {
            // All already uploaded and recently checked
            return
        }

        var anyUploaded = false
        for (file in files) {
            if (file.name in uploaded) continue
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            if (text.length < 20) continue

            // 1. Firebase Crashlytics log (anonymous, no token)
            runCatching {
                val c = com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
                c.log("Pending crash file: ${file.name}\n${text.take(8000)}")
                // Don't recordException here — this is a previous run's crash, not current
            }

            // 2. GitHub auto-issue if token present
            val githubOk = runCatching {
                GithubCrashUploader.uploadIfConfigured(context, file, text)
            }.getOrDefault(false)

            // 3. Mark as uploaded (even if GitHub skipped due to no token, we don't want to retry every launch)
            //    But if GitHub token present and upload failed due to network, keep it pending for next retry
            val shouldMark = if (BuildConfig.CRASH_REPORT_TOKEN.isBlank()) true else githubOk
            if (shouldMark) {
                uploaded.add(file.name)
                anyUploaded = true
            }
            // Small delay between uploads to avoid rate limit
            kotlinx.coroutines.delay(2000L)
        }

        if (anyUploaded) {
            prefs.edit()
                .putStringSet(KEY_UPLOADED, uploaded)
                .putLong(KEY_LAST_UPLOAD_MS, now)
                .apply()
        }
    }

    fun buildIssueTitle(crashText: String): String {
        // First line of stacktrace or summarized
        val firstLine = crashText.lineSequence()
            .firstOrNull { it.contains("Exception") || it.contains("Error") }
            ?.trim()?.take(120) ?: "Unknown crash"
        return "Auto Crash: $firstLine"
    }

    fun buildIssueBody(context: Context, file: File, crashText: String): String {
        val version = BuildConfig.VERSION_NAME
        val commit = BuildConfig.GIT_COMMIT
        val device = "${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE} SDK ${Build.VERSION.SDK_INT}"
        val ist = RecordTime.display(file.lastModified())
        // Truncate to GitHub issue body limit (65536) — keep 20k
        val truncated = crashText.take(20000)
        return """
            |**Auto-reported crash** — no user action (owner request 2026-09-23)
            |
            |**When (IST):** $ist
            |**File:** ${file.name}
            |**App:** $version (${BuildConfig.GITHUB_RUN_NUMBER}) commit $commit
            |**Device:** $device
            |**Repo:** ${BuildConfig.CRASH_REPORT_REPO}
            |
            |**Stacktrace:**
            |``` 
            |$truncated
            |```
            |
            |**Context:**
            |- Crash logs dir: ${file.parent}
            |- Pending upload: auto via CrashReporter.onAppStarted()
            |- Also in Drive backup (crash_logs/) and Firebase Crashlytics if configured
            |- Label: crash-auto
            |
            |**Agent action:** `gh issue view <id> --json body` → fix, push to `arena/...` branch.
            |Crash file also saved locally, never deleted (never-lose-logs).
        """.trimMargin()
    }

    fun pendingCount(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val uploaded = prefs.getStringSet(KEY_UPLOADED, emptySet()) ?: emptySet()
        return CrashJournal.crashFiles(context).count { it.name !in uploaded }
    }
}
