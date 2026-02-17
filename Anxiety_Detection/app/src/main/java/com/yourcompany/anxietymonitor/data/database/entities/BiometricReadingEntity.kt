package com.yourcompany.anxietymonitor.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "biometric_readings")
data class BiometricReadingEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val heartRate: Int?,
    val hrvRmssd: Double?,
    val skinTemperature: Float?,
    val accelerometerMagnitude: Float?,
    val confidence: Float,
    val source: String
)
