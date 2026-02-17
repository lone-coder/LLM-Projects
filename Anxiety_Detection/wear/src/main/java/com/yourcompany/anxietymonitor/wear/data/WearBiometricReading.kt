package com.yourcompany.anxietymonitor.wear.data

import kotlin.math.sqrt

data class WearBiometricReading(
    val timestamp: Long,
    val heartRate: Int? = null,
    val interBeatInterval: Double? = null, // IBI in milliseconds
    val skinTemperature: Float? = null,
    val accelerometerX: Float? = null,
    val accelerometerY: Float? = null,
    val accelerometerZ: Float? = null,
    val confidence: Float = 1.0f
) {
    /**
     * Calculate HRV (RMSSD) from inter-beat interval
     * This is a simplified calculation - in production you'd want multiple IBIs
     */
    fun getHrvRmssd(): Double? {
        return interBeatInterval?.let { ibi ->
            // Simplified RMSSD approximation from single IBI
            // In reality, you'd calculate from successive differences of multiple IBIs
            ibi * 0.8 // Rough approximation
        }
    }

    /**
     * Calculate accelerometer magnitude
     */
    fun getAccelerometerMagnitude(): Float? {
        return if (accelerometerX != null && accelerometerY != null && accelerometerZ != null) {
            sqrt(accelerometerX * accelerometerX +
                    accelerometerY * accelerometerY +
                    accelerometerZ * accelerometerZ)
        } else null
    }

    /**
     * Determine activity level based on accelerometer data
     */
    fun getActivityLevel(): String {
        val magnitude = getAccelerometerMagnitude()
        return when {
            magnitude == null -> "Unknown"
            magnitude < 0.5f -> "Resting"
            magnitude < 1.5f -> "Light Activity"
            magnitude < 3.0f -> "Moderate Activity"
            else -> "High Activity"
        }
    }

    /**
     * Check if this reading indicates potential anxiety
     * This is a simplified check - the phone app does more sophisticated analysis
     */
    fun isPotentialAnxiety(
        baselineHeartRate: Int,
        baselineHrv: Double? = null
    ): Boolean {
        val hr = heartRate ?: return false
        val hrv = getHrvRmssd()

        // Check for elevated heart rate (20% above baseline)
        val hrElevated = hr > baselineHeartRate * 1.2f

        // Check for reduced HRV (30% below baseline)
        val hrvReduced = if (baselineHrv != null && hrv != null) {
            hrv < baselineHrv * 0.7f
        } else false

        // Must not be exercising
        val notExercising = (getAccelerometerMagnitude() ?: 0f) < 2.0f

        return hrElevated && (hrvReduced || hrv == null) && notExercising
    }

    /**
     * Convert to simple map for easier serialization
     */
    fun toMap(): Map<String, Any?> {
        return mapOf(
            "timestamp" to timestamp,
            "heart_rate" to heartRate,
            "ibi" to interBeatInterval,
            "hrv_rmssd" to getHrvRmssd(),
            "skin_temp" to skinTemperature,
            "accel_x" to accelerometerX,
            "accel_y" to accelerometerY,
            "accel_z" to accelerometerZ,
            "accel_magnitude" to getAccelerometerMagnitude(),
            "activity_level" to getActivityLevel(),
            "confidence" to confidence
        )
    }

    companion object {
        /**
         * Create a reading from a map (for deserialization)
         */
        fun fromMap(map: Map<String, Any?>): WearBiometricReading {
            return WearBiometricReading(
                timestamp = (map["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                heartRate = (map["heart_rate"] as? Number)?.toInt(),
                interBeatInterval = (map["ibi"] as? Number)?.toDouble(),
                skinTemperature = (map["skin_temp"] as? Number)?.toFloat(),
                accelerometerX = (map["accel_x"] as? Number)?.toFloat(),
                accelerometerY = (map["accel_y"] as? Number)?.toFloat(),
                accelerometerZ = (map["accel_z"] as? Number)?.toFloat(),
                confidence = (map["confidence"] as? Number)?.toFloat() ?: 1.0f
            )
        }
    }
}