package com.yourcompany.anxietymonitor.ml

import android.content.Context
import android.util.Log
import com.yourcompany.anxietymonitor.domain.models.*
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedReader
import java.io.InputStreamReader

@Singleton
class AndroidTensorFlowLiteModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val featureEngineer: FeatureEngineer
) {

    private var interpreter: Interpreter? = null
    private var scalerMeans: FloatArray? = null
    private var scalerScales: FloatArray? = null
    private var featureNames: List<String>? = null
    private val modelInputSize = 20

    // Feedback learning properties
    private val feedbackScope = CoroutineScope(Dispatchers.IO)
    private val feedbackHistory = mutableListOf<FeedbackEntry>()
    private var adjustedThreshold = 0.5f
    private var thresholdAdjustmentCount = 0

    companion object {
        private const val TAG = "AndroidTensorFlowLiteModel"
        private const val MODEL_FILE = "anxiety_detection_model.tflite"
        private const val SCALER_FILE = "anxiety_detection_model_scaler_params.json"
    }

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Initializing TensorFlow Lite model...")

            // Load TensorFlow Lite model
            val modelBuffer = FileUtil.loadMappedFile(context, MODEL_FILE)
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                setUseNNAPI(true)
            }
            interpreter = Interpreter(modelBuffer, options)

            // Load scaler parameters from JSON
            loadScalerParameters()

            Log.d(TAG, "TensorFlow Lite model initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TensorFlow Lite model", e)
            false
        }
    }

    private fun loadScalerParameters() {
        try {
            val inputStream = context.assets.open(SCALER_FILE)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val jsonString = reader.readText()
            reader.close()

            val jsonObject = JSONObject(jsonString)

            // Parse means
            val meansArray = jsonObject.getJSONArray("mean")
            scalerMeans = FloatArray(meansArray.length()) { i ->
                meansArray.getDouble(i).toFloat()
            }

            // Parse scales
            val scalesArray = jsonObject.getJSONArray("scale")
            scalerScales = FloatArray(scalesArray.length()) { i ->
                scalesArray.getDouble(i).toFloat()
            }

            // Parse feature names
            val namesArray = jsonObject.getJSONArray("feature_names")
            featureNames = List(namesArray.length()) { i ->
                namesArray.getString(i)
            }

            Log.d(TAG, "Loaded scaler parameters: ${scalerMeans?.size} features")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load scaler parameters", e)
            throw IllegalStateException("Failed to load scaler parameters: ${e.message}")
        }
    }

    private fun normalizeFeatures(features: FloatArray): FloatArray {
        val means = scalerMeans ?: throw IllegalStateException("Scaler not initialized")
        val scales = scalerScales ?: throw IllegalStateException("Scaler not initialized")

        if (features.size != means.size || features.size != scales.size) {
            throw IllegalArgumentException(
                "Feature size mismatch: got ${features.size}, expected ${means.size}"
            )
        }

        return FloatArray(features.size) { i ->
            (features[i] - means[i]) / scales[i]
        }
    }

    fun predict(features: FloatArray): Float {
        val interpreter = this.interpreter
            ?: throw IllegalStateException("Model not initialized")

        // Normalize features using loaded scaler parameters
        val normalizedFeatures = normalizeFeatures(features)

        // Prepare input buffer
        val inputBuffer = ByteBuffer.allocateDirect(modelInputSize * 4).apply {
            order(ByteOrder.nativeOrder())
            rewind()
            normalizedFeatures.forEach { putFloat(it) }
        }

        // Prepare output buffer
        val outputBuffer = ByteBuffer.allocateDirect(4).apply {
            order(ByteOrder.nativeOrder())
        }

        // Run inference
        interpreter.run(inputBuffer, outputBuffer)

        outputBuffer.rewind()
        return outputBuffer.float
    }

    fun predictWithConfidence(features: FloatArray): Pair<Float, Float> {
        val prediction = predict(features)

        // Calculate confidence based on how far from 0.5 the prediction is
        val confidence = kotlin.math.abs(prediction - 0.5f) * 2f

        return Pair(prediction, confidence)
    }

    fun predictWithAdjustedThreshold(features: FloatArray): Pair<Float, Boolean> {
        val rawPrediction = predict(features)
        val adjustedPrediction = rawPrediction > adjustedThreshold
        return Pair(rawPrediction, adjustedPrediction)
    }

    fun engineerFeatures(
        reading: BiometricReading,
        baseline: UserBaseline,
        recentReadings: List<BiometricReading>,
        userFeedback: List<UserFeedback>
    ): FloatArray {
        return featureEngineer.createFeatures(reading, baseline, recentReadings, userFeedback)
    }

    fun updateWithFeedback(
        features: FloatArray,
        prediction: Float,
        correctLabel: Float, // 0.0 = not anxious, 1.0 = anxious
        timestamp: Long = System.currentTimeMillis()
    ) {
        feedbackScope.launch {
            try {
                // 1. Store feedback for model improvement
                val feedbackEntry = FeedbackEntry(
                    features = features.clone(),
                    prediction = prediction,
                    correctLabel = correctLabel,
                    timestamp = timestamp,
                    predictionError = kotlin.math.abs(prediction - correctLabel)
                )

                feedbackHistory.add(feedbackEntry)
                Log.d(TAG, "Feedback recorded: prediction=$prediction, correct=$correctLabel, error=${feedbackEntry.predictionError}")

                // 2. Trigger immediate threshold adjustment for quick improvement
                adjustPredictionThreshold(feedbackEntry)

                // 3. Check if we have enough feedback for model retraining
                if (shouldTriggerRetraining()) {
                    Log.d(TAG, "Triggering model retraining with ${feedbackHistory.size} feedback entries")
                    triggerModelRetraining()
                }

                // 4. Clean up old feedback data (keep last 1000 entries)
                if (feedbackHistory.size > 1000) {
                    feedbackHistory.removeAt(0)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error processing feedback", e)
            }
        }
    }

    private fun adjustPredictionThreshold(feedback: FeedbackEntry) {
        // Simple threshold adjustment based on recent feedback
        val learningRate = 0.01f

        when {
            // False positive: model predicted anxiety but user says no
            feedback.prediction > 0.5f && feedback.correctLabel < 0.5f -> {
                adjustedThreshold += learningRate
                Log.d(TAG, "False positive detected, raising threshold to $adjustedThreshold")
            }

            // False negative: model didn't predict anxiety but user says yes
            feedback.prediction < 0.5f && feedback.correctLabel > 0.5f -> {
                adjustedThreshold -= learningRate
                Log.d(TAG, "False negative detected, lowering threshold to $adjustedThreshold")
            }
        }

        // Keep threshold within reasonable bounds
        adjustedThreshold = adjustedThreshold.coerceIn(0.2f, 0.8f)
        thresholdAdjustmentCount++
    }

    private fun shouldTriggerRetraining(): Boolean {
        // Trigger retraining when we have enough diverse feedback
        return feedbackHistory.size >= 50 &&
                feedbackHistory.size % 25 == 0 && // Every 25 new feedback entries
                hasSignificantErrors()
    }

    private fun hasSignificantErrors(): Boolean {
        val recentFeedback = feedbackHistory.takeLast(25)
        val averageError = recentFeedback.map { it.predictionError }.average()
        return averageError > 0.3 // If average error > 30%, we need retraining
    }

    private fun triggerModelRetraining() {
        // In a production system, this would:
        // 1. Send feedback data to a training service
        // 2. Retrain the model with new data
        // 3. Download updated model

        // For now, we'll implement a simple feature weight adjustment
        adjustFeatureWeights()
    }

    private fun adjustFeatureWeights() {
        // Analyze which features are most predictive based on feedback
        val featureImportance = calculateFeatureImportance()
        Log.d(TAG, "Updated feature importance: $featureImportance")

        // This could be used to adjust feature engineering in the future
    }

    private fun calculateFeatureImportance(): Map<Int, Float> {
        // Simple correlation-based feature importance
        val importance = mutableMapOf<Int, Float>()

        feedbackHistory.takeLast(50).forEach { feedback ->
            feedback.features.forEachIndexed { index, value ->
                val correlation = value * feedback.correctLabel
                importance[index] = (importance[index] ?: 0f) + correlation
            }
        }

        return importance
    }

    fun getFeatureNames(): List<String>? = featureNames

    fun getModelInfo(): Map<String, Any> {
        val interpreter = this.interpreter ?: return emptyMap()

        return mapOf(
            "input_shape" to interpreter.getInputTensor(0).shape().contentToString(),
            "output_shape" to interpreter.getOutputTensor(0).shape().contentToString(),
            "feature_count" to (scalerMeans?.size ?: 0),
            "feature_names" to (featureNames ?: emptyList<String>()),
            "adjusted_threshold" to adjustedThreshold,
            "total_feedback" to feedbackHistory.size
        )
    }

    fun getFeedbackStats(): Map<String, Any> {
        return mapOf(
            "total_feedback" to feedbackHistory.size,
            "adjusted_threshold" to adjustedThreshold,
            "threshold_adjustments" to thresholdAdjustmentCount,
            "average_error" to if (feedbackHistory.isNotEmpty())
                feedbackHistory.map { it.predictionError }.average() else 0.0,
            "recent_accuracy" to calculateRecentAccuracy()
        )
    }

    private fun calculateRecentAccuracy(): Float {
        val recentFeedback = feedbackHistory.takeLast(20)
        if (recentFeedback.isEmpty()) return 0f

        val correctPredictions = recentFeedback.count { feedback ->
            val predicted = feedback.prediction > adjustedThreshold
            val actual = feedback.correctLabel > 0.5f
            predicted == actual
        }

        return correctPredictions.toFloat() / recentFeedback.size
    }

    fun isInitialized(): Boolean {
        return interpreter != null && scalerMeans != null && scalerScales != null
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        scalerMeans = null
        scalerScales = null
        featureNames = null
        feedbackHistory.clear()
        Log.d(TAG, "TensorFlow Lite model closed and resources cleaned up")
    }

    // Data class for feedback storage
    private data class FeedbackEntry(
        val features: FloatArray,
        val prediction: Float,
        val correctLabel: Float,
        val timestamp: Long,
        val predictionError: Float
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as FeedbackEntry

            if (!features.contentEquals(other.features)) return false
            if (prediction != other.prediction) return false
            if (correctLabel != other.correctLabel) return false
            if (timestamp != other.timestamp) return false
            if (predictionError != other.predictionError) return false

            return true
        }

        override fun hashCode(): Int {
            var result = features.contentHashCode()
            result = 31 * result + prediction.hashCode()
            result = 31 * result + correctLabel.hashCode()
            result = 31 * result + timestamp.hashCode()
            result = 31 * result + predictionError.hashCode()
            return result
        }
    }
}