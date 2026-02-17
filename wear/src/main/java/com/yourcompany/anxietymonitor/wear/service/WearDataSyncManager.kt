package com.yourcompany.anxietymonitor.wear.service

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import com.yourcompany.anxietymonitor.wear.data.WearBiometricReading
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

class WearDataSyncManager(private val context: Context) {

    private lateinit var dataClient: DataClient
    private lateinit var messageClient: MessageClient
    private lateinit var nodeClient: NodeClient
    private lateinit var capabilityClient: CapabilityClient

    private val batchBuffer = mutableListOf<WearBiometricReading>()
    private var phoneNodeId: String? = null

    private val _connectionStatus = MutableStateFlow(ConnectionStatus())
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus

    companion object {
        private const val TAG = "WearDataSyncManager"

        // Data paths
        private const val BIOMETRIC_DATA_PATH = "/biometric/reading"
        private const val ANXIETY_EVENT_PATH = "/anxiety/event"
        private const val SYNC_REQUEST_PATH = "/sync/request"
        private const val SYNC_BATCH_PATH = "/sync/batch"

        // Message paths
        private const val STATUS_REQUEST_PATH = "/status/request"
        private const val STATUS_RESPONSE_PATH = "/status/response"

        // Configuration
        private const val BATCH_SIZE = 10
        private const val BATCH_SEND_INTERVAL_MS = 30000L // 30 seconds

        // Capability name for phone app
        private const val PHONE_APP_CAPABILITY = "anxiety_monitor_phone"
    }

    private var batchSendJob: Job? = null

    data class ConnectionStatus(
        val isPhoneConnected: Boolean = false,
        val isWatchAppInstalled: Boolean = true,
        val phoneNodeName: String? = null,
        val lastSyncTime: Long = 0L
    )

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Initializing Wearable Data Layer...")

            dataClient = Wearable.getDataClient(context)
            messageClient = Wearable.getMessageClient(context)
            nodeClient = Wearable.getNodeClient(context)
            capabilityClient = Wearable.getCapabilityClient(context)

            // Find phone node
            findPhoneNode()

            // Start batch send job
            startBatchSendJob()

            // Set up listeners
            setupListeners()

