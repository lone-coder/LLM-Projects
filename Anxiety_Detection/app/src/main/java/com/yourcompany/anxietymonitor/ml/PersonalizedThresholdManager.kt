package com.yourcompany.anxietymonitor.ml

import com.yourcompany.anxietymonitor.domain.models.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

@Singleton
class PersonalizedThresholdManager @Inject constructor() {

    private var currentThreshold = 0.7f
    private val minThreshold = 0.5f
    private val maxThreshold = 0.9f
    private val adjustmentRate = 0.02f

    fun updateThreshold(feedback: List<UserFeedback>) {
        if (feedback.isEmpty()) return

        val recentFeedback = feedback.takeLast(20)
        val accuracy = recentFeedback.count { it.wasCorrect }.toFloat() / recentFeedback.size
        val falsePositiveRate = recentFeedback.count { !it.wasCorrect }.toFloat() / recentFeedback.size

        when {
            // Too many false positives - increase threshold (make less sensitive)
            falsePositiveRate > 0.3f -> {
                currentThreshold = min(maxThreshold, currentThreshold + adjustmentRate)
            }
            // Good accuracy but maybe missing some - decrease threshold slightly (more sensitive)
            accuracy > 0.8f && falsePositiveRate < 0.1f -> {
                currentThreshold = max(minThreshold, currentThreshold - adjustmentRate * 0.5f)
            }
            // Poor accuracy - adjust based on whether we're missing detections or getting false positives
            accuracy < 0.6f -> {
                currentThreshold = if (falsePositiveRate > 0.2f) {
                    // More false positives than missed detections - increase threshold
                    min(maxThreshold, currentThreshold + adjustmentRate * 2)
                } else {
                    // More missed detections than false positives - decrease threshold
                    max(minThreshold, currentThreshold - adjustmentRate)
                }
            }
        }
    }

    fun getCurrentThreshold(): Float = currentThreshold

    fun shouldTriggerAlert(confidence: Float): Boolean = confidence >= currentThreshold

    fun getThresholdForTimeOfDay(hour: Int): Float {
        // Adjust threshold based on time of day
        return when (hour) {
            in 22..23, in 0..6 -> currentThreshold * 0.9f // Lower threshold at night (more sensitive)
            in 7..9 -> currentThreshold * 1.1f // Higher threshold in morning (less sensitive)
            in 10..16 -> currentThreshold // Normal threshold during day
            in 17..21 -> currentThreshold * 0.95f // Slightly lower in evening
            else -> currentThreshold
        }
    }
}