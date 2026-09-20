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
 *
 * ## What it does since 1.0.337 (owner 2026-09-17: "why the hell ... app is killed in
 * ## background it supposed to be service right ... it never ever loose the logs")
 *
 * Being a foreground service lowers the chance of a kill. It does not make the process
 * unkillable, and no app can promise that - Motorola's battery management in particular
 * kills foreground services. So this service now does three things beyond staying alive:
 *
 *  1. **Recovers on start.** When Android restarts it after a kill (START_STICKY, or a reboot),
 *     the process is fresh and holds nothing - so it immediately rebuilds every session the
 *     previous process died during, from the crash journal and the raw logs. No screen to open,
 *     no banner to tap.
 *  2. **Survives a swipe-away.** `onTaskRemoved` used to be inherited (do nothing). It now
 *     restarts the service, so removing the app from Recents does not end the OBD session.
 *  3. **Says when it is exposed.** If the battery-optimisation exemption has not been granted,
 *     the notification states that plainly and carries an action that opens the exemption
 *     request. Pretending the service is bulletproof is what let a whole day of logging go
 *     missing once already.
 *  4. **Runs the auto-connect and auto-record supervisors itself.** They used to live only in
 *     `MainViewModel.startSessionAutomation()`, whose own comment admitted *"both loops live in
 *     viewModelScope, so they stop with the app UI"*. That is the second half of the owner's
 *     complaint: after a kill, or with the phone in his pocket and the UI never opened, this
 *     service was keeping a process alive that was **doing nothing** - no reconnect, no polling, no
 *     recording. The loops now run here for as long as the process lives, so a drive resumes
 *     without anyone touching the phone.
 */
class ObdKeepAliveService : Service() {

