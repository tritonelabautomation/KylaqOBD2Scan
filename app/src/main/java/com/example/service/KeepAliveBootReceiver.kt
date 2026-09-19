package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File

/**
 * Revives the OBD keep-alive service after the two events that silently kill it (KILL-AUDIT
 * FIX D, owner 2026-09-19): a device reboot and an in-app update install. Both take the process
 * down with a possibly half-written journal on disk; before this receiver, nothing recovered it
 * or restarted the auto-connect/auto-record supervisors until the owner opened the app by hand.
 *
 * The service's own `onCreate` does the rest: it recovers every unfinished session and starts
 * the supervisors, so a phone that rebooted mid-drive rebuilds the first half of the trip and
 * reconnects for the second half - no touch required.
 *
 * Every step is wrapped: firmware varies wildly, and a rescue path that can crash-loop is worse
 * than no rescue path at all.
 */
class KeepAliveBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (!KeepAlivePolicy.isStartBroadcast(intent?.action)) return
        val app = context.applicationContext ?: return
        val shouldStart = runCatching {
            com.example.di.AppContainer.init(app)
            val journalDir = File(File(app.filesDir, "recordings"), "journal")
            val pending = KeepAlivePolicy.hasPendingJournal(
                fileNames = journalDir.listFiles()?.map { it.name } ?: emptyList(),
                sizeOfBytes = { name -> File(journalDir, name).length() },
                isFinished = { id ->
                    File(journalDir, id + com.example.data.SessionJournal.FINISHED_SUFFIX).exists()
                }
            )
            KeepAlivePolicy.shouldStartKeepAlive(
                pendingJournal = pending,
                autoConnectEnabled = com.example.di.AppContainer.settingsRepository.autoConnect.value
            )
        }.getOrDefault(false)
        if (!shouldStart) return
        runCatching {
            val svc = Intent(app, ObdKeepAliveService::class.java)
                .putExtra(ObdKeepAliveService.EXTRA_RECORDING, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
        }.onFailure {
            android.util.Log.e("KeepAliveBootReceiver", "keep-alive restart failed", it)
        }
    }
}
