package com.yourcompany.anxietymonitor.wear.service

import android.content.Context
import android.util.Log
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import com.yourcompany.anxietymonitor.wear.data.WearBiometricReading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class WearSensorManager(context: Context) {

    private var healthTrackingService: HealthTrackingService? = null
    private val trackers = mutableListOf<HealthTracker>()

    private val readingBuffer = mutableListOf<WearBiometricReading>()
    private var dataCallback: ((WearBiometricReading) -> Unit)? = null

    // Current sensor values
    @Volatile private var currentHeartRate: Int? = null
    @Volatile private var currentIbi: Double? = null
    @Volatile private var currentSkinTemp: Float? = null
    @Volatile private var currentAccelData: Triple<Float, Float, Float>? = null

    // This warning is normal for now. It means the UI isn't observing this value yet.
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    companion object {
        private const val TAG = "WearSensorManager"
        private const val BUFFER_SIZE = 10
        private const val MIN_UPDATE_INTERVAL_MS = 1000L // 1 second
    }

    private val connectionListener = object : ConnectionListener {
        override fun onConnectionSuccess() {
            Log.d(TAG, "HealthTrackingService connection successful.")
            _isConnected.value = true
            startMonitoring()
        }

        override fun onConnectionEnded() {
            Log.d(TAG, "HealthTrackingService connection ended.")
            _isConnected.value = false
        }

        override fun onConnectionFailed(e: HealthTrackerException) {
            Log.e(TAG, "HealthTrackingService connection failed: $e")
            _isConnected.value = false
        }
    }

    init {
        healthTrackingService = HealthTrackingService(connectionListener, context)
    }

    private val dataListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            for (dataPoint in dataPoints) {
                try {
                    // --- Heart Rate and IBI ---
                    dataPoint.getValue(ValueKey.HeartRateSet.HEART_RATE)?.let {
                        currentHeartRate = it
                    }
                    // Cleaned up the cast for IBI_LIST
                    (dataPoint.getValue(ValueKey.HeartRateSet.IBI_LIST) as? List<Int>)?.lastOrNull()?.let {
                        currentIbi = it.toDouble()
                    }

                    // --- Skin Temperature ---
                    dataPoint.getValue(ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE)?.let {
                        currentSkinTemp = it
                    }

                    // --- Accelerometer ---
                    val xInt = dataPoint.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_X)
                    val yInt = dataPoint.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Y)
                    val zInt = dataPoint.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Z)

                    if (xInt != null && yInt != null && zInt != null) {
                        currentAccelData = Triple(xInt.toFloat(), yInt.toFloat(), zInt.toFloat())
                    }
                } catch (_: Exception) {
                    // This will catch any unexpected errors during data parsing.
                }
            }
            emitReadingIfReady()
        }

        override fun onFlushCompleted() {
            Log.i(TAG, "Flush completed.")
        }

        override fun onError(error: HealthTracker.TrackerError) {
            Log.e(TAG, "Tracker error received: $error")
        }
    }


    private var lastEmitTime = 0L

    fun setDataCallback(callback: (WearBiometricReading) -> Unit) {
        dataCallback = callback
    }

    fun initialize() {
        if (_isConnected.value) {
            Log.d(TAG, "Already initialized.")
            return
        }
        Log.d(TAG, "Connecting to HealthTrackingService...")
        healthTrackingService?.connectService()
    }

    private fun setupTrackers() {
        val capabilities = healthTrackingService?.trackingCapability ?: run {
            Log.e(TAG, "Could not get tracking capabilities")
            return
        }

        val supportedTypes = capabilities.supportHealthTrackerTypes
        Log.d(TAG, "Device supported sensor types: $supportedTypes")

        val typesToTrack = listOf(
            HealthTrackerType.HEART_RATE,
            HealthTrackerType.SKIN_TEMPERATURE,
            HealthTrackerType.ACCELEROMETER
        )

        for (type in typesToTrack) {
            if (supportedTypes.contains(type)) {
                val tracker = healthTrackingService?.getHealthTracker(type)
                tracker?.setEventListener(dataListener)
                trackers.add(tracker!!)
                Log.d(TAG, "Tracker for $type is set up.")
            } else {
                Log.w(TAG, "Device does not support tracker type: $type")
            }
        }
    }

    fun startMonitoring() {
        if (!_isConnected.value) {
            Log.w(TAG, "Cannot start monitoring - service not connected. Please initialize first.")
            return
        }
        if (trackers.isEmpty()) {
            setupTrackers()
        }
        Log.d(TAG, "Monitoring is active.")
    }

    fun stopMonitoring() {
        Log.d(TAG, "Stopping monitoring all sensors...")
        if (trackers.isNotEmpty()) {
            trackers.forEach {
                it.unsetEventListener()
            }
            trackers.clear()
        }
        if (_isConnected.value) {
            healthTrackingService?.disconnectService()
        }
        resetCurrentValues()
        readingBuffer.clear()
        _isConnected.value = false
        Log.d(TAG, "Monitoring stopped and service disconnected.")
    }

    @Synchronized
    private fun emitReadingIfReady() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastEmitTime < MIN_UPDATE_INTERVAL_MS) {
            return
        }

        val reading = WearBiometricReading(
            timestamp = currentTime,
            heartRate = currentHeartRate,
            interBeatInterval = currentIbi,
            skinTemperature = currentSkinTemp,
            accelerometerX = currentAccelData?.first,
            accelerometerY = currentAccelData?.second,
            accelerometerZ = currentAccelData?.third,
            confidence = calculateConfidence()
        )

        readingBuffer.add(reading)
        if (readingBuffer.size > BUFFER_SIZE) {
            readingBuffer.removeAt(0)
        }

        dataCallback?.invoke(reading)
        lastEmitTime = currentTime
    }

    private fun calculateConfidence(): Float {
        var confidence = 0.0f
        if (currentHeartRate != null) confidence += 0.4f
        if (currentIbi != null) confidence += 0.3f
        if (currentSkinTemp != null) confidence += 0.2f
        if (currentAccelData != null) confidence += 0.1f
        return confidence.coerceIn(0.0f, 1.0f)
    }

    private fun resetCurrentValues() {
        currentHeartRate = null
        currentIbi = null
        currentSkinTemp = null
        currentAccelData = null
        lastEmitTime = 0L
    }
}