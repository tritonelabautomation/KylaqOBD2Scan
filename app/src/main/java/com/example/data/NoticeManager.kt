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

    fun cancelSummary(context: Context) {
        NotificationManagerCompat.from(context).cancel(SUMMARY_ID)
    }
}
