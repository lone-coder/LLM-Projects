package com.yourcompany.anxietymonitor.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.*
import com.yourcompany.anxietymonitor.wear.data.WearBiometricReading
import com.yourcompany.anxietymonitor.wear.service.SensorMonitoringService
import com.yourcompany.anxietymonitor.wear.service.WearDataSyncManager
import com.yourcompany.anxietymonitor.wear.service.WearSensorManager
import com.yourcompany.anxietymonitor.wear.theme.WearAppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private lateinit var sensorManager: WearSensorManager
    private lateinit var dataSyncManager: WearDataSyncManager

    private val currentReadingFlow = MutableStateFlow<WearBiometricReading?>(null)
    private val isMonitoringFlow = MutableStateFlow(false)
    private val connectionStatusFlow = MutableStateFlow("Disconnected")

    companion object {
        private const val TAG = "WearMainActivity"
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            startMonitoring()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sensorManager = WearSensorManager(this)
        dataSyncManager = WearDataSyncManager(this)

        setContent {
            WearAppTheme {
                AnxietyMonitorApp(
                    currentReading = currentReadingFlow.collectAsState().value,
                    isMonitoring = isMonitoringFlow.collectAsState().value,
                    connectionStatus = connectionStatusFlow.collectAsState().value,
                    onStartMonitoring = { checkPermissionsAndStart() },
                    onStopMonitoring = { stopMonitoring() }
                )
            }
        }

        // Initialize data sync
        lifecycleScope.launch {
            dataSyncManager.initialize()
            updateConnectionStatus()
        }

        // Set up sensor data callback
        sensorManager.setDataCallback { reading ->
            lifecycleScope.launch {
                currentReadingFlow.value = reading
                dataSyncManager.sendBiometricReading(reading)
            }
        }
    }

    private fun checkPermissionsAndStart() {
        val requiredPermissions = mutableListOf(
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.FOREGROUND_SERVICE,
            "com.samsung.android.healthsensor.permission.USE_SENSOR",
            "com.samsung.wearable.permission.HEALTH_SENSOR"
        )

        // Add API level specific permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (Build.VERSION.SDK_INT >= 34) { // Android 14+
            requiredPermissions.add(Manifest.permission.FOREGROUND_SERVICE_HEALTH)
            requiredPermissions.add(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC)
        }

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startMonitoring()
        } else {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun startMonitoring() {
        // Start foreground service
        val serviceIntent = Intent(this, SensorMonitoringService::class.java)
        startForegroundService(serviceIntent)

        isMonitoringFlow.value = true
        Log.d(TAG, "Monitoring started")
    }

    private fun stopMonitoring() {
        val serviceIntent = Intent(this, SensorMonitoringService::class.java)
        stopService(serviceIntent)

        isMonitoringFlow.value = false
        currentReadingFlow.value = null
        Log.d(TAG, "Monitoring stopped")
    }

    private suspend fun updateConnectionStatus() {
        dataSyncManager.connectionStatus.collect { status ->
            connectionStatusFlow.value = when {
                status.isPhoneConnected -> "Phone Connected"
                status.isWatchAppInstalled -> "Watch Ready"
                else -> "Disconnected"
            }
        }
    }
}

@Composable
fun AnxietyMonitorApp(
    currentReading: WearBiometricReading?,
    isMonitoring: Boolean,
    connectionStatus: String,
    onStartMonitoring: () -> Unit,
    onStopMonitoring: () -> Unit
) {
    Scaffold(
        timeText = {
            TimeText(
                timeTextStyle = TimeTextDefaults.timeTextStyle(
                    fontSize = 12.sp
                )
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            // App Title
            Text(
                text = "Anxiety Monitor",
                style = MaterialTheme.typography.title3,
                modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)
            )

            // Connection Status
            Chip(
                modifier = Modifier.padding(bottom = 8.dp),
                onClick = { },
                label = {
                    Text(
                        text = connectionStatus,
                        style = MaterialTheme.typography.caption2
                    )
                },
                colors = ChipDefaults.chipColors(
                    backgroundColor = when (connectionStatus) {
                        "Phone Connected" -> Color(0xFF4CAF50)
                        "Watch Ready" -> Color(0xFFFF9800)
                        else -> Color(0xFFF44336)
                    }
                )
            )

            // Biometric Data Display
            if (currentReading != null) {
                BiometricDataCard(reading = currentReading)
            } else if (isMonitoring) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(16.dp)
                )
                Text(
                    text = "Waiting for data...",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurfaceVariant
                )
            }

            // Control Button
            Button(
                onClick = {
                    if (isMonitoring) onStopMonitoring() else onStartMonitoring()
                },
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(0.8f),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = if (isMonitoring) Color(0xFFF44336) else Color(0xFF4CAF50)
                )
            ) {
                Text(
                    text = if (isMonitoring) "Stop Monitoring" else "Start Monitoring",
                    style = MaterialTheme.typography.button
                )
            }
        }
    }
}

@Composable
fun BiometricDataCard(reading: WearBiometricReading) {
    Card(
        onClick = { /* Required for Card composable, can be empty */ },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        backgroundPainter = CardDefaults.cardBackgroundPainter(
            startBackgroundColor = MaterialTheme.colors.surface,
            endBackgroundColor = MaterialTheme.colors.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Heart Rate
            reading.heartRate?.let { hr ->
                BiometricRow(
                    label = "Heart Rate",
                    value = "$hr BPM",
                    color = Color(0xFFE91E63)
                )
            }

            // IBI (HRV)
            reading.interBeatInterval?.let { ibi ->
                val hrvValue = String.format(Locale.getDefault(), "%.1f ms", ibi)
                BiometricRow(
                    label = "HRV (IBI)",
                    value = hrvValue,
                    color = Color(0xFF2196F3)
                )
            }

            // Temperature
            reading.skinTemperature?.let { temp ->
                val tempValue = String.format(Locale.getDefault(), "%.1f°C", temp)
                BiometricRow(
                    label = "Skin Temp",
                    value = tempValue,
                    color = Color(0xFFFF9800)
                )
            }

            // Activity Level
            val activityLevel = reading.getActivityLevel()
            BiometricRow(
                label = "Activity",
                value = activityLevel,
                color = Color(0xFF4CAF50)
            )

            // Timestamp
            val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            Text(
                text = timeFormat.format(Date(reading.timestamp)),
                style = MaterialTheme.typography.caption3,
                color = MaterialTheme.colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
fun BiometricRow(
    label: String,
    value: String,
    color: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.title3,
            color = color
        )
    }
}