package com.yourcompany.anxietymonitor.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "anxiety_events")
data class AnxietyEventEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val type: String,
    val confidence: Float,
    val heartRate: Int,
    val baselineHeartRate: Int,
    val hrv: Double?,
    val baselineHrv: Double?,
    val temperature: Float?,
    val baselineTemperature: Float?,
    val activityLevel: String,
    val biometricSource: String,
    val detectionMethod: String,
    val wasPromptShown: Boolean = false,
    val userFeedbackReceived: Boolean = false
)