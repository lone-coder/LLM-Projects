// ============================================================================
// app/src/main/java/com/yourcompany/anxietymonitor/utils/BiometricValidator.kt
// ============================================================================

package com.yourcompany.anxietymonitor.utils

import com.yourcompany.anxietymonitor.domain.models.BiometricReading

object BiometricValidator {

    fun isValidReading(reading: BiometricReading): Boolean {
        return isValidHeartRate(reading.heartRate) &&
                isValidHRV(reading.hrvRmssd) &&
                isValidTemperature(reading.skinTemperature) &&
                isValidTimestamp(reading.timestamp)
    }

    fun isValidHeartRate(heartRate: Int?): Boolean {
        return heartRate == null || heartRate in Constants.MIN_HEART_RATE..Constants.MAX_HEART_RATE
    }

    fun isValidHRV(hrv: Double?): Boolean {
        return hrv == null || hrv in Constants.MIN_HRV..Constants.MAX_HRV
    }

    fun isValidTemperature(temperature: Float?): Boolean {
        return temperature == null || temperature in Constants.MIN_TEMPERATURE..Constants.MAX_TEMPERATURE
    }

    private fun isValidTimestamp(timestamp: Long): Boolean {
        val now = System.currentTimeMillis()
        val oneHourAgo = now - (60 * 60 * 1000)
        val oneHourFromNow = now + (60 * 60 * 1000)
        return timestamp in oneHourAgo..oneHourFromNow
    }

    fun sanitizeReading(reading: BiometricReading): BiometricReading {
        return reading.copy(
            heartRate = if (isValidHeartRate(reading.heartRate)) reading.heartRate else null,
            hrvRmssd = if (isValidHRV(reading.hrvRmssd)) reading.hrvRmssd else null,
            skinTemperature = if (isValidTemperature(reading.skinTemperature)) reading.skinTemperature else null
        )
    }
}