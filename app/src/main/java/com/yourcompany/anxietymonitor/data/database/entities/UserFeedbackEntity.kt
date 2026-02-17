package com.yourcompany.anxietymonitor.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_feedback")
data class UserFeedbackEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val anxietyEventId: String,
    val wasCorrect: Boolean,
    val userNotes: String?,
    val actualAnxietyLevel: Int?,
    val contextNotes: String?,
    val feedbackType: String
)