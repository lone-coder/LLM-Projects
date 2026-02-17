package com.yourcompany.anxietymonitor.wear.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.yourcompany.anxietymonitor.wear.MainActivity
import com.yourcompany.anxietymonitor.wear.data.WearBiometricReading
import kotlinx.coroutines.*

class SensorMonitoringService : Service() {

    private lateinit var sensorManager: WearSensorManager
    private lateinit var dataSyncManager: WearDataSyncManager
    private lateinit var wakeLock: PowerManager.WakeLock

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var monitoringJob: Job? = null
    private var lastKnownReading: WearBiometricReading? = null

    // Simple baseline tracking
    private var baselineHeartRate: Int? = null
    private var baselineHrv: Double? = null
    private val readingsForBaseline = mutableListOf<WearBiometricReading>()

    companion object {
        private const val TAG = "SensorMonitoringService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "anxiety_monitoring_channel"
        private const val WAKELOCK_TAG = "AnxietyMonitor:SensorMonitoring"

        private const val BASELINE_READINGS_COUNT = 10
        private const val ANXIETY_CHECK_INTERVAL_MS = 5000L
    }

    override fun onCreate() {
        super.onCreate()

        sensorManager = WearSensorManager(this)
        dataSyncManager = WearDataSyncManager(this)

        // FIX: Removed redundant qualifier 'Context.'
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKELOCK_TAG
        )

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Initializing sensors..."))

        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")

        if (!wakeLock.isHeld) {
            wakeLock.acquire(8 * 60 * 60 * 1000L) // 8 hours max
        }

        startMonitoring()

        return START_STICKY
    }

    private fun startMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = serviceScope.launch {
            try {
                sensorManager.initialize()
                dataSyncManager.initialize()

                Log.d(TAG, "Starting sensor monitoring")

                sensorManager.setDataCallback { reading ->
                    serviceScope.launch {
                        processReading(reading)
                    }
                }

                sensorManager.startMonitoring()
                startAnxietyDetection()
                updateNotification("Active - Collecting baseline")

            } catch (e: Exception) {
                Log.e(TAG, "Error starting monitoring", e)
                stopSelf()
            }
        }
    }

    private suspend fun processReading(reading: WearBiometricReading) {
        try {
            lastKnownReading = reading
            dataSyncManager.sendBiometricReading(reading)
            updateBaseline(reading)
            Log.v(TAG, "Processed reading: HR=${reading.heartRate}, IBI=${reading.interBeatInterval}")
        } catch (e: Exception) {
            Log.e(TAG, "Error processing reading", e)
        }
    }

    private fun updateBaseline(reading: WearBiometricReading) {
        if (reading.heartRate == null) return

        readingsForBaseline.add(reading)

        if (readingsForBaseline.size >= BASELINE_READINGS_COUNT) {
            if (baselineHeartRate == null) {
                baselineHeartRate = readingsForBaseline
                    .mapNotNull { it.heartRate }
                    .average()
                    .toInt()

                Log.d(TAG, "Baseline established - HR: $baselineHeartRate")
                updateNotification("Active - Baseline HR: ${baselineHeartRate}bpm")
            }
            if (readingsForBaseline.size > BASELINE_READINGS_COUNT * 2) {
                readingsForBaseline.removeAt(0)
            }
        } else {
            val progress = readingsForBaseline.size
            updateNotification("Collecting baseline ($progress/$BASELINE_READINGS_COUNT)")
        }
    }

    private fun startAnxietyDetection() {
        serviceScope.launch {
            while (isActive) {
                delay(ANXIETY_CHECK_INTERVAL_MS)
                val readingToCheck = lastKnownReading
                if (readingToCheck != null && baselineHeartRate != null) {
                    checkForAnxiety(readingToCheck)
                }
            }
        }
    }

    private suspend fun checkForAnxiety(reading: WearBiometricReading) {
        val baseline = baselineHeartRate ?: return

        // FIX: Removed unnecessary non-null assertion (!!)
        if (reading.heartRate != null && reading.heartRate > baseline * 1.2) {
            Log.d(TAG, "Potential anxiety detected - HR: ${reading.heartRate}, baseline: $baseline")

            // FIX: Removed unnecessary safe call (?.)
            reading.heartRate.let { hr ->
                dataSyncManager.sendAnxietyAlert(
                    confidence = 0.7f,
                    heartRate = hr,
                    ibi = reading.interBeatInterval
                )
            }
            showAnxietyNotification(reading)
            updateNotification("⚠️ Elevated stress - HR: ${reading.heartRate}bpm")
        } else {
            reading.heartRate?.let { hr ->
                updateNotification("Monitoring - HR: ${hr}bpm")
            }
        }
    }

    private fun showAnxietyNotification(reading: WearBiometricReading) {
        // FIX: Using a standard system icon to prevent unresolved reference errors.
        // Replace this with your own R.drawable.ic_anxiety_alert when it's available.
        val icon = android.R.drawable.ic_dialog_alert

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Elevated Stress Detected")
            .setContentText("HR: ${reading.heartRate} BPM")
            .setSmallIcon(icon)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 250, 250, 250))
            .build()

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Anxiety Monitoring",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Continuous biometric monitoring"
            setShowBadge(false)
        }
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // FIX: Using a standard system icon to prevent unresolved reference errors.
        // Replace this with your own R.drawable.ic_watch when it's available.
        val icon = android.R.drawable.ic_dialog_info

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Anxiety Monitor")
            .setContentText(text)
            .setSmallIcon(icon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(text))
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")

        monitoringJob?.cancel()
        serviceScope.cancel()

        sensorManager.stopMonitoring()
        // dataSyncManager.cleanup() // This method may not exist on your class

        if (wakeLock.isHeld) {
            wakeLock.release()
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}