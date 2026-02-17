package com.yourcompany.anxietymonitor.data.health

import android.content.Context
import android.util.Log
import com.yourcompany.anxietymonitor.domain.interfaces.BiometricDataSource
import com.yourcompany.anxietymonitor.domain.models.*
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Samsung health Tracking SDK implementation for real-time biometric data
 * Adapted to use the com.samsung.android.service.health.tracking package.
 */
@Singleton
class AndroidSamsungSensorDataSource @Inject constructor(
    @ApplicationContext private val context: Context
) : BiometricDataSource {

    private var healthTrackingService: HealthTrackingService? = null
    private var heartRateTracker: HealthTracker? = null
    private var skinTempTracker: HealthTracker? = null
    private var accelerometerTracker: HealthTracker? = null

    private var isInitialized = false
    private var isStreaming = false

    // A coroutine scope for this class to launch background jobs
    private val classScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _biometricFlow = MutableSharedFlow<BiometricReading>(
        replay = 0,
        extraBufferCapacity = 10
    )

    // Current sensor values
    @Volatile private var currentHeartRate: Int? = null
    @Volatile private var currentInterBeatInterval: Double? = null
    @Volatile private var currentSkinTemp: Float? = null
    @Volatile private var currentAccelData: Triple<Float, Float, Float>? = null
    private var lastUpdateTime = 0L

    companion object {
        private const val TAG = "SamsungSensorDataSource"
        private const val MIN_UPDATE_INTERVAL_MS = 1000L
    }

    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Initializing Samsung health Tracking SDK...")

            val connectionDeferred = CompletableDeferred<Boolean>()

            val connectionListener = object : ConnectionListener {
                override fun onConnectionSuccess() {
                    Log.d(TAG, "Samsung health Tracking SDK connected successfully")
                    isInitialized = true
                    connectionDeferred.complete(true)
                }

                override fun onConnectionEnded() {
                    Log.w(TAG, "Samsung health Tracking SDK connection ended")
                    isInitialized = false
                    cleanup()
                }

                override fun onConnectionFailed(e: HealthTrackerException) {
                    Log.e(TAG, "Samsung health Tracking SDK connection failed", e)
                    isInitialized = false
                    connectionDeferred.complete(false)
                }
            }

            healthTrackingService = HealthTrackingService(connectionListener, context)
            healthTrackingService?.connectService()

            // FIX: Using equality check instead of elvis operator for nullable boolean
            (withTimeoutOrNull(5000L) { connectionDeferred.await() } == true)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Samsung health Tracking SDK", e)
            false
        }
    }

    private fun initializeTrackers() {
        val capabilities = healthTrackingService?.trackingCapability ?: return
        if (capabilities.supportHealthTrackerTypes.contains(HealthTrackerType.HEART_RATE)) {
            heartRateTracker = healthTrackingService?.getHealthTracker(HealthTrackerType.HEART_RATE)
        }
        if (capabilities.supportHealthTrackerTypes.contains(HealthTrackerType.SKIN_TEMPERATURE)) {
            skinTempTracker = healthTrackingService?.getHealthTracker(HealthTrackerType.SKIN_TEMPERATURE)
        }
        if (capabilities.supportHealthTrackerTypes.contains(HealthTrackerType.ACCELEROMETER)) {
            accelerometerTracker = healthTrackingService?.getHealthTracker(HealthTrackerType.ACCELEROMETER)
        }
        Log.d(TAG, "Trackers initialized.")
    }

    override suspend fun startStreaming(): Boolean {
        if (!isInitialized) {
            Log.w(TAG, "Cannot start streaming - not initialized")
            return false
        }
        if (isStreaming) {
            Log.d(TAG, "Already streaming")
            return true
        }

        try {
            initializeTrackers()
            heartRateTracker?.setEventListener(dataListener)
            skinTempTracker?.setEventListener(dataListener)
            accelerometerTracker?.setEventListener(dataListener)
            isStreaming = true
            Log.d(TAG, "Started streaming from Samsung sensors")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start streaming", e)
            return false
        }
    }

    private val dataListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            for (dataPoint in dataPoints) {
                try {
                    // --- Heart Rate and IBI ---
                    dataPoint.getValue(ValueKey.HeartRateSet.HEART_RATE)?.let { currentHeartRate = it }
                    // FIX: No cast needed, and fixed variable name typo
                    dataPoint.getValue(ValueKey.HeartRateSet.IBI_LIST)?.lastOrNull()?.let { currentInterBeatInterval = it.toDouble() }

                    // --- Skin Temperature ---
                    dataPoint.getValue(ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE)?.let { currentSkinTemp = it }

                    // --- Accelerometer ---
                    val x = dataPoint.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_X)
                    val y = dataPoint.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Y)
                    val z = dataPoint.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Z)
                    if (x != null && y != null && z != null) {
                        currentAccelData = Triple(x.toFloat(), y.toFloat(), z.toFloat())
                    }
                } catch (_: Exception) {}
            }
            emitReading()
        }
        override fun onFlushCompleted() {}
        override fun onError(error: HealthTracker.TrackerError) {
            Log.e(TAG, "Tracker error: $error")
        }
    }

    private fun emitReading() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastUpdateTime < MIN_UPDATE_INTERVAL_MS) return

        val hrvFromIbi = currentInterBeatInterval?.let { calculateHrvFromIbi(it) }
        val accelMagnitude = currentAccelData?.let { (x, y, z) -> sqrt(x * x + y * y + z * z) }

        val reading = BiometricReading(
            timestamp = currentTime,
            heartRate = currentHeartRate,
            hrvRmssd = hrvFromIbi,
            skinTemperature = currentSkinTemp,
            accelerometerMagnitude = accelMagnitude,
            confidence = calculateConfidence(),
            source = BiometricSource.GALAXY_WATCH
        )

        _biometricFlow.tryEmit(reading)
        lastUpdateTime = currentTime

        Log.v(TAG, "Emitted reading - HR: ${reading.heartRate}")
    }

    private fun calculateHrvFromIbi(ibiMs: Double): Double {
        return ibiMs * 0.8 // Simplified conversion
    }

    private fun calculateConfidence(): Float {
        var confidence = 0.0f
        if (currentHeartRate != null) confidence += 0.4f
        if (currentInterBeatInterval != null) confidence += 0.3f
        if (currentSkinTemp != null) confidence += 0.2f
        if (currentAccelData != null) confidence += 0.1f
        return confidence.coerceIn(0.0f, 1.0f)
    }

    override suspend fun stopStreaming() {
        if (!isStreaming) return
        try {
            heartRateTracker?.unsetEventListener()
            skinTempTracker?.unsetEventListener()
            accelerometerTracker?.unsetEventListener()
            isStreaming = false
            resetCurrentReadings()
            Log.d(TAG, "Stopped streaming from Samsung sensors")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping streaming", e)
        }
    }

    override fun isConnected(): Boolean = isInitialized && isStreaming

    override fun getDeviceInfo(): DeviceInfo {
        return DeviceInfo(
            name = "Samsung Galaxy Watch (Tracking SDK)",
            type = BiometricSource.GALAXY_WATCH,
            capabilities = setOf(
                SensorCapability.HEART_RATE,
                SensorCapability.HRV,
                SensorCapability.SKIN_TEMPERATURE,
                SensorCapability.ACCELEROMETER,
                SensorCapability.REAL_TIME_STREAMING
            )
        )
    }

    override fun observeRealTimeData(): Flow<BiometricReading> = _biometricFlow.asSharedFlow()

    private fun cleanup() {
        // FIX: Calling suspend function from a coroutine
        classScope.launch {
            try {
                stopStreaming()
                healthTrackingService?.disconnectService()
                healthTrackingService = null
                classScope.cancel() // Cancel the scope after cleanup is done
            } catch (e: Exception) {
                Log.e(TAG, "Error during cleanup", e)
            }
        }
    }

    private fun resetCurrentReadings() {
        currentHeartRate = null
        currentInterBeatInterval = null
        currentSkinTemp = null
        currentAccelData = null
        lastUpdateTime = 0L
    }
}