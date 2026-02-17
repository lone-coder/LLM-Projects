package com.yourcompany.anxietymonitor.domain.models

data class BiometricReading(
    val timestamp: Long,
    val heartRate: Int?,
    val hrvRmssd: Double?,  // RMSSD is correct - Root Mean Square of Successive Differences
    val skinTemperature: Float?,
    val accelerometerMagnitude: Float?,
    val confidence: Float = 1.0f,
    val source: BiometricSource
)

enum class BiometricSource {
    GALAXY_WATCH,
    APPLE_WATCH,
    PHONE_SENSORS,
    MANUAL_INPUT,
    UNKNOWN,
    HEALTH_CONNECT
}

data class DeviceInfo(
    val name: String,
    val type: BiometricSource,
    val capabilities: Set<SensorCapability>
)


data class UserBaseline(
    val timeOfDay: Int,
    val avgHeartRate: Double,
    val avgHrv: Double,
    val avgTemperature: Double?,
    val dataPoints: Int,
    val lastUpdated: Long,
    val deviceType: BiometricSource
)

data class AnxietyEvent(
    val id: String = generateId(),
    val timestamp: Long,
    val type: AnxietyType,
    val confidence: Float,
    val heartRate: Int,
    val baselineHeartRate: Int,
    val hrv: Double?,
    val baselineHrv: Double?,
    val temperature: Float?,
    val baselineTemperature: Float?,
    val activityLevel: ActivityLevel,
    val biometricSource: BiometricSource,
    val detectionMethod: String = "HYBRID"
)

enum class AnxietyType {
    GENERAL_ANXIETY_SPIKE,
    PRE_SLEEP_ANXIETY,
    SUSTAINED_ELEVATION,
    PATTERN_ANOMALY
}

enum class ActivityLevel {
    SEDENTARY,
    LIGHT_ACTIVITY,
    MODERATE_ACTIVITY,
    HIGH_ACTIVITY
}

data class UserFeedback(
    val id: String = generateId(),
    val timestamp: Long,
    val anxietyEventId: String,
    val wasCorrect: Boolean,
    val userNotes: String?,
    val actualAnxietyLevel: Int?, // 0-10 scale
    val contextNotes: String?,
    val feedbackType: FeedbackType = FeedbackType.IMMEDIATE
)

enum class FeedbackType {
    IMMEDIATE,
    DELAYED,
    RETROSPECTIVE
}

@Suppress("SpellCheckingInspection")  // These are standard CBT terms
enum class CognitiveDistortion {
    ALL_OR_NOTHING,
    OVERGENERALIZATION,  // This is the correct spelling in CBT literature
    MENTAL_FILTER,
    DISQUALIFYING_POSITIVE,
    JUMPING_TO_CONCLUSIONS,
    MAGNIFICATION,
    EMOTIONAL_REASONING,
    SHOULD_STATEMENTS,
    LABELING,
    PERSONALIZATION
}

// Utility function
private fun generateId(): String = java.util.UUID.randomUUID().toString()