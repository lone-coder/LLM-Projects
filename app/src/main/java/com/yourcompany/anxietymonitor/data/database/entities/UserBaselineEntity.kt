package com.yourcompany.anxietymonitor.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_baselines")
data class UserBaselineEntity(
    @PrimaryKey val timeOfDay: Int,
    val avgHeartRate: Double,
    val avgHrv: Double,
    val avgTemperature: Double?,
    val dataPoints: Int,
    val lastUpdated: Long,
    val deviceType: String
)