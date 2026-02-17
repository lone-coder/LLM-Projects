package com.yourcompany.anxietymonitor.domain.engine

import com.yourcompany.anxietymonitor.domain.models.*
import com.yourcompany.anxietymonitor.ml.AndroidTensorFlowLiteModel
import com.yourcompany.anxietymonitor.ml.ModelValidationUtils
import com.yourcompany.anxietymonitor.ml.PersonalizedThresholdManager
import com.yourcompany.anxietymonitor.data.repository.DataRepository
import com.yourcompany.anxietymonitor.service.WearableDataSyncService
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnxietyDetectionEngine @Inject constructor(
    private val mlModel: AndroidTensorFlowLiteModel,
    private val validationUtils: ModelValidationUtils,
    private val thresholdManager: PersonalizedThresholdManager,
    private val repository: DataRepository
) {
    // Lazy injection to avoid circular dependency
    @Inject
    lateinit var wearableSyncProvider: dagger.Lazy<WearableDataSyncService>
    companion object {
        private const val TAG = "AnxietyDetectionEngine"

        // Rule-based thresholds
        private const val HR_ELEVATION_THRESHOLD_PERCENTAGE = 0.2f
        private const val HRV_REDUCTION_THRESHOLD_PERCENTAGE = 0.3f
        private const val MIN_CONFIDENCE_THRESHOLD = 0.7f

        // Hybrid approach thresholds
        private const val MIN_READINGS_FOR_HYBRID = 100
        private const val MIN_READINGS_FOR_ML_DOMINANT = 500
        private const val SEDENTARY_THRESHOLD = 0.5f
        private const val EXERCISE_THRESHOLD = 2.0f // Above this = exercising, don't detect anxiety
    }

    // Store recent predictions for feedback learning
    private val recentPredictions = mutableMapOf<String, PredictionData>()

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!validationUtils.validateModelFiles()) {
                Log.w(TAG, "ML model files not found, using rule-based detection only")
                return@withContext true // Still functional with rules only
            }

            val initialized = mlModel.initialize()
            if (initialized) {
                validationUtils.logModelInfo(mlModel)
                validationUtils.testModelPrediction(mlModel)

                // Log feature names for debugging
                val featureNames = mlModel.getFeatureNames()
                Log.d(TAG, "ML model features: ${featureNames?.joinToString(", ") ?: "Unknown"}")

                Log.d(TAG, "AnxietyDetectionEngine initialized with ML support")

                // Log ML model learning stats
                val feedbackStats = mlModel.getFeedbackStats()
                Log.d(TAG, "ML model feedback stats: $feedbackStats")
            } else {
                Log.w(TAG, "ML model failed to initialize, using rule-based detection only")
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AnxietyDetectionEngine", e)
            false
        }
    }

    /**
     * Main detection method using hybrid approach based on available data
     */
    suspend fun detectAnxietyEvent(
        current: BiometricReading,
        baseline: UserBaseline,
        recentReadings: List<BiometricReading> = emptyList(),
        isNightTime: Boolean = false
    ): AnxietyEvent? {

        val userDataCount = repository.getReadingCount()

        return when {
            // Phase 1: Pure rule-based (first 2 weeks, <100 readings)
            userDataCount < MIN_READINGS_FOR_HYBRID -> {
                detectWithRules(current, baseline, isNightTime)?.copy(
                    detectionMethod = "RULES_ONLY"
                )
            }

            // Phase 2: Hybrid (weeks 2-4, 100-500 readings)
            userDataCount < MIN_READINGS_FOR_ML_DOMINANT -> {
                detectWithHybridApproach(current, baseline, recentReadings, isNightTime, 0.7f, 0.3f)
            }

            // Phase 3: ML-dominant (after month 1, 500+ readings)
            else -> {
                val userTrustScore = calculateUserTrustScore()
                val mlWeight = 0.8f * userTrustScore + 0.5f * (1 - userTrustScore)
                detectWithHybridApproach(current, baseline, recentReadings, isNightTime, 1f - mlWeight, mlWeight)
            }
        }
    }

    /**
     * Rule-based detection (your original logic)
     */
    private fun detectWithRules(
        current: BiometricReading,
        baseline: UserBaseline,
        isNightTime: Boolean
    ): AnxietyEvent? {

        val currentHR = current.heartRate ?: return null

        // Skip detection if user is exercising (high accelerometer activity)
        val accelerometerMagnitude = current.accelerometerMagnitude ?: 0f
        if (accelerometerMagnitude > EXERCISE_THRESHOLD) {
            Log.d(TAG, "Skipping anxiety detection - user appears to be exercising (accel: $accelerometerMagnitude)")
            return null
        }

        // Use simple threshold calculation since getPersonalizedThresholds doesn't exist
        val hrThreshold = baseline.avgHeartRate * (1 + HR_ELEVATION_THRESHOLD_PERCENTAGE)
        val hrvThreshold = baseline.avgHrv * (1 - HRV_REDUCTION_THRESHOLD_PERCENTAGE)

        val hrElevated = currentHR > hrThreshold
        val hrvLow = current.hrvRmssd?.let { it < hrvThreshold } ?: false
        val sedentary = accelerometerMagnitude < SEDENTARY_THRESHOLD

        // Only detect anxiety if HR is elevated AND (HRV is low OR user is sedentary)
        // This ensures we catch anxiety during both rest and light activity
        if (hrElevated && (hrvLow || sedentary)) {
            val confidence = calculateConfidence(current, baseline)

            if (confidence >= MIN_CONFIDENCE_THRESHOLD) {
                return AnxietyEvent(
                    timestamp = current.timestamp,
                    type = if (isNightTime) AnxietyType.PRE_SLEEP_ANXIETY else AnxietyType.GENERAL_ANXIETY_SPIKE,
                    confidence = confidence,
                    heartRate = currentHR,
                    baselineHeartRate = baseline.avgHeartRate.toInt(),
                    hrv = current.hrvRmssd,
                    baselineHrv = baseline.avgHrv,
                    temperature = current.skinTemperature,
                    baselineTemperature = baseline.avgTemperature?.toFloat(),
                    activityLevel = calculateActivityLevel(current.accelerometerMagnitude),
                    biometricSource = current.source,
                    detectionMethod = "RULE_BASED"
                )
            }
        }

        return null
    }

    /**
     * ML-based detection with adaptive learning
     */
    private suspend fun detectWithML(
        current: BiometricReading,
        baseline: UserBaseline,
        recentReadings: List<BiometricReading>,
        isNightTime: Boolean
    ): AnxietyEvent? {

        if (!mlModel.isInitialized() || recentReadings.size < 5) {
            return null
        }

        return try {
            val userFeedback = repository.getUserFeedback(since = System.currentTimeMillis() - 86400000L * 7) // Last 7 days
            val features = mlModel.engineerFeatures(current, baseline, recentReadings, userFeedback)

            // Use predictWithConfidence for better insight into model certainty
            val (rawPrediction, modelConfidence) = mlModel.predictWithConfidence(features)

            // Also get adjusted threshold prediction
            val (_, isAnxietyDetected) = mlModel.predictWithAdjustedThreshold(features)

            // Combine model confidence with threshold-based decision
            val finalConfidence = modelConfidence * (if (isAnxietyDetected) 1.0f else 0.5f)

            if (isAnxietyDetected && finalConfidence >= MIN_CONFIDENCE_THRESHOLD) {
                val currentHR = current.heartRate ?: return null

                val anxietyEvent = AnxietyEvent(
                    timestamp = current.timestamp,
                    type = if (isNightTime) AnxietyType.PRE_SLEEP_ANXIETY else AnxietyType.GENERAL_ANXIETY_SPIKE,
                    confidence = finalConfidence,
                    heartRate = currentHR,
                    baselineHeartRate = baseline.avgHeartRate.toInt(),
                    hrv = current.hrvRmssd,
                    baselineHrv = baseline.avgHrv,
                    temperature = current.skinTemperature,
                    baselineTemperature = baseline.avgTemperature?.toFloat(),
                    activityLevel = calculateActivityLevel(current.accelerometerMagnitude),
                    biometricSource = current.source,
                    detectionMethod = "ML_BASED"
                )

                // Store prediction data for potential feedback learning
                recentPredictions[anxietyEvent.id] = PredictionData(
                    features = features,
                    rawPrediction = rawPrediction,
                    timestamp = current.timestamp
                )

                // Clean up old predictions (keep last 50)
                if (recentPredictions.size > 50) {
                    val oldestKey = recentPredictions.minByOrNull { it.value.timestamp }?.key
                    oldestKey?.let { recentPredictions.remove(it) }
                }

                return anxietyEvent
            } else {
                // Store negative predictions too for false negative feedback
                val eventId = "negative_${current.timestamp}"
                recentPredictions[eventId] = PredictionData(
                    features = features,
                    rawPrediction = rawPrediction,
                    timestamp = current.timestamp
                )
                return null
            }

        } catch (e: Exception) {
            Log.e(TAG, "ML detection failed", e)
            null
        }
    }

    /**
     * Hybrid approach combining rule-based and ML results
     */
    private suspend fun detectWithHybridApproach(
        current: BiometricReading,
        baseline: UserBaseline,
        recentReadings: List<BiometricReading>,
        isNightTime: Boolean,
        ruleWeight: Float,
        mlWeight: Float
    ): AnxietyEvent? {

        val ruleResult = detectWithRules(current, baseline, isNightTime)
        val mlResult = detectWithML(current, baseline, recentReadings, isNightTime)

        return combineResults(ruleResult, mlResult, ruleWeight, mlWeight)
    }

    /**
     * Combine rule-based and ML results with weighted confidence
     */
    private fun combineResults(
        ruleResult: AnxietyEvent?,
        mlResult: AnxietyEvent?,
        ruleWeight: Float,
        mlWeight: Float
    ): AnxietyEvent? {

        when {
            ruleResult != null && mlResult != null -> {
                // Both detected anxiety - combine confidence scores
                val combinedConfidence = (ruleResult.confidence * ruleWeight + mlResult.confidence * mlWeight)

                return ruleResult.copy(
                    confidence = combinedConfidence,
                    detectionMethod = "HYBRID"
                )
            }

            ruleResult != null && mlWeight < 0.5f -> {
                // Rule-based dominant and detected
                return ruleResult.copy(detectionMethod = "HYBRID_RULE_DOMINANT")
            }

            mlResult != null && mlWeight >= 0.5f -> {
                // ML dominant and detected
                return mlResult.copy(detectionMethod = "HYBRID_ML_DOMINANT")
            }

            else -> return null
        }
    }

    /**
     * Calculate user trust score based on feedback accuracy
     */
    private suspend fun calculateUserTrustScore(): Float {
        return try {
            val recentFeedback = repository.getUserFeedback(since = System.currentTimeMillis() - 86400000L * 30) // Last 30 days
            if (recentFeedback.isEmpty()) return 0.5f

            val correctPredictions = recentFeedback.count { it.wasCorrect }
            correctPredictions.toFloat() / recentFeedback.size

        } catch (e: Exception) {
            Log.e(TAG, "Failed to calculate user trust score", e)
            0.5f // Default neutral trust
        }
    }

    /**
     * UPDATED: Enhanced feedback method that trains the ML model
     */
    suspend fun updateWithFeedback(
        anxietyEventId: String,
        wasCorrect: Boolean,
        actualAnxietyLevel: Int? = null,
        userNotes: String? = null
    ) {
        try {
            val feedback = UserFeedback(
                anxietyEventId = anxietyEventId,
                wasCorrect = wasCorrect,
                actualAnxietyLevel = actualAnxietyLevel,
                userNotes = userNotes,
                contextNotes = null,
                timestamp = System.currentTimeMillis(),
                feedbackType = FeedbackType.IMMEDIATE
            )

            repository.saveUserFeedback(feedback)

            // UPDATED: Train the ML model with this feedback
            val predictionData = recentPredictions[anxietyEventId]
            if (predictionData != null && mlModel.isInitialized()) {
                val correctLabel = if (wasCorrect) {
                    // If feedback says prediction was correct, use the original prediction as truth
                    if (predictionData.rawPrediction > 0.5f) 1.0f else 0.0f
                } else {
                    // If feedback says prediction was wrong, flip it
                    if (predictionData.rawPrediction > 0.5f) 0.0f else 1.0f
                }

                // Train the ML model
                mlModel.updateWithFeedback(
                    features = predictionData.features,
                    prediction = predictionData.rawPrediction,
                    correctLabel = correctLabel
                )

                Log.d(TAG, "ML model updated with feedback: eventId=$anxietyEventId, wasCorrect=$wasCorrect, correctLabel=$correctLabel")

                // Log updated ML stats
                val updatedStats = mlModel.getFeedbackStats()
                Log.d(TAG, "Updated ML feedback stats: $updatedStats")
            }

            // Update personalized thresholds based on feedback
            val recentFeedback = repository.getUserFeedback(since = System.currentTimeMillis() - 86400000L * 7)
            thresholdManager.updateThreshold(recentFeedback)

            Log.d(TAG, "User feedback recorded: wasCorrect=$wasCorrect")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to update with feedback", e)
        }
    }

    /**
     * CRITICAL: Method for handling false negative feedback (when user manually reports anxiety)
     * Called by MainActivity when user taps "Report Anxiety" FAB
     */
    suspend fun reportManualAnxietyEvent(
        timestamp: Long,
        userNotes: String? = null
    ) {
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Processing manual anxiety report at timestamp: $timestamp")

                val timeWindow = 10 * 60 * 1000L
                val recentPrediction = recentPredictions.values
                    .filter { kotlin.math.abs(it.timestamp - timestamp) < timeWindow }
                    .minByOrNull { kotlin.math.abs(it.timestamp - timestamp) }

                if (recentPrediction != null && mlModel.isInitialized()) {
                    // Update ML model
                    mlModel.updateWithFeedback(
                        features = recentPrediction.features,
                        prediction = recentPrediction.rawPrediction,
                        correctLabel = 1.0f
                    )

                    // Use lazy provider to get wearable sync
                    val wearableSync = wearableSyncProvider.get()
                    val manualEventId = "manual_${timestamp}"

                    wearableSync.sendAnxietyFeedback(
                        anxietyEventId = manualEventId,
                        wasCorrect = false,
                        confidence = recentPrediction.rawPrediction
                    )

                    Log.d(TAG, "ML model and watch updated with false negative feedback")

                } else {
                    // Use lazy provider for no prediction case too
                    val wearableSync = wearableSyncProvider.get()
                    val manualEventId = "manual_${timestamp}"

                    wearableSync.sendAnxietyFeedback(
                        anxietyEventId = manualEventId,
                        wasCorrect = false,
                        confidence = 0.0f
                    )
                }

                // Save feedback to repository
                val feedback = UserFeedback(
                    anxietyEventId = "manual_${timestamp}",
                    wasCorrect = false,
                    actualAnxietyLevel = 2,
                    userNotes = userNotes,
                    contextNotes = "Manually reported anxiety - false negative",
                    timestamp = timestamp,
                    feedbackType = FeedbackType.IMMEDIATE
                )

                repository.saveUserFeedback(feedback)

                Log.d(TAG, "Manual anxiety report processed successfully")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle manual anxiety report", e)
            }
        }
    }

    private fun calculateConfidence(current: BiometricReading, baseline: UserBaseline): Float {
        var confidence = 0.0f

        current.heartRate?.let { hr ->
            val hrDelta = hr - baseline.avgHeartRate.toFloat()
            confidence += when {
                hrDelta > 25 -> 0.4f
                hrDelta > 15 -> 0.3f
                hrDelta > 10 -> 0.2f
                else -> 0.0f
            }
        }

        current.hrvRmssd?.let { hrv ->
            val hrvRatio = hrv / baseline.avgHrv
            confidence += when {
                hrvRatio < 0.5 -> 0.4f
                hrvRatio < 0.7 -> 0.3f
                else -> 0.1f
            }
        }

        confidence *= current.confidence
        return confidence.coerceIn(0.0f, 1.0f)
    }

    private fun calculateActivityLevel(accelerometerMagnitude: Float?): ActivityLevel {
        return when (accelerometerMagnitude) {
            null -> ActivityLevel.SEDENTARY
            in 0.0f..0.5f -> ActivityLevel.SEDENTARY
            in 0.5f..1.5f -> ActivityLevel.LIGHT_ACTIVITY
            in 1.5f..3.0f -> ActivityLevel.MODERATE_ACTIVITY
            else -> ActivityLevel.HIGH_ACTIVITY
        }
    }

    fun isMLEnabled(): Boolean = mlModel.isInitialized()

    suspend fun getDetectionStats(): Map<String, Any> {
        return try {
            val totalReadings = repository.getReadingCount()
            val recentEvents = repository.getAnxietyEvents(since = System.currentTimeMillis() - 86400000L * 30) // Last 30 days
            val methodCounts = recentEvents.groupBy { it.detectionMethod }.mapValues { it.value.size }

            val baseStats = mapOf(
                "total_readings" to totalReadings,
                "ml_enabled" to mlModel.isInitialized(),
                "detection_methods" to methodCounts,
                "current_phase" to when {
                    totalReadings < MIN_READINGS_FOR_HYBRID -> "RULES_ONLY"
                    totalReadings < MIN_READINGS_FOR_ML_DOMINANT -> "HYBRID"
                    else -> "ML_DOMINANT"
                },
                "stored_predictions" to recentPredictions.size
            )

            // Add ML model stats if available
            if (mlModel.isInitialized()) {
                baseStats + mlModel.getFeedbackStats()
            } else {
                baseStats
            }

        } catch (e: Exception) {
            emptyMap<String, Any>()
        }
    }

    /**
     * Clean up resources when the engine is no longer needed
     */
    fun cleanup() {
        try {
            if (mlModel.isInitialized()) {
                mlModel.close()
                Log.d(TAG, "ML model resources cleaned up")
            }
            recentPredictions.clear()
            Log.d(TAG, "AnxietyDetectionEngine cleanup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }

    // Data class to store prediction data for feedback learning
    private data class PredictionData(
        val features: FloatArray,
        val rawPrediction: Float,
        val timestamp: Long
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as PredictionData

            if (!features.contentEquals(other.features)) return false
            if (rawPrediction != other.rawPrediction) return false
            if (timestamp != other.timestamp) return false

            return true
        }

        override fun hashCode(): Int {
            var result = features.contentHashCode()
            result = 31 * result + rawPrediction.hashCode()
            result = 31 * result + timestamp.hashCode()
            return result
        }
    }
}