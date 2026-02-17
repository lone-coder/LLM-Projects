package com.yourcompany.anxietymonitor.data.health

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.yourcompany.anxietymonitor.domain.interfaces.BiometricDataSource
import com.yourcompany.anxietymonitor.domain.models.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hybrid data source that intelligently combines Samsung Sensor SDK and Health Connect
 * Provides the best available data quality based on device capabilities
 */
@Singleton
class HybridBiometricDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val samsungSensorSource: AndroidSamsungSensorDataSource,
    private val healthConnectSource: AndroidHealthConnectDataSource
) : BiometricDataSource {

    private var primarySource: BiometricDataSource? = null
    private var secondarySource: BiometricDataSource? = null
    private var activeTier: DataSourceTier = DataSourceTier.UNKNOWN

    private val _combinedFlow = MutableSharedFlow<BiometricReading>(
        replay = 0,
        extraBufferCapacity = 10
    )

    private var isInitialized = false
    private var collectionJob: Job? = null

    companion object {
        private const val TAG = "HybridBiometricDataSource"
        private const val SAMSUNG_HEALTH_PACKAGE = "com.sec.android.app.shealth"
        private const val QUALITY_CHECK_INTERVAL_MS = 30_000L // 30 seconds
    }

    enum class DataSourceTier {
        SAMSUNG_SDK,      // Tier 1: Best quality with IBI, real-time temp
        HEALTH_CONNECT,   // Tier 2: Good quality, wider compatibility
        UNKNOWN
    }

    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Initializing hybrid biometric data source...")

            // Determine best available data source
            val tier = determineDataSourceTier()
            activeTier = tier

            when (tier) {
                DataSourceTier.SAMSUNG_SDK -> {
                    Log.d(TAG, "Using Samsung Sensor SDK as primary source")
                    val samsungInit = samsungSensorSource.initialize()
                    if (samsungInit) {
                        primarySource = samsungSensorSource
                        // Also init Health Connect for historical data
                        val healthInit = healthConnectSource.initialize()
                        if (healthInit) {
                            secondarySource = healthConnectSource
                        }
                    } else {
                        // Fallback to Health Connect
                        Log.w(TAG, "Samsung SDK init failed, falling back to Health Connect")
                        return@withContext initializeHealthConnectOnly()
                    }
                }

                DataSourceTier.HEALTH_CONNECT -> {
                    Log.d(TAG, "Using Health Connect as primary source")
                    return@withContext initializeHealthConnectOnly()
                }

                DataSourceTier.UNKNOWN -> {
                    Log.e(TAG, "No compatible data sources found")
                    return@withContext false
                }
            }

            isInitialized = primarySource != null

            if (isInitialized) {
                startQualityMonitoring()
            }

            Log.d(TAG, "Hybrid initialization complete. Primary: ${primarySource?.getDeviceInfo()?.name}")
            isInitialized

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize hybrid data source", e)
            false
        }
    }

    private suspend fun initializeHealthConnectOnly(): Boolean {
        val healthInit = healthConnectSource.initialize()
        if (healthInit) {
            primarySource = healthConnectSource
            secondarySource = null
            activeTier = DataSourceTier.HEALTH_CONNECT
        }
        return healthInit
    }

    private fun determineDataSourceTier(): DataSourceTier {
        return when {
            isSamsungDevice() && isSamsungHealthInstalled() -> {
                Log.d(TAG, "Samsung device with Samsung Health detected")
                DataSourceTier.SAMSUNG_SDK
            }
            isHealthConnectAvailable() -> {
                Log.d(TAG, "Health Connect available")
                DataSourceTier.HEALTH_CONNECT
            }
            else -> {
                Log.w(TAG, "No compatible health data sources found")
                DataSourceTier.UNKNOWN
            }
        }
    }

    private fun isSamsungDevice(): Boolean {
        val manufacturer = android.os.Build.MANUFACTURER.lowercase()
        return manufacturer == "samsung"
    }

    private fun isSamsungHealthInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(SAMSUNG_HEALTH_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun isHealthConnectAvailable(): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.google.android.apps.healthdata", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    override suspend fun startStreaming(): Boolean {
        if (!isInitialized) {
            Log.w(TAG, "Cannot start streaming - not initialized")
            return false
        }

        return try {
            // Start primary source
            val primaryStarted = primarySource?.startStreaming() == true

            // Start secondary source if available (for fallback/comparison)
            secondarySource?.startStreaming()

            if (primaryStarted) {
                startDataCollection()
            }

            primaryStarted

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start streaming", e)
            false
        }
    }

    private fun startDataCollection() {
        collectionJob?.cancel()
        collectionJob = CoroutineScope(Dispatchers.IO).launch {
            // Collect from primary source
            primarySource?.observeRealTimeData()?.collect { reading ->
                // Enhance reading with secondary source if needed
                val enhancedReading = enhanceReading(reading)
                _combinedFlow.emit(enhancedReading)
            }
        }
    }

    private fun enhanceReading(reading: BiometricReading): BiometricReading {
        // If primary source is missing some data, try to get it from secondary
        // Future enhancement: could merge data from multiple sources
        return reading
    }

    private fun startQualityMonitoring() {
        CoroutineScope(Dispatchers.IO).launch {
            while (isInitialized) {
                delay(QUALITY_CHECK_INTERVAL_MS)
                checkDataQuality()
            }
        }
    }

    private fun checkDataQuality() {
        // Monitor data quality and switch sources if needed
        val primaryStatus = primarySource?.isConnected() == true

        if (!primaryStatus && secondarySource != null) {
            Log.w(TAG, "Primary source disconnected, switching to secondary")
            val temp = primarySource
            primarySource = secondarySource
            secondarySource = temp

            // Restart data collection with new primary
            startDataCollection()
        }
    }

    override suspend fun stopStreaming() {
        collectionJob?.cancel()
        primarySource?.stopStreaming()
        secondarySource?.stopStreaming()
    }

    override fun isConnected(): Boolean {
        return primarySource?.isConnected() == true
    }

    override fun getDeviceInfo(): DeviceInfo {
        return primarySource?.getDeviceInfo() ?: DeviceInfo(
            name = "No Device",
            type = BiometricSource.UNKNOWN,
            capabilities = emptySet()
        )
    }

    override fun observeRealTimeData(): Flow<BiometricReading> = _combinedFlow.asSharedFlow()

    /**
     * Get connection status including both sources
     */
    fun getDetailedStatus(): Map<String, Any> {
        val samsungStatus = if (activeTier == DataSourceTier.SAMSUNG_SDK) {
            mapOf(
                "connected" to samsungSensorSource.isConnected(),
                "device_info" to samsungSensorSource.getDeviceInfo().name
            )
        } else null

        val healthConnectStatus = mapOf(
            "connected" to healthConnectSource.isConnected(),
            "device_info" to healthConnectSource.getDeviceInfo().name
        )

        return mapOf(
            "active_tier" to activeTier.name,
            "primary_source" to (primarySource?.getDeviceInfo()?.name ?: "None"),
            "secondary_source" to (secondarySource?.getDeviceInfo()?.name ?: "None"),
            "samsung_sensor_status" to (samsungStatus ?: "Not available"),
            "health_connect_status" to healthConnectStatus,
            "is_samsung_device" to isSamsungDevice(),
            "samsung_health_installed" to isSamsungHealthInstalled()
        )
    }

    /**
     * Get the active data source tier
     */
    fun getActiveTier(): DataSourceTier = activeTier

    /**
     * Get expected accuracy based on active tier
     */
    fun getExpectedAccuracy(): Float {
        return when (activeTier) {
            DataSourceTier.SAMSUNG_SDK -> 0.95f  // Highest accuracy with IBI and real-time temp
            DataSourceTier.HEALTH_CONNECT -> 0.75f  // Good accuracy but less real-time
            DataSourceTier.UNKNOWN -> 0.0f
        }
    }

    /**
     * Get capabilities of the current configuration
     */
    fun getCapabilities(): Set<SensorCapability> {
        val capabilities = mutableSetOf<SensorCapability>()

        primarySource?.getDeviceInfo()?.capabilities?.let { capabilities.addAll(it) }
        secondarySource?.getDeviceInfo()?.capabilities?.let { capabilities.addAll(it) }

        return capabilities
    }
}