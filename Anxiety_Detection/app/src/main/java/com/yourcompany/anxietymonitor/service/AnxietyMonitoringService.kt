package com.yourcompany.anxietymonitor.service
import com.yourcompany.anxietymonitor.domain.interfaces.BiometricDataSource
import com.yourcompany.anxietymonitor.data.repository.DataRepository
import com.yourcompany.anxietymonitor.domain.engine.AnxietyDetectionEngine
import com.yourcompany.anxietymonitor.domain.engine.BaselineEngine
import com.yourcompany.anxietymonitor.service.WearableDataSyncService
import com.yourcompany.anxietymonitor.domain.models.*
import com.yourcompany.anxietymonitor.data.health.HybridBiometricDataSource
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton
import java.time.LocalTime
import java.time.ZoneId
import java.time.Instant

@Singleton
class AnxietyMonitoringService @Inject constructor(
    private val dataSource: BiometricDataSource,  // Now using HybridBiometricDataSource via DI
    private val repository: DataRepository,
    private val detectionEngine: AnxietyDetectionEngine,
    private val baselineEngine: BaselineEngine,
    private val wearableSync: WearableDataSyncService
) {

    companion object {
        private const val TAG = "AnxietyMonitoringService"
        private const val BASELINE_UPDATE_INTERVAL = 60 // readings
    }

    private var isMonitoring = false
    private var readingCount = 0
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Real-time anxiety event stream
    private val _anxietyEvents = MutableSharedFlow<AnxietyEvent>(
        replay = 0, // Don't replay old events
        extraBufferCapacity = 10 // Buffer recent events if collector is slow
    )

    // Public Flow for observers to collect anxiety events
    fun observeAnxietyEvents(): Flow<AnxietyEvent> = _anxietyEvents.asSharedFlow()

    suspend fun startMonitoring(): Boolean {
        if (isMonitoring) {
            Log.w(TAG, "Monitoring already active")
            return true
        }

        return try {
            // Initialize all components
            val dataSourceReady = dataSource.initialize()
            val detectionEngineReady = detectionEngine.initialize()
            val baselineEngineReady = baselineEngine.initialize()
            val wearableSyncReady = wearableSync.initialize()

            if (!dataSourceReady) {
                Log.e(TAG, "Failed to initialize data source")
                return false
            }

            Log.d(TAG, "Starting anxiety monitoring service...")
            Log.d(TAG, "Detection engine ready: $detectionEngineReady")
            Log.d(TAG, "Baseline engine ready: $baselineEngineReady")
            Log.d(TAG, "Wearable sync ready: $wearableSyncReady")
            Log.d(TAG, "ML enabled: ${detectionEngine.isMLEnabled()}")

            isMonitoring = true
            startBiometricMonitoring()

            Log.d(TAG, "Anxiety monitoring started successfully")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start monitoring", e)
            false
        }
    }

    private fun startBiometricMonitoring() {
        serviceScope.launch {
            try {
                dataSource.observeRealTimeData()
                    .flowOn(Dispatchers.IO)
                    .collect { reading ->
                        if (isMonitoring) {
                            processReading(reading)

                            // Also send to watch if it's from phone sensors
                            if (reading.source != BiometricSource.GALAXY_WATCH) {
                                wearableSync.sendBiometricReading(reading)
                            }
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error in biometric monitoring", e)
                // Consider restarting monitoring or notifying user
            }
        }
    }

    private suspend fun processReading(reading: BiometricReading) {
        try {
            // Save reading to repository
            repository.saveBiometricReading(reading)
            readingCount++

            // Get appropriate baseline for current time
            val currentHour = LocalTime.ofInstant(
                Instant.ofEpochMilli(reading.timestamp),
                ZoneId.systemDefault()
            ).hour

            val baseline = baselineEngine.getBaseline(currentHour)

            if (baseline != null) {
                // Perform anxiety detection
                val recentReadings = repository.getRecentReadings(20)
                val isNightTime = currentHour in 22..23 || currentHour in 0..6

                val anxietyEvent = detectionEngine.detectAnxietyEvent(
                    current = reading,
                    baseline = baseline,
                    recentReadings = recentReadings,
                    isNightTime = isNightTime
                )

                // If anxiety detected, save and emit real-time event
                if (anxietyEvent != null) {
                    val eventId: Long = repository.saveAnxietyEvent(anxietyEvent)
                    val savedEvent = anxietyEvent.copy(id = eventId.toString())

                    Log.d(TAG, "Anxiety event detected: ${savedEvent.type} (confidence: ${savedEvent.confidence})")
                    Log.d(TAG, "Source: ${reading.source}, Method: ${savedEvent.detectionMethod}")

                    // Emit real-time event
                    _anxietyEvents.emit(savedEvent)
                }

                // Update baselines periodically
                if (readingCount % BASELINE_UPDATE_INTERVAL == 0) {
                    updateBaselinesIfNeeded(currentHour, recentReadings)
                }
            } else {
                Log.d(TAG, "No baseline available for hour $currentHour, skipping detection")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing reading", e)
        }
    }

    private suspend fun updateBaselinesIfNeeded(currentHour: Int, recentReadings: List<BiometricReading>) {
        try {
            val existing = baselineEngine.getBaseline(currentHour)
            if (existing != null && recentReadings.size >= 10) {
                val hourlyReadings = recentReadings.filter { reading ->
                    val hour = LocalTime.ofInstant(
                        Instant.ofEpochMilli(reading.timestamp),
                        ZoneId.systemDefault()
                    ).hour
                    hour == currentHour
                }

                if (hourlyReadings.size >= 10) {
                    baselineEngine.updateBaseline(existing, hourlyReadings)
                    Log.d(TAG, "Updated baseline for hour $currentHour with ${hourlyReadings.size} readings")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating baselines", e)
        }
    }

    suspend fun stopMonitoring() {
        if (!isMonitoring) return

        isMonitoring = false
        serviceScope.coroutineContext.cancelChildren()

        Log.d(TAG, "Anxiety monitoring stopped")
    }

    fun isMonitoring(): Boolean = isMonitoring

    suspend fun getMonitoringStats(): Map<String, Any> {
        return try {
            val detectionStats = detectionEngine.getDetectionStats()
            val recentReadings = repository.getReadingCount()
            val todayStart = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
            val todayEvents = repository.getAnxietyEventCount(todayStart)

            // Get data source info
            val dataSourceInfo = when (dataSource) {
                is HybridBiometricDataSource -> {
                    mapOf(
                        "type" to "Hybrid",
                        "tier" to dataSource.getActiveTier().name,
                        "accuracy" to dataSource.getExpectedAccuracy()
                    )
                }
                else -> mapOf("type" to dataSource.getDeviceInfo().name)
            }

            mapOf(
                "is_monitoring" to isMonitoring,
                "total_readings" to recentReadings,
                "today_events" to todayEvents,
                "detection_stats" to detectionStats,
                "ml_enabled" to detectionEngine.isMLEnabled(),
                "data_source" to dataSourceInfo,
                "wearable_sync" to wearableSync.getSyncStatus()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting monitoring stats", e)
            emptyMap()
        }
    }

    // Cleanup when service is destroyed
    fun cleanup() {
        serviceScope.cancel()
        wearableSync.cleanup()
    }
}