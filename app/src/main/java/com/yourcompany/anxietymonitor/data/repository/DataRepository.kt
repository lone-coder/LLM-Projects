package com.yourcompany.anxietymonitor.data.repository

import com.yourcompany.anxietymonitor.domain.models.*
import kotlinx.coroutines.flow.Flow

interface DataRepository {

    suspend fun saveBiometricReading(reading: BiometricReading)
    suspend fun saveBiometricReadings(readings: List<BiometricReading>)
    suspend fun saveAnxietyEvent(event: AnxietyEvent): Long
    suspend fun saveBaseline(baseline: UserBaseline)
    suspend fun saveUserFeedback(feedback: UserFeedback)

    suspend fun getRecentReadings(count: Int): List<BiometricReading>
    suspend fun getBaseline(timeOfDay: Int): UserBaseline?
    suspend fun getAnxietyEvents(since: Long): List<AnxietyEvent>
    suspend fun getUserFeedback(since: Long): List<UserFeedback>

    fun observeRecentReadings(since: Long): Flow<List<BiometricReading>>
    fun observeAnxietyEvents(since: Long): Flow<List<AnxietyEvent>>

    suspend fun getReadingsInTimeRange(start: Long, end: Long): List<BiometricReading>
    suspend fun cleanupOldData(cutoffTime: Long)
    suspend fun getAnxietyEventCount(since: Long): Int
    suspend fun getFeedbackCount(): Int
    suspend fun getReadingCount(): Int
    suspend fun getAccuracyRate(since: Long): Float

}