package com.yourcompany.anxietymonitor.data.database.daos

import androidx.room.*
import com.yourcompany.anxietymonitor.data.database.entities.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BiometricDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReading(reading: BiometricReadingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReadings(readings: List<BiometricReadingEntity>)

    @Query("SELECT * FROM biometric_readings ORDER BY timestamp DESC LIMIT :count")
    suspend fun getRecentReadings(count: Int): List<BiometricReadingEntity>

    @Query("SELECT * FROM biometric_readings WHERE timestamp >= :since ORDER BY timestamp DESC")
    fun getReadingsSince(since: Long): Flow<List<BiometricReadingEntity>>

    @Query("SELECT * FROM biometric_readings WHERE timestamp BETWEEN :start AND :end ORDER BY timestamp ASC")
    suspend fun getReadingsInRange(start: Long, end: Long): List<BiometricReadingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBaseline(baseline: UserBaselineEntity)

    @Query("SELECT * FROM user_baselines WHERE timeOfDay = :timeOfDay LIMIT 1")
    suspend fun getBaseline(timeOfDay: Int): UserBaselineEntity?

    @Query("SELECT * FROM user_baselines ORDER BY timeOfDay")
    suspend fun getAllBaselines(): List<UserBaselineEntity>

    @Query("DELETE FROM biometric_readings WHERE timestamp < :cutoff")
    suspend fun deleteOldReadings(cutoff: Long)

    @Query("SELECT COUNT(*) FROM biometric_readings")
    suspend fun getReadingCount(): Int
}