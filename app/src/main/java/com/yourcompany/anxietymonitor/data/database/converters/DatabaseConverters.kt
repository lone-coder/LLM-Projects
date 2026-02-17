package com.yourcompany.anxietymonitor.data.database.converters

import androidx.room.TypeConverter
import java.time.Instant

class DatabaseConverters {
    @TypeConverter
    fun fromTimestamp(value: Long?): Instant? {
        return value?.let { Instant.ofEpochMilli(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Instant?): Long? {
        return date?.toEpochMilli()
    }
}