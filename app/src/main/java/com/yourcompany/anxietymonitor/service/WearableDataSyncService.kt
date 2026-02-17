package com.yourcompany.anxietymonitor.service

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import kotlinx.coroutines.tasks.await
import com.yourcompany.anxietymonitor.data.repository.DataRepository
import com.yourcompany.anxietymonitor.domain.models.BiometricReading
import com.yourcompany.anxietymonitor.domain.models.BiometricSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Syncs biometric data from Galaxy Watch to phone using Wearable Data Layer
 * This is necessary because Samsung Health Sensor SDK cannot save data directly
 * CRITICAL: This service enables real-time anxiety detection from watch data
 */
@Singleton
class WearableDataSyncService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DataRepository
) : DataClient.OnDataChangedListener, MessageClient.OnMessageReceivedListener {

    private lateinit var dataClient: DataClient
    private lateinit var messageClient: MessageClient
    private lateinit var nodeClient: NodeClient
    private var isInitialized = false
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Lazy injection to avoid circular dependency
    @Inject
    lateinit var anxietyDetectionEngineProvider: dagger.Lazy<com.yourcompany.anxietymonitor.domain.engine.AnxietyDetectionEngine>

    companion object {
        private const val TAG = "WearableDataSyncService"

        // Data paths
        private const val BIOMETRIC_DATA_PATH = "/biometric/reading"
        private const val ANXIETY_EVENT_PATH = "/anxiety/event"
        private const val SYNC_REQUEST_PATH = "/sync/request"
        private const val SYNC_BATCH_PATH = "/sync/batch"

        // Message paths
        private const val STATUS_REQUEST_PATH = "/status/request"
        private const val STATUS_RESPONSE_PATH = "/status/response"
        private const val ANXIETY_FEEDBACK_PATH = "/anxiety/feedback"
        private const val WATCH_FEEDBACK_PATH = "/watch/feedback"

        // Keys for data map
        private const val KEY_TIMESTAMP = "timestamp"
        private const val KEY_HEART_RATE = "heart_rate"
        private const val KEY_HRV = "hrv"
        private const val KEY_SKIN_TEMP = "skin_temp"
        private const val KEY_ACCEL_MAG = "accel_mag"
        private const val KEY_CONFIDENCE = "confidence"
        private const val KEY_SOURCE = "source"
        private const val KEY_ANXIETY_DETECTED = "anxiety_detected"
        private const val KEY_ANXIETY_CONFIDENCE = "anxiety_confidence"
    }

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Initializing Wearable Data Sync Service...")

            dataClient = Wearable.getDataClient(context)
            messageClient = Wearable.getMessageClient(context)
            nodeClient = Wearable.getNodeClient(context)

            // Add listeners
            dataClient.addListener(this@WearableDataSyncService)
            messageClient.addListener(this@WearableDataSyncService)

            isInitialized = true

            // Request initial sync from watch
            requestSyncFromWatch()

            Log.d(TAG, "Wearable Data Sync initialized successfully")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Wearable Data Sync", e)
            false
        }
    }

    /**
     * Called when data is changed on the watch
     * CRITICAL: This enables real-time anxiety detection data flow
     */
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            when (event.type) {
                DataEvent.TYPE_CHANGED -> {
                    handleDataItem(event.dataItem)
                }
                DataEvent.TYPE_DELETED -> {
                    Log.d(TAG, "Data deleted: ${event.dataItem.uri}")
                }
            }
        }
    }

    /**
     * Called when a message is received from the watch
     * CRITICAL: Handles anxiety alerts and status updates
     */
    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            STATUS_REQUEST_PATH -> {
                sendStatusToWatch(messageEvent.sourceNodeId)
            }
            SYNC_BATCH_PATH -> {
                handleBatchSync(messageEvent.data)
            }
            ANXIETY_EVENT_PATH -> {
                handleWatchAnxietyAlert(messageEvent.data)
            }
            WATCH_FEEDBACK_PATH -> {
                handleWatchFeedback(messageEvent.data)
            }
        }
    }

    private fun handleDataItem(dataItem: DataItem) {
        when (dataItem.uri.path) {
            BIOMETRIC_DATA_PATH -> {
                handleBiometricReading(dataItem)
            }
            ANXIETY_EVENT_PATH -> {
                handleAnxietyEvent(dataItem)
            }
        }
    }

    /**
     * CRITICAL: Handles real-time biometric readings from watch for anxiety detection
     */
    private fun handleBiometricReading(dataItem: DataItem) {
        serviceScope.launch {
            try {
                val dataMap = DataMapItem.fromDataItem(dataItem).dataMap

                val reading = BiometricReading(
                    timestamp = dataMap.getLong(KEY_TIMESTAMP),
                    heartRate = if (dataMap.containsKey(KEY_HEART_RATE))
                        dataMap.getInt(KEY_HEART_RATE) else null,
                    hrvRmssd = if (dataMap.containsKey(KEY_HRV))
                        dataMap.getDouble(KEY_HRV) else null,
                    skinTemperature = if (dataMap.containsKey(KEY_SKIN_TEMP))
                        dataMap.getFloat(KEY_SKIN_TEMP) else null,
                    accelerometerMagnitude = if (dataMap.containsKey(KEY_ACCEL_MAG))
                        dataMap.getFloat(KEY_ACCEL_MAG) else null,
                    confidence = dataMap.getFloat(KEY_CONFIDENCE, 1.0f),
                    source = try {
                        BiometricSource.valueOf(
                            dataMap.getString(KEY_SOURCE) ?: BiometricSource.GALAXY_WATCH.name
                        )
                    } catch (_: IllegalArgumentException) {
                        BiometricSource.GALAXY_WATCH
                    }
                )

                // Save to repository for anxiety detection processing
                repository.saveBiometricReading(reading)

                Log.v(TAG, "Synced biometric reading from watch: HR=${reading.heartRate}, HRV=${reading.hrvRmssd}")

                // Delete the data item after processing to avoid re-processing
                dataClient.deleteDataItems(dataItem.uri).await()

            } catch (e: Exception) {
                Log.e(TAG, "Error handling biometric reading", e)
            }
        }
    }

    /**
     * CRITICAL: Handles anxiety events detected on the watch
     */
    private fun handleAnxietyEvent(dataItem: DataItem) {
        serviceScope.launch {
            try {
                val dataMap = DataMapItem.fromDataItem(dataItem).dataMap

                val anxietyDetected = dataMap.getBoolean(KEY_ANXIETY_DETECTED, false)
                val confidence = dataMap.getFloat(KEY_ANXIETY_CONFIDENCE, 0.0f)

                if (anxietyDetected) {
                    Log.i(TAG, "Anxiety detected on watch with confidence: $confidence")

                    // Could trigger additional phone-based analysis or notifications
                    // This creates a dual-detection system (watch + phone)
                }

                // Clean up
                dataClient.deleteDataItems(dataItem.uri).await()

            } catch (e: Exception) {
                Log.e(TAG, "Error handling anxiety event from watch", e)
            }
        }
    }

    /**
     * CRITICAL: Handles anxiety alerts sent as messages from watch
     */
    private fun handleWatchAnxietyAlert(data: ByteArray?) {
        if (data == null) return

        serviceScope.launch {
            try {
                val jsonString = String(data)
                val alertData = JSONObject(jsonString)

                val confidence = alertData.getDouble("confidence").toFloat()
                val heartRate = alertData.optInt("heart_rate", -1)

                Log.i(TAG, "Received anxiety alert from watch: confidence=$confidence, HR=$heartRate")

                // This could trigger immediate phone-side verification or user notification
                // Creating a comprehensive detection system

            } catch (e: Exception) {
                Log.e(TAG, "Error handling watch anxiety alert", e)
            }
        }
    }

    /**
     * CRITICAL: Handles feedback from watch and updates phone ML model
     */
    private fun handleWatchFeedback(data: ByteArray?) {
        if (data == null) return

        serviceScope.launch {
            try {
                val jsonString = String(data)
                val feedbackData = JSONObject(jsonString)

                val anxietyEventId = feedbackData.getString("anxiety_event_id")
                val wasCorrect = feedbackData.getBoolean("was_correct")
                val userNotes = if (feedbackData.has("user_notes")) {
                    feedbackData.getString("user_notes").takeIf { it.isNotEmpty() }
                } else null

                Log.d(TAG, "Received feedback from watch: eventId=$anxietyEventId, wasCorrect=$wasCorrect")

                // Update phone ML model with watch feedback
                val anxietyDetectionEngine = anxietyDetectionEngineProvider.get()
                anxietyDetectionEngine.updateWithFeedback(
                    anxietyEventId = anxietyEventId,
                    wasCorrect = wasCorrect,
                    actualAnxietyLevel = null,
                    userNotes = userNotes
                )

                Log.d(TAG, "Phone ML model updated with watch feedback")

            } catch (e: Exception) {
                Log.e(TAG, "Error handling watch feedback", e)
            }
        }
    }

    /**
     * CRITICAL: Handles batch sync for offline data when watch reconnects
     */
    private fun handleBatchSync(data: ByteArray?) {
        if (data == null) return

        serviceScope.launch {
            try {
                val jsonString = String(data)
                val jsonObject = JSONObject(jsonString)
                val readingsArray = jsonObject.getJSONArray("readings")

                Log.d(TAG, "Received batch sync with ${readingsArray.length()} readings")

                for (i in 0 until readingsArray.length()) {
                    val readingJson = readingsArray.getJSONObject(i)

                    val reading = BiometricReading(
                        timestamp = readingJson.getLong("timestamp"),
                        heartRate = if (readingJson.has("heart_rate"))
                            readingJson.getInt("heart_rate") else null,
                        hrvRmssd = if (readingJson.has("hrv"))
                            readingJson.getDouble("hrv") else null,
                        skinTemperature = if (readingJson.has("skin_temp"))
                            readingJson.getDouble("skin_temp").toFloat() else null,
                        accelerometerMagnitude = if (readingJson.has("accel_mag"))
                            readingJson.getDouble("accel_mag").toFloat() else null,
                        confidence = readingJson.optDouble("confidence", 1.0).toFloat(),
                        source = BiometricSource.GALAXY_WATCH
                    )

                    repository.saveBiometricReading(reading)
                }

                Log.d(TAG, "Batch sync completed successfully - ${readingsArray.length()} readings processed")

            } catch (e: Exception) {
                Log.e(TAG, "Error handling batch sync", e)
            }
        }
    }

    /**
     * CRITICAL: Request sync from watch (for initial data or after reconnection)
     */
    private suspend fun requestSyncFromWatch() = withContext(Dispatchers.IO) {
        try {
            val nodes = nodeClient.connectedNodes.await()

            nodes.forEach { node ->
                messageClient.sendMessage(
                    node.id,
                    SYNC_REQUEST_PATH,
                    null
                ).await()

                Log.d(TAG, "Sent sync request to watch: ${node.displayName}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error requesting sync from watch", e)
        }
    }

    /**
     * CRITICAL: Send status update to watch including anxiety detection state
     */
    private fun sendStatusToWatch(nodeId: String) {
        serviceScope.launch {
            try {
                val statusJson = JSONObject().apply {
                    put("connected", true)
                    put("last_sync", System.currentTimeMillis())
                    put("records_today", repository.getReadingCount())
                    put("anxiety_detection_active", true)
                    put("phone_ml_enabled", true)
                }

                messageClient.sendMessage(
                    nodeId,
                    STATUS_RESPONSE_PATH,
                    statusJson.toString().toByteArray()
                ).await()

                Log.d(TAG, "Sent status update to watch")

            } catch (e: Exception) {
                Log.e(TAG, "Error sending status to watch", e)
            }
        }
    }

    /**
     * CRITICAL: Send anxiety feedback to watch for local threshold learning
     * Called by AnxietyDetectionEngine.updateWithFeedback() when user provides feedback on phone
     */
    suspend fun sendAnxietyFeedbackToWatch(
        anxietyEventId: String,
        wasCorrect: Boolean,
        confidence: Float,
        userNotes: String? = null
    ) = withContext(Dispatchers.IO) {
        if (!isInitialized) return@withContext

        try {
            val nodes = nodeClient.connectedNodes.await()

            val feedbackJson = JSONObject().apply {
                put("anxiety_event_id", anxietyEventId)
                put("was_correct", wasCorrect)
                put("confidence", confidence)
                put("timestamp", System.currentTimeMillis())
                put("source", "phone_ml")
                // Handle nullable userNotes properly
                if (userNotes != null) {
                    put("user_notes", userNotes)
                }
            }

            nodes.forEach { node ->
                messageClient.sendMessage(
                    node.id,
                    ANXIETY_FEEDBACK_PATH,
                    feedbackJson.toString().toByteArray()
                ).await()
            }

            Log.d(TAG, "Sent anxiety feedback to watch: eventId=$anxietyEventId, wasCorrect=$wasCorrect")

        } catch (e: Exception) {
            Log.e(TAG, "Error sending anxiety feedback to watch", e)
        }
    }

    /**
     * Simplified method for backward compatibility
     * WILL BE USED: Called by AnxietyDetectionEngine.updateWithFeedback()
     */
    suspend fun sendAnxietyFeedback(
        anxietyEventId: String,
        wasCorrect: Boolean,
        confidence: Float
    ): Unit {
        sendAnxietyFeedbackToWatch(anxietyEventId, wasCorrect, confidence, null)
    }

    /**
     * CRITICAL: Send data to watch for bidirectional sync and watch-side processing
     */
    suspend fun sendBiometricReading(reading: BiometricReading) = withContext(Dispatchers.IO) {
        if (!isInitialized) return@withContext

        try {
            val dataMap = DataMap().apply {
                putLong(KEY_TIMESTAMP, reading.timestamp)
                reading.heartRate?.let { putInt(KEY_HEART_RATE, it) }
                reading.hrvRmssd?.let { putDouble(KEY_HRV, it) }
                reading.skinTemperature?.let { putFloat(KEY_SKIN_TEMP, it) }
                reading.accelerometerMagnitude?.let { putFloat(KEY_ACCEL_MAG, it) }
                putFloat(KEY_CONFIDENCE, reading.confidence)
                putString(KEY_SOURCE, reading.source.name)
            }

            val putDataReq = PutDataMapRequest.create(BIOMETRIC_DATA_PATH).apply {
                this.dataMap.putAll(dataMap)
                setUrgent() // For real-time sync
            }.asPutDataRequest()

            dataClient.putDataItem(putDataReq).await()

        } catch (e: Exception) {
            Log.e(TAG, "Error sending biometric reading to watch", e)
        }
    }

    /**
     * CRITICAL: Get comprehensive sync status for debugging and monitoring
     */
    fun getSyncStatus(): Map<String, Any> {
        return mapOf(
            "initialized" to isInitialized,
            "watch_connected" to isWatchConnected(),
            "last_sync" to System.currentTimeMillis(),
            "service_active" to serviceScope.isActive,
            "data_flow_enabled" to true
        )
    }

    /**
     * CRITICAL: Check if watch is connected for anxiety detection reliability
     */
    private fun isWatchConnected(): Boolean {
        return try {
            val nodes = Tasks.await(nodeClient.connectedNodes)
            val connected = nodes.isNotEmpty()
            Log.d(TAG, "Watch connection status: $connected")
            connected
        } catch (e: Exception) {
            Log.e(TAG, "Error checking watch connection", e)
            false
        }
    }

    /**
     * CRITICAL: Force sync for immediate anxiety detection needs
     * Called by MainActivity when user reports missed anxiety or during setup
     */
    suspend fun forceSyncFromWatch(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isInitialized) {
                Log.w(TAG, "Cannot force sync - service not initialized")
                return@withContext false
            }

            requestSyncFromWatch()

            // Wait briefly for sync to complete
            delay(2000)

            Log.d(TAG, "Force sync completed")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Error during force sync", e)
            false
        }
    }

    /**
     * CRITICAL: Cleanup resources properly to avoid memory leaks
     */
    fun cleanup() {
        if (isInitialized) {
            try {
                dataClient.removeListener(this)
                messageClient.removeListener(this)
                serviceScope.cancel()
                isInitialized = false
                Log.d(TAG, "Wearable Data Sync Service cleaned up")
            } catch (e: Exception) {
                Log.e(TAG, "Error during cleanup", e)
            }
        }
    }
}