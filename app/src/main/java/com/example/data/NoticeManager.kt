package com.example.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Reminder notifications (VehIQ Reminders Hub): one channel, one summary notification carrying
 * every due item (overdue services, expiring documents, due custom reminders) plus a test
 * notification so the owner can verify the pipeline like VehIQ's "Send Test Notification".
 */
object NoticeManager {

    private const val CHANNEL_ID = "kylaq_alerts"
    private const val SUMMARY_ID = 9001
    private const val TEST_ID = 9002
    private const val CHECKIN_ID = 9003
    private const val ARRIVAL_ID = 9004

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Services, documents & reminders",
                        NotificationManager.IMPORTANCE_DEFAULT
                    )
                )
            }
        }
    }

    fun postSummary(context: Context, lines: List<String>) {
        if (lines.isEmpty()) return
        ensureChannel(context)
        val title = "${lines.size} item(s) need attention"
        val text = lines.take(6).joinToString("\n")
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(lines.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(SUMMARY_ID, notification)
        } catch (e: SecurityException) {
            // Notification permission denied on 13+: the hub still shows everything in-app.
        }
    }

    fun postTest(context: Context) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Kylaq notifications work")
            .setContentText("Test notification from the Reminders hub.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(TEST_ID, notification)
        } catch (e: SecurityException) {
            // Ignored: in-app hub remains the source of truth.
        }
    }

    /** VehIQ-style weekly check-in: nudges when nothing was logged for a week. */
    fun postCheckIn(context: Context, inactiveDays: Int) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle("Weekly check-in: $inactiveDays days since your last log")
            .setContentText("Open Kylaq: log a fill-up, check tyre pressure or review due reminders.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(CHECKIN_ID, notification)
        } catch (e: SecurityException) {
            // Permission denied: the in-app hub still lists everything.
        }
    }

    /** GPS co-pilot arrival detection (VehIQ trip co-pilot). */
    fun postArrival(context: Context, tripName: String) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("Arrived: $tripName")
            .setContentText("Co-pilot tracked the full planned distance. Log your trip fuel and expenses!")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ARRIVAL_ID, notification)
        } catch (e: SecurityException) {
            // Permission denied: the Trip Planner screen shows the arrival state anyway.
        }
    }

    fun cancelSummary(context: Context) {
        NotificationManagerCompat.from(context).cancel(SUMMARY_ID)
    }
}
