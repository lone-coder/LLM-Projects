package com.yourcompany.anxietymonitor.domain.engine

import com.yourcompany.anxietymonitor.data.repository.DataRepository
import com.yourcompany.anxietymonitor.domain.models.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log

@Singleton
class BaselineEngine @Inject constructor(
    private val repository: DataRepository
) {

    companion object {
        private const val TAG = "BaselineEngine"
    }

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            // Validate existing baselines on startup
            val existingBaselines = getAllBaselines()
            Log.d(TAG, "Found ${existingBaselines.size} existing baselines")

            // Create initial baselines if none exist
            if (existingBaselines.isEmpty()) {
                createInitialBaselines()
            }

            Log.d(TAG, "BaselineEngine initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize BaselineEngine", e)
            false
        }
    }

    fun calculateBaseline(
        readings: List<BiometricReading>,
        timeOfDay: Int,
        deviceType: BiometricSource
    ): UserBaseline? {

        if (readings.size < 10) return null

        val hrReadings = readings.mapNotNull { it.heartRate?.toDouble() }
        val hrvReadings = readings.mapNotNull { it.hrvRmssd?.toDouble() }
        val tempReadings = readings.mapNotNull { it.skinTemperature?.toDouble() }

        if (hrReadings.isEmpty()) return null

        return UserBaseline(
            timeOfDay = timeOfDay,
            avgHeartRate = hrReadings.average(),
            avgHrv = if (hrvReadings.isNotEmpty()) hrvReadings.average() else 0.0,
            avgTemperature = if (tempReadings.isNotEmpty()) tempReadings.average() else null,
            dataPoints = readings.size,
            lastUpdated = System.currentTimeMillis(),
            deviceType = deviceType
        )
    }

    suspend fun updateBaseline(
        existing: UserBaseline,
        newReadings: List<BiometricReading>,
        blendRatio: Float = 0.1f
    ): UserBaseline {

        val newBaseline = calculateBaseline(newReadings, existing.timeOfDay, existing.deviceType)
            ?: return existing

        val updated = UserBaseline(
            timeOfDay = existing.timeOfDay,
            avgHeartRate = blendValues(existing.avgHeartRate, newBaseline.avgHeartRate, blendRatio),
            avgHrv = blendValues(existing.avgHrv, newBaseline.avgHrv, blendRatio),
            avgTemperature = blendNullableValues(existing.avgTemperature, newBaseline.avgTemperature, blendRatio),
            dataPoints = existing.dataPoints + newReadings.size,
            lastUpdated = System.currentTimeMillis(),
            deviceType = existing.deviceType
        )

        // Save the updated baseline to repository
        repository.saveBaseline(updated)
        return updated
    }

    suspend fun getBaseline(timeOfDay: Int): UserBaseline? {
        return repository.getBaseline(timeOfDay)
    }

    private suspend fun getAllBaselines(): List<UserBaseline> {
        return (0..23).mapNotNull { hour ->
            repository.getBaseline(hour)
        }
    }

    private suspend fun createInitialBaselines() {
        try {
            // Get recent readings to create initial baselines
            val recentReadings = repository.getRecentReadings(1000)
            if (recentReadings.isEmpty()) {
                Log.w(TAG, "No readings available for initial baseline creation")
                return
            }

            val deviceType = recentReadings.firstOrNull()?.source ?: BiometricSource.GALAXY_WATCH

            // Group readings by hour of day and create baselines
            recentReadings.groupBy {
                java.time.LocalTime.ofInstant(
                    java.time.Instant.ofEpochMilli(it.timestamp),
                    java.time.ZoneId.systemDefault()
                ).hour
            }.forEach { (hour, readings) ->
                if (readings.size >= 10) {
                    val baseline = calculateBaseline(readings, hour, deviceType)
                    baseline?.let { repository.saveBaseline(it) }
                    Log.d(TAG, "Created initial baseline for hour $hour with ${readings.size} readings")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create initial baselines", e)
        }
    }

    private fun blendValues(existing: Double, new: Double, ratio: Float): Double {
        return existing * (1 - ratio) + new * ratio
    }

    private fun blendNullableValues(existing: Double?, new: Double?, ratio: Float): Double? {
        return when {
            existing == null -> new
            new == null -> existing
            else -> blendValues(existing, new, ratio)
        }
    }
}