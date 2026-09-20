package com.example

import android.app.Application
import com.example.data.CrashJournal

/**
 * Process entry point, registered via `android:name` in the manifest so its onCreate
 * runs before ANY activity, service or receiver - including the Android Auto host
 * binding [com.example.auto.ObdCarAppService] and [com.example.service.KeepAliveBootReceiver].
 *
 * One job today: install the crash journal FIRST, so a crash can never again be silent
 * (owner 2026-09-20: "Again app is crashing when I open it crashes. No app crash logs
 * why it has crashed").
 */
class KylaqApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashJournal.install(this)
    }
}
