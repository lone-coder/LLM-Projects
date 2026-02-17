package com.yourcompany.anxietymonitor.data.database.daos

import androidx.room.*
import com.yourcompany.anxietymonitor.data.database.entities.AnxietyEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AnxietyEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: AnxietyEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvents(events: List<AnxietyEventEntity>)

    @Query("SELECT * FROM anxiety_events ORDER BY timestamp DESC LIMIT :count")
    suspend fun getRecentEvents(count: Int): List<AnxietyEventEntity>

    @Query("SELECT * FROM anxiety_events WHERE timestamp >= :since ORDER BY timestamp DESC")
    suspend fun getEventsSince(since: Long): List<AnxietyEventEntity>

    @Query("SELECT * FROM anxiety_events WHERE timestamp >= :since ORDER BY timestamp DESC")
    fun observeEventsSince(since: Long): Flow<List<AnxietyEventEntity>>

    @Query("SELECT * FROM anxiety_events WHERE id = :eventId LIMIT 1")
    suspend fun getEvent(eventId: String): AnxietyEventEntity?

    @Update
    suspend fun updateEvent(event: AnxietyEventEntity)

    @Query("UPDATE anxiety_events SET wasPromptShown = 1 WHERE id = :eventId")
    suspend fun markPromptShown(eventId: String)

    @Query("UPDATE anxiety_events SET userFeedbackReceived = 1 WHERE id = :eventId")
    suspend fun markFeedbackReceived(eventId: String)

    @Query("SELECT COUNT(*) FROM anxiety_events WHERE timestamp >= :since")
    suspend fun getEventCount(since: Long): Int

    @Query("SELECT COUNT(*) FROM anxiety_events WHERE timestamp >= :since AND userFeedbackReceived = 1")
    suspend fun getFeedbackReceivedCount(since: Long): Int

    @Query("DELETE FROM anxiety_events WHERE timestamp < :cutoff")
    suspend fun deleteOldEvents(cutoff: Long)

    @Query("SELECT AVG(confidence) FROM anxiety_events WHERE timestamp >= :since")
    suspend fun getAverageConfidence(since: Long): Float?

    @Query("SELECT * FROM anxiety_events WHERE type = :type AND timestamp >= :since ORDER BY timestamp DESC")
    suspend fun getEventsByType(type: String, since: Long): List<AnxietyEventEntity>
}