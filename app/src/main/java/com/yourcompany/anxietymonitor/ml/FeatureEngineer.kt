package com.yourcompany.anxietymonitor.ml

import com.yourcompany.anxietymonitor.domain.models.*
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

@Singleton
class FeatureEngineer @Inject constructor() {

    fun createFeatures(
        current: BiometricReading,
        baseline: UserBaseline,
        recentReadings: List<BiometricReading>,
        userFeedback: List<UserFeedback>
    ): FloatArray {

        // Basic biometric features
        val currentHR = current.heartRate?.toFloat() ?: 0f
        val baselineHR = baseline.avgHeartRate.toFloat()
        val hrDelta = currentHR - baselineHR
        val hrPercentile = if (baselineHR > 0) hrDelta / baselineHR else 0f

        val currentHRV = current.hrvRmssd?.toFloat() ?: 0f
        val baselineHRV = baseline.avgHrv.toFloat()
        val hrvDelta = currentHRV - baselineHRV
        val hrvRatio = if (baselineHRV > 0) currentHRV / baselineHRV else 1f

        val temperature = current.skinTemperature ?: 36.5f
        val tempDelta = temperature - (baseline.avgTemperature?.toFloat() ?: 36.5f)
        val activityLevel = current.accelerometerMagnitude ?: 0f

        // Time-based features
        val hour = LocalTime.ofInstant(Instant.ofEpochMilli(current.timestamp), ZoneId.systemDefault()).hour
        val timeOfDaySin = sin(2 * PI * hour / 24).toFloat()
        val timeOfDayCos = cos(2 * PI * hour / 24).toFloat()
        val dayOfWeek = ((current.timestamp / 86400000) % 7).toFloat()

        // Historical trend features
        val hrTrend5min = calculateTrend(recentReadings, 5) { it.heartRate?.toFloat() ?: 0f }
        val hrTrend15min = calculateTrend(recentReadings, 15) { it.heartRate?.toFloat() ?: 0f }
        val hrVariability = calculateVariability(recentReadings) { it.heartRate?.toFloat() ?: 0f }

        // Stress duration
        val stressDuration = calculateStressDuration(recentReadings, baselineHR)

        // User feedback features
        val recentFeedback = userFeedback.filter {
            abs(it.timestamp - current.timestamp) < 86400000 // Last 24h
        }
        val recentFalsePositives = if (recentFeedback.isNotEmpty()) {
            recentFeedback.count { !it.wasCorrect }.toFloat() / recentFeedback.size
        } else 0.5f

        val userTrustScore = if (recentFeedback.isNotEmpty()) {
            recentFeedback.count { it.wasCorrect }.toFloat() / recentFeedback.size
        } else 0.5f

        return floatArrayOf(
            currentHR, baselineHR, hrDelta, hrPercentile,
            currentHRV, baselineHRV, hrvDelta, hrvRatio,
            temperature, tempDelta, activityLevel,
            timeOfDaySin, timeOfDayCos, dayOfWeek,
            hrTrend5min, hrTrend15min, hrVariability,
            stressDuration, recentFalsePositives, userTrustScore
        )
    }

    private fun calculateTrend(readings: List<BiometricReading>, lookbackMinutes: Int, valueExtractor: (BiometricReading) -> Float): Float {
        val cutoffTime = System.currentTimeMillis() - (lookbackMinutes * 60 * 1000)
        val recentReadings = readings.filter { it.timestamp >= cutoffTime }

        if (recentReadings.size < 2) return 0f

        val values = recentReadings.map(valueExtractor)
        val x = (0 until values.size).map { it.toFloat() }.toFloatArray()
        val y = values.toFloatArray()

        // Simple linear regression slope
        val n = values.size
        val sumX = x.sum()
        val sumY = y.sum()
        val sumXY = x.zip(y) { xi, yi -> xi * yi }.sum()
        val sumXX = x.map { it * it }.sum()

        val slope = if (n * sumXX - sumX * sumX != 0f) {
            (n * sumXY - sumX * sumY) / (n * sumXX - sumX * sumX)
        } else 0f

        return slope
    }

    private fun calculateVariability(readings: List<BiometricReading>, valueExtractor: (BiometricReading) -> Float): Float {
        val values = readings.takeLast(10).map(valueExtractor)
        if (values.size < 2) return 0f

        val mean = values.average().toFloat()
        val variance = values.map { (it - mean).pow(2) }.average().toFloat()
        return sqrt(variance)
    }

    private fun calculateStressDuration(readings: List<BiometricReading>, baselineHR: Float): Float {
        var duration = 0f
        val threshold = baselineHR * 1.15f // 15% above baseline

        for (reading in readings.takeLast(20).reversed()) {
            val hr = reading.heartRate?.toFloat() ?: 0f
            if (hr > threshold) {
                duration += 1f
            } else {
                break
            }
        }

        return duration
    }
}