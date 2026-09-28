package com.example

import android.app.Application
import com.example.crash.CrashReporter
import com.example.data.CrashJournal

/**
 * Process entry point, registered via `android:name` in the manifest so its onCreate
 * runs before ANY activity, service or receiver - including the Android Auto host
 * binding [com.example.auto.ObdCarAppService] and [com.example.service.KeepAliveBootReceiver].
 *
 * Jobs:
 * 1. Install crash journal FIRST, so a crash can never again be silent (owner 2026-09-20: "Again app is crashing when I open it crashes. No app crash logs why it has crashed").
 * 2. Auto-upload pending crashes to GitHub Issues (owner 2026-09-23: "push error log automatically to GitHub some location which you have access so you can work anonymous ... without my intervention")
 *    — CrashReporter.onAppStarted() does startupCompleted + IO upload of crash_logs/ not yet marked uploaded.
 *    Works even without token (local + Drive backup), with token creates `crash-auto` labeled issue that
 *    Arena agent can list via `gh issue list --label crash-auto` and fix without chat upload.
 */
class KylaqApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashJournal.install(this)
        CrashReporter.onAppStarted(this)
    }
}
