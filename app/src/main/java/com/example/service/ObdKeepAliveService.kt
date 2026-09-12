package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R

/**
 * Background keep-alive for the OBD session (added 2026-09-09, owner request:
 * "app in background opening as fresh, I don't want that").
 *
 * WHY: "Unrestricted" battery only lifts doze/standby limits. Android may still
 * kill a background PROCESS under memory pressure, which cold-starts the app and
 * drops the single RFCOMM socket. A foreground service with a visible notification
 * gives the process top-priority protection; START_STICKY plus the existing
 * auto-connect loop (default ON) recover everything even in the rare kill case.
 *
 * Purely additive: polling/recording logic stays exactly where it was; this
 * service only keeps the process and CPU alive and tells the owner it is live.
 */
class ObdKeepAliveService : Service() {

    companion object {
        const val CHANNEL_ID = "obd_keep_alive"
        const val NOTIFICATION_ID = 9001
        const val EXTRA_RECORDING = "recording"
        private const val WAKELOCK_TAG = "KylaqOBD2Scan:obd-keep-alive"
        private const val WAKELOCK_TIMEOUT_MS = 60 * 60 * 1000L

        fun notificationTitle(recording: Boolean): String =
            if (recording) "Recording trip - OBD live" else "OBD connected - logging ready"

        fun notificationText(recording: Boolean): String =
            if (recording) {
                "Kylaq TSI Coach keeps polling and saving in the background."
            } else {
                "Kylaq TSI Coach keeps the adapter socket and polling alive in the background."
            }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var refreshJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val recording = intent?.getBooleanExtra(EXTRA_RECORDING, false) == true
        startForegroundCompat(recording)
        refreshWakeLock()
        // QA M1: re-acquire before the 60 min timeout so multi-hour drives never lose CPU.
        if (refreshJob == null) {
            refreshJob = refreshScope.launch {
                while (isActive) {
                    kotlinx.coroutines.delay(45 * 60 * 1000L)
                    refreshWakeLock()
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundCompat(recording: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "OBD keep-alive", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(notificationTitle(recording))
            .setContentText(notificationText(recording))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pending)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** Partial wake lock so polling coroutines keep running with the screen off. */
    private fun refreshWakeLock() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
        }
    }

    override fun onDestroy() {
        refreshJob?.cancel()
        refreshScope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }
}
