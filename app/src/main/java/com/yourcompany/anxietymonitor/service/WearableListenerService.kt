package com.yourcompany.anxietymonitor.service

import android.util.Log
import com.google.android.gms.wearable.*
import com.yourcompany.anxietymonitor.data.repository.DataRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Background service that listens for data from the Galaxy Watch
 * Extends WearableListenerService for automatic lifecycle management
 */
@AndroidEntryPoint
class AnxietyDataListenerService : WearableListenerService() {

    @Inject
    lateinit var wearableDataSync: WearableDataSyncService

    @Inject
    lateinit var repository: DataRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "AnxietyDataListener"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AnxietyDataListenerService created")

        serviceScope.launch {
            wearableDataSync.initialize()
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        super.onDataChanged(dataEvents) // It's good practice to call super
        Log.d(TAG, "onDataChanged: ${dataEvents.count} events")

        // Delegate to WearableDataSyncService
        wearableDataSync.onDataChanged(dataEvents)

        dataEvents.release()
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent) // It's good practice to call super
        Log.d(TAG, "onMessageReceived: ${messageEvent.path}")

        // Delegate to WearableDataSyncService
        wearableDataSync.onMessageReceived(messageEvent)
    }

    override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
        super.onCapabilityChanged(capabilityInfo) // It's good practice to call super
        Log.d(TAG, "onCapabilityChanged: ${capabilityInfo.name}")

        // Handle capability changes if needed
        when (capabilityInfo.name) {
            "anxiety_monitor_watch" -> {
                val nodes = capabilityInfo.nodes
                Log.d(TAG, "Watch app capability nodes: ${nodes.size}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wearableDataSync.cleanup()
    }
}
