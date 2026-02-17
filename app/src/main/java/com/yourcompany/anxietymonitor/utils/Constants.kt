// ============================================================================
// app/src/main/java/com/yourcompany/anxietymonitor/utils/Constants.kt
// ============================================================================

package com.yourcompany.anxietymonitor.utils

object Constants {

    // Database
    const val DATABASE_NAME = "anxiety_database"
    const val DATABASE_VERSION = 1

    // Monitoring
    const val MONITORING_INTERVAL_MS = 30_000L // 30 seconds
    const val BASELINE_COLLECTION_PERIOD_MS = 60_000L * 60 * 24 * 7 // 1 week
    const val DATA_RETENTION_PERIOD_MS = 60_000L * 60 * 24 * 30 // 30 days

    // Thresholds
    const val MIN_HEART_RATE = 40
    const val MAX_HEART_RATE = 200
    const val MIN_HRV = 5.0
    const val MAX_HRV = 200.0
    const val MIN_TEMPERATURE = 32.0f
    const val MAX_TEMPERATURE = 42.0f

    // Detection
    const val BASELINE_MIN_READINGS = 20
    const val CONFIDENCE_THRESHOLD = 0.7f
    const val HR_ELEVATION_THRESHOLD = 0.2f // 20% above baseline
    const val HRV_REDUCTION_THRESHOLD = 0.3f // 30% below baseline

    // Notifications
    const val NOTIFICATION_CHANNEL_MONITORING = "anxiety_monitoring"
    const val NOTIFICATION_CHANNEL_ALERTS = "anxiety_alerts"
    const val NOTIFICATION_ID_SERVICE = 1

    // Permissions
    val REQUIRED_PERMISSIONS = arrayOf(
        "com.samsung.android.providers.health.permission.READ",
        "com.samsung.android.providers.health.permission.WRITE",
        android.Manifest.permission.POST_NOTIFICATIONS,
        android.Manifest.permission.FOREGROUND_SERVICE,
        android.Manifest.permission.FOREGROUND_SERVICE_HEALTH
    )

    // Samsung health
    const val SAMSUNG_HEALTH_PACKAGE = "com.sec.android.app.shealth"

    // Time periods
    const val MORNING_START_HOUR = 6
    const val MORNING_END_HOUR = 12
    const val AFTERNOON_START_HOUR = 12
    const val AFTERNOON_END_HOUR = 18
    const val EVENING_START_HOUR = 18
    const val EVENING_END_HOUR = 22
    const val NIGHT_START_HOUR = 22
    const val NIGHT_END_HOUR = 6

    // ML Model
    const val ML_MODEL_FILENAME = "anxiety_detection_model.tflite"
    const val FEATURE_COUNT = 20
    const val ML_PREDICTION_THRESHOLD = 0.7f

    // CBT
    const val MAX_THOUGHT_LENGTH = 500
    const val CBT_PROMPT_TIMEOUT_MS = 30_000L // 30 seconds

    // Feedback
    const val FEEDBACK_COLLECTION_PERIOD_MS = 60_000L * 60 * 24 // 24 hours
    const val MIN_FEEDBACK_FOR_ADAPTATION = 5

    // Privacy
    const val BIOMETRIC_AUTH_TIMEOUT_MS = 60_000L // 1 minute
    const val SESSION_TIMEOUT_MS = 60_000L * 15 // 15 minutes
}