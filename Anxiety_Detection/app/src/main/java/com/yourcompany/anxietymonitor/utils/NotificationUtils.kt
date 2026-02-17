package com.yourcompany.anxietymonitor.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.yourcompany.anxietymonitor.R
import com.yourcompany.anxietymonitor.ui.CbtPromptActivity
import com.yourcompany.anxietymonitor.ui.MainActivity
import com.yourcompany.anxietymonitor.domain.models.AnxietyEvent

object NotificationUtils {

    fun createNotificationChannels(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Monitoring service channel
        val serviceChannel = NotificationChannel(
            Constants.NOTIFICATION_CHANNEL_MONITORING,
            "Anxiety Monitoring",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Ongoing monitoring notifications"
            setShowBadge(false)
        }

        // Alert channel
        val alertChannel = NotificationChannel(
            Constants.NOTIFICATION_CHANNEL_ALERTS,
            "Anxiety Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Anxiety detection alerts"
            enableVibration(true)
            setShowBadge(true)
        }

        notificationManager.createNotificationChannel(serviceChannel)
        notificationManager.createNotificationChannel(alertChannel)
    }

    fun createMonitoringNotification(context: Context, status: String): android.app.Notification {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, Constants.NOTIFICATION_CHANNEL_MONITORING)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_watch_connected)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    fun showAnxietyAlert(context: Context, event: AnxietyEvent) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val alertIntent = Intent(context, CbtPromptActivity::class.java).apply {
            putExtra("anxiety_event_id", event.id)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context, 0, alertIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, Constants.NOTIFICATION_CHANNEL_ALERTS)
            .setContentTitle(context.getString(R.string.anxiety_detected_title))
            .setContentText(context.getString(R.string.anxiety_detected_text))
            .setSmallIcon(R.drawable.ic_anxiety_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(
                R.drawable.ic_psychology,
                context.getString(R.string.check_thoughts_action),
                pendingIntent
            )
            .build()

        notificationManager.notify(event.id.hashCode(), notification)
    }
}