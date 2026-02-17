package com.yourcompany.anxietymonitor.data.repository
import com.yourcompany.anxietymonitor.data.database.AnxietyDatabase
import com.yourcompany.anxietymonitor.domain.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidDataRepository @Inject constructor(
    database: AnxietyDatabase
) : DataRepository {

    private val biometricDao = database.biometricDao()
    private val anxietyEventDao = database.anxietyEventDao()
    private val userFeedbackDao = database.userFeedbackDao()

    override suspend fun saveBiometricReading(reading: BiometricReading) = withContext(Dispatchers.IO) {
        biometricDao.insertReading(reading.toEntity())
    }

    override suspend fun saveBiometricReadings(readings: List<BiometricReading>) = withContext(Dispatchers.IO) {
        biometricDao.insertReadings(readings.map { it.toEntity() })
    }

    override suspend fun saveAnxietyEvent(event: AnxietyEvent): Long = withContext(Dispatchers.IO) {
        anxietyEventDao.insertEvent(event.toEntity())
    }

    override suspend fun saveBaseline(baseline: UserBaseline) = withContext(Dispatchers.IO) {
        biometricDao.insertBaseline(baseline.toEntity())
    }

    override suspend fun saveUserFeedback(feedback: UserFeedback) = withContext(Dispatchers.IO) {
        userFeedbackDao.insertFeedback(feedback.toEntity())
    }

    override suspend fun getRecentReadings(count: Int): List<BiometricReading> = withContext(Dispatchers.IO) {
        biometricDao.getRecentReadings(count).map { it.toBiometricReading() }
    }

    override suspend fun getBaseline(timeOfDay: Int): UserBaseline? = withContext(Dispatchers.IO) {
        biometricDao.getBaseline(timeOfDay)?.toUserBaseline()
    }

    override suspend fun getAnxietyEvents(since: Long): List<AnxietyEvent> = withContext(Dispatchers.IO) {
        anxietyEventDao.getEventsSince(since).map { it.toAnxietyEvent() }
    }

    override suspend fun getUserFeedback(since: Long): List<UserFeedback> = withContext(Dispatchers.IO) {
        userFeedbackDao.getFeedbackSince(since).map { it.toUserFeedback() }
    }

    override fun observeRecentReadings(since: Long): Flow<List<BiometricReading>> {
        return biometricDao.getReadingsSince(since).map { entities ->
            entities.map { it.toBiometricReading() }
        }
    }

    override fun observeAnxietyEvents(since: Long): Flow<List<AnxietyEvent>> {
        return anxietyEventDao.observeEventsSince(since).map { entities ->
            entities.map { it.toAnxietyEvent() }
        }
    }

    override suspend fun getReadingsInTimeRange(start: Long, end: Long): List<BiometricReading> = withContext(Dispatchers.IO) {
        biometricDao.getReadingsInRange(start, end).map { it.toBiometricReading() }
    }

    override suspend fun cleanupOldData(cutoffTime: Long) = withContext(Dispatchers.IO) {
        biometricDao.deleteOldReadings(cutoffTime)
    }

    override suspend fun getAnxietyEventCount(since: Long): Int = withContext(Dispatchers.IO) {
        anxietyEventDao.getEventCount(since) // FIXED: Changed from getEventCountSince
    }

    override suspend fun getFeedbackCount(): Int = withContext(Dispatchers.IO) {
        userFeedbackDao.getFeedbackCount()
    }

    override suspend fun getReadingCount(): Int = withContext(Dispatchers.IO) {
        biometricDao.getReadingCount()
    }

    override suspend fun getAccuracyRate(since: Long): Float = withContext(Dispatchers.IO) {
        userFeedbackDao.getAccuracyRate(since)
    }
}

// Extension functions for entity conversion
private fun BiometricReading.toEntity() = com.yourcompany.anxietymonitor.data.database.entities.BiometricReadingEntity(
    id = "${timestamp}_${source}_${heartRate ?: "null"}",
    timestamp = timestamp,
    heartRate = heartRate,
    hrvRmssd = hrvRmssd,
    skinTemperature = skinTemperature,
    accelerometerMagnitude = accelerometerMagnitude,
    confidence = confidence,
    source = source.name
)

private fun com.yourcompany.anxietymonitor.data.database.entities.BiometricReadingEntity.toBiometricReading() = BiometricReading(
    timestamp = timestamp,
    heartRate = heartRate,
    hrvRmssd = hrvRmssd,
    skinTemperature = skinTemperature,
    accelerometerMagnitude = accelerometerMagnitude,
    confidence = confidence,
    source = BiometricSource.valueOf(source)
)

private fun AnxietyEvent.toEntity() = com.yourcompany.anxietymonitor.data.database.entities.AnxietyEventEntity(
    id = id,
    timestamp = timestamp,
    type = type.name,
    confidence = confidence,
    heartRate = heartRate,
    baselineHeartRate = baselineHeartRate,
    hrv = hrv,
    baselineHrv = baselineHrv,
    temperature = temperature,
    baselineTemperature = baselineTemperature,
    activityLevel = activityLevel.name,
    biometricSource = biometricSource.name,
    detectionMethod = detectionMethod
)

private fun com.yourcompany.anxietymonitor.data.database.entities.AnxietyEventEntity.toAnxietyEvent() = AnxietyEvent(
    id = id,
    timestamp = timestamp,
    type = AnxietyType.valueOf(type),
    confidence = confidence,
    heartRate = heartRate,
    baselineHeartRate = baselineHeartRate,
    hrv = hrv,
    baselineHrv = baselineHrv,
    temperature = temperature,
    baselineTemperature = baselineTemperature,
    activityLevel = ActivityLevel.valueOf(activityLevel),
    biometricSource = BiometricSource.valueOf(biometricSource),
    detectionMethod = detectionMethod
)

private fun UserBaseline.toEntity() = com.yourcompany.anxietymonitor.data.database.entities.UserBaselineEntity(
    timeOfDay = timeOfDay,
    avgHeartRate = avgHeartRate,
    avgHrv = avgHrv,
    avgTemperature = avgTemperature,
    dataPoints = dataPoints,
    lastUpdated = lastUpdated,
    deviceType = deviceType.name
)

private fun com.yourcompany.anxietymonitor.data.database.entities.UserBaselineEntity.toUserBaseline() = UserBaseline(
    timeOfDay = timeOfDay,
    avgHeartRate = avgHeartRate,
    avgHrv = avgHrv,
    avgTemperature = avgTemperature,
    dataPoints = dataPoints,
    lastUpdated = lastUpdated,
    deviceType = BiometricSource.valueOf(deviceType)
)

private fun UserFeedback.toEntity() = com.yourcompany.anxietymonitor.data.database.entities.UserFeedbackEntity(
    id = id,
    timestamp = timestamp,
    anxietyEventId = anxietyEventId,
    wasCorrect = wasCorrect,
    userNotes = userNotes,
    actualAnxietyLevel = actualAnxietyLevel,
    contextNotes = contextNotes,
    feedbackType = feedbackType.name
)

private fun com.yourcompany.anxietymonitor.data.database.entities.UserFeedbackEntity.toUserFeedback() = UserFeedback(
    id = id,
    timestamp = timestamp,
    anxietyEventId = anxietyEventId,
    wasCorrect = wasCorrect,
    userNotes = userNotes,
    actualAnxietyLevel = actualAnxietyLevel,
    contextNotes = contextNotes,
    feedbackType = FeedbackType.valueOf(feedbackType)
)