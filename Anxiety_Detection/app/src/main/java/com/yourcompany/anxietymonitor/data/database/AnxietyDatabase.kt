package com.yourcompany.anxietymonitor.data.database

import androidx.room.*
import com.yourcompany.anxietymonitor.data.database.entities.*
import com.yourcompany.anxietymonitor.data.database.daos.*
import com.yourcompany.anxietymonitor.data.database.converters.DatabaseConverters

@Database(
    entities = [
        BiometricReadingEntity::class,
        AnxietyEventEntity::class,
        UserBaselineEntity::class,
        UserFeedbackEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(DatabaseConverters::class)
abstract class AnxietyDatabase : RoomDatabase() {
    abstract fun biometricDao(): BiometricDao
    abstract fun anxietyEventDao(): AnxietyEventDao
    abstract fun userFeedbackDao(): UserFeedbackDao
}