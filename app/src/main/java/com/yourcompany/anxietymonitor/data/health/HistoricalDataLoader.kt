package com.yourcompany.anxietymonitor.data.health

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.yourcompany.anxietymonitor.data.repository.DataRepository
import com.yourcompany.anxietymonitor.domain.models.*
import com.yourcompany.anxietymonitor.domain.engine.BaselineEngine
import kotlinx.coroutines.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads historical health data from Health Connect to establish immediate baselines
 * This allows the app to work effectively from day one instead of waiting weeks
 */
@Singleton
class HistoricalDataLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DataRepository,
    private val baselineEngine: BaselineEngine
) {

    private var healthConnectClient: HealthConnectClient? = null

    companion object {
        private const val TAG = "HistoricalDataLoader"
        private const val MAX_HISTORICAL_DAYS = 30 // 1 months of data
        private const val MAX_MERGE_DIFFERENCE_MS = 300_000L // 5 minutes

        // Progress tracking
        const val STAGE_INITIALIZING = "Initializing Health Connect"
        const val STAGE_LOADING_HR = "Loading heart rate data"
        const val STAGE_LOADING_HRV = "Loading HRV data"
        const val STAGE_LOADING_TEMP = "Loading temperature data"
        const val STAGE_LOADING_ACTIVITY = "Loading activity data"
        const val STAGE_PROCESSING = "Processing data"
        const val STAGE_CALCULATING_BASELINES = "Calculating baselines"
        const val STAGE_COMPLETE = "Complete"
    }

    data class LoadProgress(
        val stage: String,
        val percentComplete: Float,
        val recordsLoaded: Int,
        val message: String
    )

    data class LoadResult(
        val success: Boolean,
        val recordsLoaded: Int,
        val daysOfData: Int,
        val baselinesCreated: Int,
        val oldestDataDate: Instant?,
        val message: String
    )

    /**
     * Load historical data and establish baselines
     * Returns immediately with a result, progress updates via callback
     */
    suspend fun loadHistoricalData(
        daysToLoad: Int = MAX_HISTORICAL_DAYS,
        onProgress: (LoadProgress) -> Unit = {}
    ): LoadResult = withContext(Dispatchers.IO) {

        try {
            // Initialize Health Connect
            onProgress(LoadProgress(STAGE_INITIALIZING, 0f, 0, "Connecting to Health Connect..."))

            val client = HealthConnectClient.getOrCreate(context)
            healthConnectClient = client

            // Check permissions
            if (!hasRequiredPermissions(client)) {
                return@withContext LoadResult(
                    success = false,
                    recordsLoaded = 0,
                    daysOfData = 0,
                    baselinesCreated = 0,
                    oldestDataDate = null,
                    message = "Missing Health Connect permissions"
                )
            }

            val endTime = Instant.now()
            val startTime = endTime.minusSeconds(daysToLoad.toLong() * 24 * 60 * 60)

            Log.d(TAG, "Loading historical data from $startTime to $endTime")

            val allReadings = mutableListOf<BiometricReading>()
            var oldestData: Instant? = null

            // Load heart rate data
            onProgress(LoadProgress(STAGE_LOADING_HR, 0.1f, 0, "Loading heart rate history..."))
            val hrReadings = loadHeartRateData(client, startTime, endTime, onProgress)
            allReadings.addAll(hrReadings)
            if (hrReadings.isNotEmpty()) {
                oldestData = Instant.ofEpochMilli(hrReadings.minOf { it.timestamp })
            }

            // Skip HRV data (not available in Health Connect)
            onProgress(LoadProgress(STAGE_LOADING_HRV, 0.3f, allReadings.size, "Skipping HRV (not available)..."))

            // Load temperature data
            onProgress(LoadProgress(STAGE_LOADING_TEMP, 0.5f, allReadings.size, "Loading temperature history..."))
            val tempData = loadTemperatureData(client, startTime, endTime)
            mergeTemperatureData(allReadings, tempData)

            // Load activity/steps for accelerometer estimation
            onProgress(LoadProgress(STAGE_LOADING_ACTIVITY, 0.7f, allReadings.size, "Loading activity history..."))
            val activityData = loadActivityData(client, startTime, endTime)
            mergeActivityData(allReadings, activityData)

            // Save all readings to repository
            onProgress(LoadProgress(STAGE_PROCESSING, 0.8f, allReadings.size, "Saving ${allReadings.size} readings..."))

            for (reading in allReadings) {
                repository.saveBiometricReading(reading)
            }

            // Calculate baselines from historical data
            onProgress(LoadProgress(STAGE_CALCULATING_BASELINES, 0.9f, allReadings.size, "Calculating personalized baselines..."))
            val baselinesCreated = calculateHistoricalBaselines(allReadings)

            // Calculate days of data
            val daysOfData = if (oldestData != null) {
                val daysDiff = (endTime.epochSecond - oldestData.epochSecond) / (24 * 60 * 60)
                daysDiff.toInt()
            } else 0

            onProgress(LoadProgress(STAGE_COMPLETE, 1.0f, allReadings.size, "Historical data loaded successfully"))

            LoadResult(
                success = true,
                recordsLoaded = allReadings.size,
                daysOfData = daysOfData,
                baselinesCreated = baselinesCreated,
                oldestDataDate = oldestData,
                message = "Loaded ${allReadings.size} readings from $daysOfData days of history"
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load historical data", e)
            LoadResult(
                success = false,
                recordsLoaded = 0,
                daysOfData = 0,
                baselinesCreated = 0,
                oldestDataDate = null,
                message = "Error: ${e.message}"
            )
        }
    }

    private suspend fun hasRequiredPermissions(client: HealthConnectClient): Boolean {
        return try {
            val grantedPermissions = client.permissionController.getGrantedPermissions()
            AndroidHealthConnectDataSource.REQUIRED_PERMISSIONS.all { it in grantedPermissions }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking permissions", e)
            false
        }
    }

    private suspend fun loadHeartRateData(
        client: HealthConnectClient,
        startTime: Instant,
        endTime: Instant,
        onProgress: (LoadProgress) -> Unit
    ): List<BiometricReading> {

        val readings = mutableListOf<BiometricReading>()

        try {
            val request = ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
            )

            val response = client.readRecords(request)
            val totalRecords = response.records.size

            response.records.forEachIndexed { index, record ->
                // HeartRateRecord has samples, not direct properties
                record.samples.forEach { sample ->
                    val reading = BiometricReading(
                        timestamp = sample.time.toEpochMilli(),
                        heartRate = sample.beatsPerMinute.toInt(),
                        hrvRmssd = null, // HRV not available in Health Connect
                        skinTemperature = null,
                        accelerometerMagnitude = null,
                        confidence = 0.7f, // Historical data has lower confidence
                        source = BiometricSource.HEALTH_CONNECT
                    )
                    readings.add(reading)
                }

                if (index % 100 == 0) {
                    val progress = 0.1f + (0.2f * index / totalRecords)
                    onProgress(LoadProgress(
                        STAGE_LOADING_HR,
                        progress,
                        readings.size,
                        "Loaded ${index + 1} of $totalRecords heart rate records"
                    ))
                }
            }

            Log.d(TAG, "Loaded ${readings.size} heart rate records")

        } catch (e: Exception) {
            Log.e(TAG, "Error loading heart rate data", e)
        }

        return readings
    }

    private suspend fun loadTemperatureData(
        client: HealthConnectClient,
        startTime: Instant,
        endTime: Instant
    ): Map<Long, Float> {

        val tempMap = mutableMapOf<Long, Float>()

        try {
            val request = ReadRecordsRequest(
                recordType = SkinTemperatureRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
            )

            val response = client.readRecords(request)

            response.records.forEach { record ->
                // SkinTemperatureRecord uses baseline property and startTime
                record.baseline?.let { baseline ->
                    tempMap[record.startTime.toEpochMilli()] = baseline.inCelsius.toFloat()
                }
            }

            Log.d(TAG, "Loaded ${tempMap.size} temperature records")

        } catch (e: Exception) {
            Log.e(TAG, "Error loading temperature data", e)
        }

        return tempMap
    }

    private suspend fun loadActivityData(
        client: HealthConnectClient,
        startTime: Instant,
        endTime: Instant
    ): Map<Long, Float> {

        val activityMap = mutableMapOf<Long, Float>()

        try {
            // Load steps data to estimate activity level
            val request = ReadRecordsRequest(
                recordType = StepsRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
            )

            val response = client.readRecords(request)

            response.records.forEach { record ->
                // Estimate activity level from steps
                val duration = (record.endTime.toEpochMilli() - record.startTime.toEpochMilli()) / 1000.0
                val stepsPerSecond = if (duration > 0) record.count / duration else 0.0

                // Convert to estimated accelerometer magnitude
                val activityLevel = when {
                    stepsPerSecond > 2.0 -> 3.0f  // Running
                    stepsPerSecond > 1.5 -> 2.0f  // Fast walking
                    stepsPerSecond > 0.8 -> 1.0f  // Walking
                    stepsPerSecond > 0 -> 0.5f    // Slow walking
                    else -> 0.1f                  // Minimal activity
                }

                activityMap[record.startTime.toEpochMilli()] = activityLevel
            }

            Log.d(TAG, "Loaded ${activityMap.size} activity records")

        } catch (e: Exception) {
            Log.e(TAG, "Error loading activity data", e)
        }

        return activityMap
    }

    private fun mergeTemperatureData(readings: MutableList<BiometricReading>, tempData: Map<Long, Float>) {
        for (reading in readings) {
            // The last parameter is now omitted, using the default value
            val closestTemp = findClosestValue(reading.timestamp, tempData)
            if (closestTemp != null) {
                val index = readings.indexOf(reading)
                readings[index] = reading.copy(skinTemperature = closestTemp)
            }
        }
    }

    private fun mergeActivityData(readings: MutableList<BiometricReading>, activityData: Map<Long, Float>) {
        for (reading in readings) {
            // The last parameter is now omitted, using the default value
            val closestActivity = findClosestValue(reading.timestamp, activityData)
            if (closestActivity != null) {
                val index = readings.indexOf(reading)
                readings[index] = reading.copy(accelerometerMagnitude = closestActivity)
            }
        }
    }

    private fun <T> findClosestValue(
        targetTime: Long,
        dataMap: Map<Long, T>,
        maxDifferenceMs: Long = MAX_MERGE_DIFFERENCE_MS // Set default value
    ): T? {

        var closestTime: Long? = null
        var minDifference = Long.MAX_VALUE

        for (time in dataMap.keys) {
            val difference = kotlin.math.abs(time - targetTime)
            if (difference < minDifference && difference <= maxDifferenceMs) {
                minDifference = difference
                closestTime = time
            }
        }

        return closestTime?.let { dataMap[it] }
    }

    private suspend fun calculateHistoricalBaselines(readings: List<BiometricReading>): Int {
        if (readings.isEmpty()) return 0

        var baselinesCreated = 0

        // Group readings by hour of day
        val readingsByHour = readings.groupBy { reading ->
            LocalDateTime.ofInstant(
                Instant.ofEpochMilli(reading.timestamp),
                ZoneId.systemDefault()
            ).hour
        }

        // Create baseline for each hour with sufficient data
        for ((hour, hourReadings) in readingsByHour) {
            if (hourReadings.size >= 10) {
                val baseline = baselineEngine.calculateBaseline(
                    readings = hourReadings,
                    timeOfDay = hour,
                    deviceType = BiometricSource.HEALTH_CONNECT
                )

                if (baseline != null) {
                    repository.saveBaseline(baseline)
                    baselinesCreated++
                    Log.d(TAG, "Created baseline for hour $hour with ${hourReadings.size} readings")
                }
            }
        }

        return baselinesCreated
    }

    /**
     * Get health app availability info
     */
    fun getHealthAppInfo(): Map<String, Any> {
        val installedApps = mutableMapOf<String, Boolean>()

        val healthApps = mapOf(
            "com.google.android.apps.healthdata" to "Health Connect",
            "com.sec.android.app.shealth" to "Samsung Health",
            "com.google.android.apps.fitness" to "Google Fit",
            "com.fitbit.FitbitMobile" to "Fitbit"
        )

        for ((packageName, appName) in healthApps) {
            try {
                context.packageManager.getPackageInfo(packageName, 0)
                installedApps[appName] = true
            } catch (_: PackageManager.NameNotFoundException) {
                installedApps[appName] = false
            }
        }

        return installedApps
    }
}