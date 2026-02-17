package com.yourcompany.anxietymonitor.data.database.daos

import androidx.room.*
import com.yourcompany.anxietymonitor.data.database.entities.*

@Dao
interface UserFeedbackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFeedback(feedback: UserFeedbackEntity)

    @Query("SELECT * FROM user_feedback WHERE timestamp >= :since ORDER BY timestamp DESC")
    suspend fun getFeedbackSince(since: Long): List<UserFeedbackEntity>

    @Query("SELECT * FROM user_feedback WHERE anxietyEventId = :eventId LIMIT 1")
    suspend fun getFeedbackForEvent(eventId: String): UserFeedbackEntity?

    @Query("SELECT AVG(CASE WHEN wasCorrect = 1 THEN 1.0 ELSE 0.0 END) FROM user_feedback WHERE timestamp >= :since")
    suspend fun getAccuracyRate(since: Long): Float

    @Query("SELECT COUNT(*) FROM user_feedback WHERE wasCorrect = 0 AND timestamp >= :since")
    suspend fun getFalsePositiveCount(since: Long): Int

    @Query("SELECT COUNT(*) FROM user_feedback")
    suspend fun getFeedbackCount(): Int
}