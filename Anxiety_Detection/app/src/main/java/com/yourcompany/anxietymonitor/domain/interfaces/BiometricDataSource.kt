// Biometric datasource.kt
package com.yourcompany.anxietymonitor.domain.interfaces

import com.yourcompany.anxietymonitor.domain.models.BiometricReading
import com.yourcompany.anxietymonitor.domain.models.DeviceInfo  // CHANGED: moved to domain models
import kotlinx.coroutines.flow.Flow

interface BiometricDataSource {
    suspend fun initialize(): Boolean
    suspend fun startStreaming(): Boolean
    suspend fun stopStreaming()
    fun isConnected(): Boolean
    fun getDeviceInfo(): DeviceInfo
    fun observeRealTimeData(): Flow<BiometricReading>
}