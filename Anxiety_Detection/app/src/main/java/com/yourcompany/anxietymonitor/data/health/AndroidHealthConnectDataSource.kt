package com.yourcompany.anxietymonitor.data.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.yourcompany.anxietymonitor.domain.interfaces.BiometricDataSource
import com.yourcompany.anxietymonitor.domain.models.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

@Singleton
class AndroidHealthConnectDataSource @Inject constructor(
    @ApplicationContext private val context: Context
) : BiometricDataSource {

    private var healthConnectClient: HealthConnectClient? = null
    private var isInitialized = false
    private var isStreaming = false
    private var streamingJob: Job? = null
    private val classScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var currentHR: Int? = null
    private var currentTemp: Float? = null
    private var currentAccel: Triple<Float, Float, Float>? = null

    companion object {
        private const val TAG = "AndroidHealthConnectDataSource"
        private const val POLLING_INTERVAL_MS = 5000L
        val REQUIRED_PERMISSIONS = setOf(
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(SkinTemperatureRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(SpeedRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class)
        )
    }

    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
                return@withContext false
            }
            healthConnectClient = HealthConnectClient.getOrCreate(context)
            isInitialized = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Health Connect", e)
            false
        }
    }

    override suspend fun startStreaming(): Boolean {
        if (!isInitialized) return false
        if (isStreaming) return true
        val client = healthConnectClient ?: return false
        if (!checkPermissions()) {
            Log.w(TAG, "Cannot start streaming - missing permissions")
            return false
        }
        streamingJob = classScope.launch {
            while (isActive) {
                pollLatestData(client)
                delay(POLLING_INTERVAL_MS)
            }
        }
        isStreaming = true
        return true
    }

    override suspend fun stopStreaming() {
        streamingJob?.cancelAndJoin()
        streamingJob = null
        isStreaming = false
        resetCurrentReadings()
    }

    override fun isConnected(): Boolean = isInitialized && isStreaming

    override fun getDeviceInfo(): DeviceInfo {
        return DeviceInfo("Health Connect", BiometricSource.HEALTH_CONNECT, capabilities = setOf(
            SensorCapability.HEART_RATE, SensorCapability.SKIN_TEMPERATURE
        ))
    }

    override fun observeRealTimeData(): Flow<BiometricReading> = callbackFlow {
        healthConnectClient ?: run {
            close(IllegalStateException("Health Connect client not available")); return@callbackFlow
        }
        if (!startStreaming()) {
            close(IllegalStateException("Could not start streaming")); return@callbackFlow
        }
        val observationJob = classScope.launch {
            while(isActive) {
                if(currentHR != null || currentTemp != null) {
                    trySend(createCurrentReading())
                }
                delay(POLLING_INTERVAL_MS)
            }
        }
        awaitClose {
            classScope.launch { stopStreaming() }
            observationJob.cancel()
        }
    }

    private suspend fun pollLatestData(client: HealthConnectClient) {
        val endTime = Instant.now()
        val startTime = endTime.minusSeconds(30)
        try {
            // Heart Rate
            val hrRequest = ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
            )
            val hrResponse = client.readRecords(hrRequest)
            hrResponse.records
                .flatMap { it.samples }
                .maxByOrNull { it.time }
                ?.let { currentHR = it.beatsPerMinute.toInt() }

            // Skin Temperature
            val tempRequest = ReadRecordsRequest(
                recordType = SkinTemperatureRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
            )
            val tempResponse = client.readRecords(tempRequest)
            tempResponse.records
                .maxByOrNull { it.startTime }
                ?.let { record ->
                    // SkinTemperatureRecord uses baseline property in newer versions
                    currentTemp = record.baseline?.inCelsius?.toFloat()
                }

            currentAccel = estimateActivityLevel(client, startTime, endTime)
        } catch (e: Exception) {
            Log.e(TAG, "Error polling latest data", e)
        }
    }

    private suspend fun estimateActivityLevel(client: HealthConnectClient, startTime: Instant, endTime: Instant): Triple<Float, Float, Float>? {
        try {
            // Speed records
            val speedRequest = ReadRecordsRequest(
                recordType = SpeedRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
            )
            val speedResponse = client.readRecords(speedRequest)
            speedResponse.records
                .flatMap { it.samples }
                .maxByOrNull { it.time }
                ?.let { sample ->
                    val speedMs = sample.speed.inMetersPerSecond
                    val level = when {
                        speedMs > 2.5 -> 3.0f
                        speedMs > 1.0 -> 2.0f
                        else -> 1.0f
                    }
                    return Triple(level, 0f, 0f)
                }

            // Steps records
            val stepsRequest = ReadRecordsRequest(
                recordType = StepsRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
            )
            val stepsResponse = client.readRecords(stepsRequest)
            if (stepsResponse.records.sumOf { it.count } > 5) {
                return Triple(2.0f, 0f, 0f)
            }

            return Triple(1.0f, 0f, 0f)
        } catch (e: Exception) {
            Log.e(TAG, "Error estimating activity level", e)
            return null
        }
    }

    private fun createCurrentReading(): BiometricReading {
        val accelMagnitude = currentAccel?.let { (x, y, z) -> sqrt(x * x + y * y + z * z) }
        return BiometricReading(
            timestamp = System.currentTimeMillis(),
            heartRate = currentHR,
            hrvRmssd = null, // HRV not available in Health Connect
            skinTemperature = currentTemp,
            accelerometerMagnitude = accelMagnitude,
            confidence = calculateConfidence(),
            source = BiometricSource.HEALTH_CONNECT
        )
    }

    private fun calculateConfidence(): Float {
        var confidence = 0.0f
        if (currentHR != null) confidence += 0.5f
        if (currentTemp != null) confidence += 0.3f
        if (currentAccel != null) confidence += 0.2f
        return confidence.coerceIn(0.0f, 1.0f)
    }

    suspend fun checkPermissions(): Boolean {
        val client = healthConnectClient ?: return false
        return try {
            val granted = client.permissionController.getGrantedPermissions()
            REQUIRED_PERMISSIONS.all { it in granted }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking permissions", e)
            false
        }
    }

    @Suppress("unused")
    private fun isHealthConnectAvailable(): Boolean {
        return HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }

    private fun resetCurrentReadings() {
        currentHR = null
        currentTemp = null
        currentAccel = null
    }

    @Suppress("unused")
    suspend fun getHistoricalData(startTime: Instant, endTime: Instant): List<BiometricReading> = withContext(Dispatchers.IO) {
        val client = healthConnectClient ?: return@withContext emptyList()
        try {
            val hrRequest = ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
            )
            val hrResponse = client.readRecords(hrRequest)
            hrResponse.records.flatMap { record ->
                record.samples.map { sample ->
                    BiometricReading(
                        timestamp = sample.time.toEpochMilli(),
                        heartRate = sample.beatsPerMinute.toInt(),
                        hrvRmssd = null, // HRV not available in Health Connect
                        skinTemperature = null,
                        accelerometerMagnitude = null,
                        confidence = 0.5f,
                        source = BiometricSource.HEALTH_CONNECT
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting historical data", e)
            emptyList()
        }
    }

    @Suppress("unused")
    suspend fun requestPermissions(): Set<String> {
        val client = healthConnectClient ?: return emptySet()
        return try {
            val granted = client.permissionController.getGrantedPermissions()
            REQUIRED_PERMISSIONS - granted
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting permissions", e)
            REQUIRED_PERMISSIONS
        }
    }
}