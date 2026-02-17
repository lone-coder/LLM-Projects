// ============================================================================
// app/src/main/java/com/yourcompany/anxietymonitor/utils/TimeUtils.kt
// ============================================================================

package com.yourcompany.anxietymonitor.utils

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.*

object TimeUtils {

    fun getTimeOfDayCategory(timestamp: Long): TimeOfDay {
        val hour = getHourOfDay(timestamp)
        return when (hour) {
            in Constants.MORNING_START_HOUR until Constants.MORNING_END_HOUR -> TimeOfDay.MORNING
            in Constants.AFTERNOON_START_HOUR until Constants.AFTERNOON_END_HOUR -> TimeOfDay.AFTERNOON
            in Constants.EVENING_START_HOUR until Constants.EVENING_END_HOUR -> TimeOfDay.EVENING
            else -> TimeOfDay.NIGHT
        }
    }

    fun getHourOfDay(timestamp: Long): Int {
        return LocalTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault()).hour
    }

    fun isNightTime(timestamp: Long): Boolean {
        val hour = getHourOfDay(timestamp)
        return hour >= Constants.NIGHT_START_HOUR || hour < Constants.NIGHT_END_HOUR
    }

    fun getTodayStart(): Long {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    fun getWeekStart(): Long {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    fun getMonthStart(): Long {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    fun formatDuration(durationMs: Long): String {
        val seconds = durationMs / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            days > 0 -> "${days}d ${hours % 24}h"
            hours > 0 -> "${hours}h ${minutes % 60}m"
            minutes > 0 -> "${minutes}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
    }
}

enum class TimeOfDay {
    MORNING, AFTERNOON, EVENING, NIGHT
}