            true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Wearable Data Layer", e)
            false
        }
    }

    private fun findPhoneNode() {
        try {
            // First try to find nodes with our app capability
            val capabilityInfo = Tasks.await(
                capabilityClient.getCapability(PHONE_APP_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            )

            phoneNodeId = capabilityInfo.nodes.firstOrNull()?.id

            if (phoneNodeId == null) {
                // Fallback to finding any connected node
                val connectedNodes = Tasks.await(nodeClient.connectedNodes)
                phoneNodeId = connectedNodes.firstOrNull()?.id
            }

            updateConnectionStatus()

            Log.d(TAG, "Phone node: $phoneNodeId")

        } catch (e: Exception) {
            Log.e(TAG, "Error finding phone node", e)
        }
    }

    private fun setupListeners() {
        // Listen for messages from phone
        messageClient.addListener(object : MessageClient.OnMessageReceivedListener {
            override fun onMessageReceived(messageEvent: MessageEvent) {
                when (messageEvent.path) {
                    SYNC_REQUEST_PATH -> {
                        Log.d(TAG, "Received sync request from phone")
                        sendBatchImmediately()
                    }
                    STATUS_REQUEST_PATH -> {
                        sendStatusToPhone()
                    }
                }
            }
        })

        // Listen for capability changes
        capabilityClient.addListener(
            object : CapabilityClient.OnCapabilityChangedListener {
                override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
                    if (capabilityInfo.name == PHONE_APP_CAPABILITY) {
                        phoneNodeId = capabilityInfo.nodes.firstOrNull()?.id
                        updateConnectionStatus()
                    }
                }
            },
            PHONE_APP_CAPABILITY
        )
    }

    suspend fun sendBiometricReading(reading: WearBiometricReading) {
        // Add to batch buffer
        synchronized(batchBuffer) {
            batchBuffer.add(reading)

            // Send immediately if buffer is full
            if (batchBuffer.size >= BATCH_SIZE) {
                sendBatchImmediately()
            }
        }

        // Also send as individual data item for real-time sync
        sendIndividualReading(reading)
    }

    private suspend fun sendIndividualReading(reading: WearBiometricReading) = withContext(Dispatchers.IO) {
        try {
            val readingDataMap = DataMap().apply {
                putLong("timestamp", reading.timestamp)
                reading.heartRate?.let { putInt("heart_rate", it) }
                reading.interBeatInterval?.let { putDouble("ibi", it) }
                reading.skinTemperature?.let { putFloat("skin_temp", it) }
                reading.accelerometerX?.let { putFloat("accel_x", it) }
                reading.accelerometerY?.let { putFloat("accel_y", it) }
                reading.accelerometerZ?.let { putFloat("accel_z", it) }
                putFloat("confidence", reading.confidence)
                putString("source", "GALAXY_WATCH")
            }

            val putDataReq = PutDataMapRequest.create(BIOMETRIC_DATA_PATH).apply {
                this.dataMap.putAll(readingDataMap)
                setUrgent() // Real-time priority
            }.asPutDataRequest()

            Tasks.await(dataClient.putDataItem(putDataReq))

            Log.v(TAG, "Sent individual reading: HR=${reading.heartRate}")

        } catch (e: Exception) {
            Log.e(TAG, "Error sending individual reading", e)
        }
    }

    private fun startBatchSendJob() {
        batchSendJob?.cancel()
        batchSendJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(BATCH_SEND_INTERVAL_MS)
                sendBatchImmediately()
            }
        }
    }

    private fun sendBatchImmediately() {
        synchronized(batchBuffer) {
            if (batchBuffer.isEmpty()) return

            CoroutineScope(Dispatchers.IO).launch {
                sendBatchToPhone(batchBuffer.toList())
                batchBuffer.clear()
            }
        }
    }

    private suspend fun sendBatchToPhone(readings: List<WearBiometricReading>) = withContext(Dispatchers.IO) {
        try {
            val jsonArray = JSONArray()
            readings.forEach { reading ->
                val json = JSONObject().apply {
                    put("timestamp", reading.timestamp)
                    reading.heartRate?.let { put("heart_rate", it) }
                    reading.interBeatInterval?.let { put("ibi", it) }
                    reading.skinTemperature?.let { put("skin_temp", it) }
                    reading.accelerometerX?.let { put("accel_x", it) }
                    reading.accelerometerY?.let { put("accel_y", it) }
                    reading.accelerometerZ?.let { put("accel_z", it) }
                    put("confidence", reading.confidence.toDouble())
                }
                jsonArray.put(json)
            }

            val batchData = JSONObject().apply {
                put("readings", jsonArray)
                put("device_id", getDeviceId())
                put("batch_time", System.currentTimeMillis())
            }

            phoneNodeId?.let { nodeId ->
                Tasks.await(
                    messageClient.sendMessage(
                        nodeId,
                        SYNC_BATCH_PATH,
                        batchData.toString().toByteArray()
                    )
                )

                Log.d(TAG, "Sent batch with ${readings.size} readings to phone")

                _connectionStatus.value = _connectionStatus.value.copy(
                    lastSyncTime = System.currentTimeMillis()
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error sending batch to phone", e)
        }
    }

    private fun sendStatusToPhone() {
        phoneNodeId?.let { nodeId ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val statusJson = JSONObject().apply {
                        put("monitoring", true)
                        put("buffer_size", batchBuffer.size)
                        put("device_id", getDeviceId())
                        put("timestamp", System.currentTimeMillis())
                    }

                    Tasks.await(
                        messageClient.sendMessage(
                            nodeId,
                            STATUS_RESPONSE_PATH,
                            statusJson.toString().toByteArray()
                        )
                    )

                } catch (e: Exception) {
                    Log.e(TAG, "Error sending status to phone", e)
                }
            }
        }
    }

    private fun updateConnectionStatus() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val connectedNodes = Tasks.await(nodeClient.connectedNodes)
                val phoneNode = connectedNodes.find { it.id == phoneNodeId }

                _connectionStatus.value = ConnectionStatus(
                    isPhoneConnected = phoneNode != null,
                    isWatchAppInstalled = true,
                    phoneNodeName = phoneNode?.displayName,
                    lastSyncTime = _connectionStatus.value.lastSyncTime
                )

            } catch (e: Exception) {
                Log.e(TAG, "Error updating connection status", e)
            }
        }
    }

    suspend fun sendAnxietyAlert(
        confidence: Float,
        heartRate: Int,
        ibi: Double?
    ) = withContext(Dispatchers.IO) {
        try {
            val alertData = JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("confidence", confidence.toDouble())
                put("heart_rate", heartRate)
                ibi?.let { put("ibi", it) }
                put("device_id", getDeviceId())
            }

            // Send as urgent message
            phoneNodeId?.let { nodeId ->
                Tasks.await(
                    messageClient.sendMessage(
                        nodeId,
                        ANXIETY_EVENT_PATH,
                        alertData.toString().toByteArray()
                    )
                )

                Log.d(TAG, "Sent anxiety alert to phone")
            }

            // Also send as data item for persistence
            val alertDataMap = DataMap().apply {
                putString("alert_data", alertData.toString())
                putLong("timestamp", System.currentTimeMillis())
            }

            val putDataReq = PutDataMapRequest.create(ANXIETY_EVENT_PATH).apply {
                this.dataMap.putAll(alertDataMap)
                setUrgent()
            }.asPutDataRequest()

            Tasks.await(dataClient.putDataItem(putDataReq))

        } catch (e: Exception) {
            Log.e(TAG, "Error sending anxiety alert", e)
        }
    }

    private fun getDeviceId(): String {
        // Use a more privacy-friendly approach for device identification
        val modelName = android.os.Build.MODEL ?: "Unknown"
        val timestamp = System.currentTimeMillis().toString().takeLast(8)
        return "${modelName}_${timestamp}"
    }

    fun cleanup() {
        batchSendJob?.cancel()
        sendBatchImmediately()
    }
}