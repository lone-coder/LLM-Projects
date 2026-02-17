package com.yourcompany.anxietymonitor

import android.app.Application
import android.util.Log
import com.yourcompany.anxietymonitor.domain.engine.AnxietyDetectionEngine
import com.yourcompany.anxietymonitor.domain.engine.BaselineEngine
import com.yourcompany.anxietymonitor.ai.ModelDownloadWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class AnxietyMonitorApplication : Application() {

    @Inject
    lateinit var anxietyDetectionEngine: AnxietyDetectionEngine

    @Inject
    lateinit var baselineEngine: BaselineEngine

    // Application-level coroutine scope
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val TAG = "AnxietyMonitorApp"
    }

    override fun onCreate() {
        super.onCreate()

        Log.d(TAG, "AnxietyMonitor Application starting...")

        // Initialize ML model and engines in background
        applicationScope.launch {
            try {
                Log.d(TAG, "Initializing AnxietyDetectionEngine...")
                val detectionSuccess = anxietyDetectionEngine.initialize()
                Log.d(TAG, "AnxietyDetectionEngine initialized: $detectionSuccess")

                Log.d(TAG, "Initializing BaselineEngine...")
                val baselineSuccess = baselineEngine.initialize()
                Log.d(TAG, "BaselineEngine initialized: $baselineSuccess")

                // Start model download in background
                Log.d(TAG, "Starting model download...")
                ModelDownloadWorker.enqueue(this@AnxietyMonitorApplication)

                // Log detection stats
                val stats = anxietyDetectionEngine.getDetectionStats()
                Log.d(TAG, "Detection stats: $stats")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize engines", e)
            }
        }

        Log.d(TAG, "AnxietyMonitor Application created successfully")
    }
}