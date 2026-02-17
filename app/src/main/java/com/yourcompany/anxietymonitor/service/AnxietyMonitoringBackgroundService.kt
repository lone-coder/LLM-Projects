package com.yourcompany.anxietymonitor.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.yourcompany.anxietymonitor.R
import com.yourcompany.anxietymonitor.ui.CbtPromptActivity
import com.yourcompany.anxietymonitor.ui.MainActivity
import com.yourcompany.anxietymonitor.domain.models.AnxietyEvent
import com.yourcompany.anxietymonitor.data.repository.DataRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
//import kotlinx.coroutines.flow.collect
import java.util.Calendar
import javax.inject.Inject

@AndroidEntryPoint
class AnxietyMonitoringBackgroundService : LifecycleService() {

    @Inject
    lateinit var anxietyService: AnxietyMonitoringService
    @Inject
    lateinit var repository: DataRepository

    private var monitoringJob: Job? = null

    // FIX: Renamed constants to camelCase to adhere to Kotlin's style guide for private properties.
    private val notificationId = 1
    private val channelId = "anxiety_monitoring"
    private val alertChannelId = "anxiety_alerts"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START_MONITORING -> startMonitoring()
            ACTION_STOP_MONITORING -> stopMonitoring()
            else -> {
                // Default action if intent or action is null,
                // which can happen if the service is restarted by the system.
                startMonitoring()
            }
        }

        return START_STICKY
    }

    private fun startMonitoring() {
        startForeground(notificationId, createNotification("Starting monitoring..."))

        monitoringJob?.cancel() // Cancel any existing job before starting a new one
        monitoringJob = lifecycleScope.launch {
            try {
                val started = anxietyService.startMonitoring()

                if (started) {
                    updateNotification("Monitoring active")

                    launch(Dispatchers.IO) { monitorAnxietyEvents() }

                    // FIX: Moved the periodic maintenance logic directly into this launch block.
                    // This resolves the 'unused import' warning by using the 'isActive' property
                    // from the CoroutineScope of this launch block.
                    launch(Dispatchers.IO) {
                        while (isActive) {
                            try {
                                val cutoffTime = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
                                repository.cleanupOldData(cutoffTime)

                                val todayStart = getTodayStart()
                                val eventCount = repository.getAnxietyEventCount(todayStart)
                                updateNotification("Active - $eventCount events today")

                            } catch (e: Exception) {
                                Log.e("BackgroundService", "Error during periodic maintenance", e)
                            }
                            delay(60 * 60 * 1000) // Run every hour
                        }
                    }

                } else {
                    updateNotification("Failed to start monitoring. Check permissions.")
                    stopSelf()
                }

            } catch (e: Exception) {
                Log.e("BackgroundService", "Error starting monitoring", e)
                updateNotification("Error: ${e.message}")
                stopSelf()
            }
        }
    }

    // FIX: Removed redundant @Suppress("DEPRECATION") as it's no longer needed.
    private suspend fun monitorAnxietyEvents() {
        try {
            anxietyService.observeAnxietyEvents().collect { event ->
                Log.d("BackgroundService", "Real-time anxiety event received: ${event.id}")
                showAnxietyAlert(event)
            }
        } catch (e: Exception) {
            Log.e("BackgroundService", "Error monitoring anxiety events", e)
        }
    }

    private fun showAnxietyAlert(event: AnxietyEvent) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val alertIntent = Intent(this, CbtPromptActivity::class.java).apply {
            putExtra("anxiety_event_id", event.id)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            this, event.id.hashCode(), alertIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alertNotification = NotificationCompat.Builder(this, alertChannelId)
            .setContentTitle("Anxiety Pattern Detected")
            .setContentText("Your biometric data suggests elevated anxiety levels.")
            .setSmallIcon(R.drawable.ic_anxiety_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(
                R.drawable.ic_psychology,
                "Check Thoughts",
                pendingIntent
            )
            .build()

        notificationManager.notify(event.id.hashCode(), alertNotification)
    }

    private fun stopMonitoring() {
        lifecycleScope.launch {
            anxietyService.stopMonitoring()
        }
        monitoringJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        // FIX: Removed unnecessary SDK version check, as minSdk is 26.
        val serviceChannel = NotificationChannel(
            channelId,
            "Anxiety Monitoring",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent notification for background monitoring"
        }

        val alertChannel = NotificationChannel(
            alertChannelId,
            "Anxiety Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications for detected anxiety patterns"
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(serviceChannel)
        notificationManager.createNotificationChannel(alertChannel)
    }

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Anxiety Monitor")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_watch_connected)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val updatedNotification = createNotification(text)
        notificationManager.notify(notificationId, updatedNotification)
    }

    private fun getTodayStart(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        monitoringJob?.cancel()
    }

    companion object {
        const val ACTION_START_MONITORING = "ACTION_START_MONITORING"
        const val ACTION_STOP_MONITORING = "ACTION_STOP_MONITORING"
    }
}
