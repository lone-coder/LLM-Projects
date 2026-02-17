package com.yourcompany.anxietymonitor.domain.models

/**
 * Represents the different biometric sensor capabilities available on devices
 */
enum class SensorCapability(
    val displayName: String,
    val description: String,
    val isRequired: Boolean = false
) {
    /**
     * Heart rate monitoring - measures beats per minute
     */
    HEART_RATE(
        displayName = "Heart Rate",
        description = "Measures heart rate in beats per minute",
        isRequired = true
    ),

    /**
     * Heart Rate Variability - measures variation between heartbeats
     */
    @Suppress("SpellCheckingInspection")  // RMSSD is correct medical terminology
    HRV(
        displayName = "Heart Rate Variability",
        description = "Measures variation in time between heartbeats (RMSSD)",
        isRequired = true
    ),

    /**
     * Skin temperature monitoring
     */
    SKIN_TEMPERATURE(
        displayName = "Skin Temperature",
        description = "Measures skin surface temperature in Celsius",
        isRequired = false
    ),

    /**
     * Accelerometer for motion detection
     */
    ACCELEROMETER(
        displayName = "Motion Sensor",
        description = "Detects movement and activity levels",
        isRequired = false
    ),

    /**
     * Real-time streaming capability
     */
    REAL_TIME_STREAMING(
        displayName = "Real-time Streaming",
        description = "Supports continuous real-time data streaming",
        isRequired = false
    );

    companion object {
        /**
         * Get all required sensor capabilities
         */
        fun getRequiredCapabilities(): List<SensorCapability> {
            return entries.filter { it.isRequired }
        }

        /**
         * Get all optional sensor capabilities
         * Note: This function is provided for future UI features
         */
        @Suppress("unused")  // Will be used in settings/configuration UI
        fun getOptionalCapabilities(): List<SensorCapability> {
            return entries.filter { !it.isRequired }
        }

        /**
         * Check if a set of capabilities includes all required sensors
         * Used by data source selection logic
         */
        fun hasRequiredCapabilities(capabilities: Set<SensorCapability>): Boolean {
            return getRequiredCapabilities().all { it in capabilities }
        }
    }
}