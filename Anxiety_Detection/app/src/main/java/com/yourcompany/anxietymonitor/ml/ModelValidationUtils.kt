package com.yourcompany.anxietymonitor.ml

import android.content.Context
import android.util.Log
import com.yourcompany.anxietymonitor.domain.models.*
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

@Singleton
class ModelValidationUtils @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "ModelValidation"
    }

    fun validateModelFiles(): Boolean {
        val modelExists = checkAssetExists("anxiety_detection_model.tflite")
        val scalerExists = checkAssetExists("anxiety_detection_model_scaler_params.json")

        Log.d(TAG, "Model file exists: $modelExists")
        Log.d(TAG, "Scaler file exists: $scalerExists")

        return modelExists && scalerExists
    }

    private fun checkAssetExists(filename: String): Boolean {
        return try {
            context.assets.open(filename).use { true }
        } catch (e: Exception) {
            Log.e(TAG, "Asset $filename not found: ${e.message}")
            false
        }
    }

    fun testModelPrediction(model: AndroidTensorFlowLiteModel): Boolean {
        return try {
            // Create dummy features for testing
            val testFeatures = FloatArray(20) { i ->
                when (i) {
                    0 -> 75.0f  // heart_rate
                    1 -> 65.0f  // baseline_heart_rate
                    2 -> 0.03f  // hrv_rmssd
                    3 -> 0.035f // baseline_hrv
                    4 -> 36.5f  // skin_temperature
                    else -> 0.1f * i // other features
                }
            }

            val prediction = model.predict(testFeatures)
            val isValidPrediction = prediction in 0.0f..1.0f

            Log.d(TAG, "Test prediction: $prediction, Valid: $isValidPrediction")
            isValidPrediction

        } catch (e: Exception) {
            Log.e(TAG, "Model prediction test failed: ${e.message}")
            false
        }
    }

    fun logModelInfo(model: AndroidTensorFlowLiteModel) {
        val info = model.getModelInfo()
        Log.d(TAG, "=== Model Information ===")
        info.forEach { (key, value) ->
            Log.d(TAG, "$key: $value")
        }
        Log.d(TAG, "========================")
    }

    fun validateFeatureEngineering(
        featureEngineer: FeatureEngineer,
        current: BiometricReading,
        baseline: UserBaseline
    ): Boolean {
        return try {
            val features = featureEngineer.createFeatures(
                current = current,
                baseline = baseline,
                recentReadings = listOf(current),
                userFeedback = emptyList()
            )

            val isValidSize = features.size == 20
            val hasValidValues = features.all { it.isFinite() }

            Log.d(TAG, "Feature validation - Size: ${features.size}, Valid values: $hasValidValues")
            if (!isValidSize) {
                Log.e(TAG, "Expected 20 features, got ${features.size}")
            }

            isValidSize && hasValidValues

        } catch (e: Exception) {
            Log.e(TAG, "Feature engineering validation failed: ${e.message}")
            false
        }
    }
}