    companion object {
        const val CHANNEL_ID = "obd_keep_alive"
        const val NOTIFICATION_ID = 9001
        const val RECOVERY_NOTIFICATION_ID = 9002
        const val RECOVERY_CHANNEL_ID = "obd_recovery"

        /** Live threshold alerts land here at HIGH importance: they mean stop-and-look. */
        const val ALERT_CHANNEL_ID = "vehicle_alerts"

        /** Tick periods, matching what `MainViewModel.startSessionAutomation` has always used. */
        const val AUTO_CONNECT_TICK_MS = 10_000L
        const val AUTO_RECORD_TICK_MS = 2_000L
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

        /**
         * The honest warning shown while the battery exemption is missing.
         *
         * Pure so the wording is testable, and so a test can pin the rule that the service never
         * claims more protection than Android actually gives: exempted -> no warning, not
         * exempted -> say so and offer the fix.
         */
        fun warningText(exempted: Boolean): String? =
            if (exempted) null
            else "Battery optimisation is still ON for this app - Android may kill the session. " +
                "Tap \"Make Unrestricted\" so the recording survives."
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var refreshJob: Job? = null

    /** Remembered so [onTaskRemoved] can restart the service in the same state. */
    @Volatile private var lastRecordingState: Boolean = false

    private var autoConnectJob: Job? = null
    private var autoRecordJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // A kill is exactly when this matters: Android restarts a START_STICKY service in a FRESH
        // process that holds no session in RAM. Recover the drive that process died during, now -
        // then start supervising again, so the rest of the drive is still recorded.
        recoverKilledSessions()
        startSupervisors()
        // Live threshold alerts (owner 2026-09-19): the service outlives the UI, so an over-temp
        // or a dying alternator reaches him with the screen off and the phone pocketed.
        refreshScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(5_000)
                checkVehicleAlerts()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val recording = intent?.getBooleanExtra(EXTRA_RECORDING, false) == true
        lastRecordingState = recording
        startForegroundCompat(recording)
        refreshWakeLock()
        recoverKilledSessions()
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
        // Say plainly when the session is exposed to an OEM kill, and give the owner the one tap
        // that fixes it. A service that quietly claims invulnerability is how a day of logging went
        // missing.
        val exempted = runCatching {
            getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) ?: true
        }.getOrDefault(true)
        val warning = warningText(exempted)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_kylaq)
            .setContentTitle(notificationTitle(recording))
            .setContentText(notificationText(recording))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pending)
        if (warning != null) {
            builder.setStyle(
                NotificationCompat.BigTextStyle().bigText(notificationText(recording) + "\n" + warning)
            ).addAction(0, "Make Unrestricted", exemptionPendingIntent())
        }
        val notification: Notification = builder.build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Owner field report 2026-09-18 ("Altitude"): the location type is what keeps GPS alive
            // on a pocketed-phone drive. Android counts location access made while a location-type
            // foreground service runs as WHILE-IN-USE access, so the grant the owner already gave
            // ("Allow only while using the app") covers the whole recording even with the screen
            // off. With connectedDevice alone the service is "background" for location purposes and
            // Android 10+ hands it zero fixes once the app leaves the screen - the 93-minute drive
            // came back with a blank altitude column for exactly that reason, while a 35-minute
            // drive with the app on screen recorded a full trace. The manifest declares both types
            // and FOREGROUND_SERVICE_LOCATION, which Android 14+ requires to start this at all.
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Auto-connect and auto-record, owned by the service instead of by the UI.
     *
     * Idempotent, and deliberately tolerant of `MainViewModel` running the same two loops while the
     * phone UI is open: every action here is guarded by the state it would change (is polling
     * already up? is a session already open?), and `RecordingManager.startRecording` refuses to open
     * a second session, so two supervisors cannot produce two trips.
     *
     * Both loops read the owner's settings on every tick, so switching auto-connect or auto-record
     * off takes effect within seconds without a restart.
     */
    /** Per-metric cooldown so a breached limit notifies once per two minutes, not once per tick. */
    private val alertCooldownMs = mutableMapOf<String, Long>()

    private fun checkVehicleAlerts() {
        runCatching {
            com.example.di.AppContainer.init(applicationContext)
            val settings = com.example.di.AppContainer.settingsRepository
            if (!settings.alertsEnabled()) return
            val scheduler = com.example.di.AppContainer.obdScheduler
            if (!scheduler.isPolling.value) return
            val alerts = com.example.analysis.AlertRules.evaluate(
                scheduler.liveNumericMap.value, settings.alertThresholds()
            )
            if (alerts.isEmpty()) return
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(ALERT_CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        ALERT_CHANNEL_ID, "Vehicle alerts", NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
            val now = System.currentTimeMillis()
            for (a in alerts) {
                val last = alertCooldownMs[a.metric] ?: 0L
                if (now - last < 120_000L) continue
                alertCooldownMs[a.metric] = now
                nm.notify(
                    7000 + kotlin.math.abs(a.metric.hashCode()) % 64,
                    NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_stat_kylaq)
                        .setContentTitle("Vehicle alert: " + a.metric)
                        .setContentText(a.message)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(a.message))
                        .setAutoCancel(true)
                        .build()
                )
            }
        }.onFailure { android.util.Log.e("ObdKeepAliveService", "alert check failed", it) }
    }

    private fun startSupervisors() {
        if (autoConnectJob == null) {
            autoConnectJob = recoveryScope.launch {
                while (isActive) {
                    runCatching {
                        com.example.di.AppContainer.init(applicationContext)
                        val settings = com.example.di.AppContainer.settingsRepository
                        val scheduler = com.example.di.AppContainer.obdScheduler
                        if (settings.autoConnect.value && !scheduler.isPolling.value) {
                            com.example.scheduler.ObdQuickConnect.connectPairedAdapterAndPoll(
                                this,
                                respectAutoConnectSetting = true
                            ) { /* progress messages belong to the UI supervisor */ }
                        }
                    }.onFailure {
                        android.util.Log.e("ObdKeepAliveService", "auto-connect tick failed", it)
                    }
                    kotlinx.coroutines.delay(AUTO_CONNECT_TICK_MS)
                }
            }
        }
        if (autoRecordJob == null) {
            autoRecordJob = recoveryScope.launch {
                var engineOffSinceMs = 0L
                while (isActive) {
                    runCatching {
                        com.example.di.AppContainer.init(applicationContext)
                        val container = com.example.di.AppContainer
                        val rpm = container.obdScheduler.liveNumericMap.value["010C"]
                        val recording = container.recordingManager.isRecording.value
                        val now = android.os.SystemClock.elapsedRealtime()
                        val decision = AutoRecordPolicy.decide(
                            rpm = rpm,
                            isRecording = recording,
                            isPolling = container.obdScheduler.isPolling.value,
                            autoRecordEnabled = container.settingsRepository.autoRecord.value,
                            engineOffSinceMs = engineOffSinceMs,
                            nowMs = now
                        )
                        when (decision) {
                            AutoRecordPolicy.Decision.START_RECORDING -> {
                                // The ride X-ray belongs to the drive about to start. When the UI
                                // opens a session it resets this itself; a drive opened here, in the
                                // background, would otherwise inherit the previous drive's X-ray and
                                // report it as part of this one.
                                container.obdScheduler.rideRecorder.reset()
                                container.recordingManager.startRecording()
                                container.gpsManager.startTracking()
                                lastRecordingState = true
                                startForegroundCompat(true)
                            }
                            AutoRecordPolicy.Decision.STOP_RECORDING -> {
                                container.recordingManager.stopRecording()
                                container.gpsManager.stopTracking()
                                lastRecordingState = false
                                startForegroundCompat(false)
                            }
                            AutoRecordPolicy.Decision.NONE -> Unit
                        }
                        // BELT (owner 2026-09-20: "Altitude still not logging in"): a process
                        // that restarts mid-drive resumes the unfinished journal WITHOUT passing
                        // the START_RECORDING edge above - the only place this loop used to start
                        // GPS - so altitude and the GPS trace would stay blank for the rest of
                        // the drive. Every tick: a recording without tracking gets corrected.
                        if (container.recordingManager.isRecording.value &&
                            !container.gpsManager.isTrackingNow
                        ) {
                            container.gpsManager.startTracking()
                        }
                        engineOffSinceMs = AutoRecordPolicy.nextEngineOffSince(
                            rpm, recording, engineOffSinceMs, now
                        )
                    }.onFailure {
                        android.util.Log.e("ObdKeepAliveService", "auto-record tick failed", it)
                    }
                    kotlinx.coroutines.delay(AUTO_RECORD_TICK_MS)
                }
            }
        }
    }

    /**
     * Rebuilds every session a previous process died during.
     *
     * Runs off the main thread, is guarded against re-entry inside RecordingManager, and skips the
     * session this process is recording right now. Silently does nothing when the app container is
     * not up yet - a service restart must never crash the process it is trying to protect.
     */
    private fun recoverKilledSessions() {
        recoveryScope.launch {
            runCatching {
                com.example.di.AppContainer.init(applicationContext)
                val manager = com.example.di.AppContainer.recordingManager
                val summary = manager.recoverUnfinishedSessions()
                if (summary != null && summary.recoveredSessions > 0) {
                    showRecoveryNotification(summary.notice())
                }
            }.onFailure {
                android.util.Log.e("ObdKeepAliveService", "session recovery failed", it)
            }
        }
    }

    /**
     * Tells the owner what was rescued. Recovery must not be silent: a trip that reappears in the
     * list with no explanation looks like a bug, and a trip that stays missing looks like the app
     * lost it - which is the complaint this fixes.
     */
    private fun showRecoveryNotification(text: String) {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(RECOVERY_CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(RECOVERY_CHANNEL_ID, "Recovered trips", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val pending = PendingIntent.getActivity(
                this, 1, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n: Notification = NotificationCompat.Builder(this, RECOVERY_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_kylaq)
                .setContentTitle("Killed session recovered - logs saved")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            nm.notify(RECOVERY_NOTIFICATION_ID, n)
        }
    }

    /**
     * Removing the app from Recents must not end the OBD session.
     *
     * The inherited implementation does nothing, so a swipe-away left the service running only if
     * the OS felt like it - on Motorola it usually did not, and the drive stopped being recorded.
     * Restarting here keeps the foreground service, the socket, the polling and the journal alive
     * after the task is gone.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        runCatching {
            val restart = Intent(applicationContext, ObdKeepAliveService::class.java)
                .putExtra(EXTRA_RECORDING, lastRecordingState)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restart)
            } else {
                startService(restart)
            }
        }.onFailure { android.util.Log.e("ObdKeepAliveService", "restart after task removal failed", it) }
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Opens the standard exemption dialog, or the app details page on OEM firmware that does not
     * implement the standard action. Never throws - firmware varies wildly, and a crash here would
     * kill the very session it is trying to protect.
     */
    private fun exemptionPendingIntent(): PendingIntent {
        val standard = Intent(
            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            android.net.Uri.parse("package:$packageName")
        )
        val fallback = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:$packageName")
        }
        val intent = if (runCatching { packageManager.resolveActivity(standard, 0) }.getOrNull() != null) {
            standard
        } else {
            fallback
        }
        return PendingIntent.getActivity(
            this, 2, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
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
        autoConnectJob?.cancel()
        autoRecordJob?.cancel()
        refreshScope.cancel()
        recoveryScope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }
}
