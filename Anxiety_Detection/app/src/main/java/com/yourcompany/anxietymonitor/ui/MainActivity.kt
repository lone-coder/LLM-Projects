package com.yourcompany.anxietymonitor.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.yourcompany.anxietymonitor.R
import com.yourcompany.anxietymonitor.service.AnxietyMonitoringBackgroundService
import com.yourcompany.anxietymonitor.data.repository.DataRepository
import com.yourcompany.anxietymonitor.data.health.HybridBiometricDataSource
import com.yourcompany.anxietymonitor.service.WearableDataSyncService
import com.yourcompany.anxietymonitor.domain.interfaces.CognitiveAnalyzer
import com.yourcompany.anxietymonitor.domain.engine.AnxietyDetectionEngine
import com.yourcompany.anxietymonitor.domain.models.BiometricReading
import com.yourcompany.anxietymonitor.utils.FirstRunHandler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import java.util.Locale
import javax.inject.Inject
import com.yourcompany.anxietymonitor.ai.ModelManager

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var cognitiveAnalyzer: CognitiveAnalyzer
    @Inject lateinit var repository: DataRepository
    @Inject lateinit var hybridDataSource: HybridBiometricDataSource
    @Inject lateinit var wearableSync: WearableDataSyncService
    @Inject lateinit var modelManager: ModelManager
    @Inject lateinit var firstRunHandler: FirstRunHandler
    @Inject lateinit var anxietyDetectionEngine: AnxietyDetectionEngine

    // UI Elements
    private lateinit var connectionStatusIcon: ImageView
    private lateinit var connectionStatusTitle: TextView
    private lateinit var connectionStatusText: TextView
    private lateinit var calibrationStatusText: TextView
    private lateinit var calibrationProgressBar: ProgressBar
    private lateinit var calibrationProgressText: TextView
    private lateinit var heartRateValue: TextView
    private lateinit var hrvValue: TextView
    private lateinit var temperatureValue: TextView
    private lateinit var journalAccessCard: CardView
    private lateinit var settingsCard: CardView
    private lateinit var reportAnxietyFab: FloatingActionButton

    private var isMonitoring = false
    private var currentReading: BiometricReading? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            startAnxietyMonitoring()
        } else {
            showPermissionDeniedUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if first run - launch setup
        if (firstRunHandler.isFirstRun() || !firstRunHandler.isSetupComplete()) {
            val intent = Intent(this, DataSetupActivity::class.java)
            startActivity(intent)
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        initializeViews()
        setupClickListeners()

        lifecycleScope.launch {
            initializeApp()
        }
    }

    private fun initializeViews() {
        connectionStatusIcon = findViewById(R.id.connectionStatusIcon)
        connectionStatusTitle = findViewById(R.id.connectionStatusTitle)
        connectionStatusText = findViewById(R.id.connectionStatusText)

        calibrationStatusText = findViewById(R.id.calibrationStatusText)
        calibrationProgressBar = findViewById(R.id.calibrationProgressBar)
        calibrationProgressText = findViewById(R.id.calibrationProgressText)

        heartRateValue = findViewById(R.id.heartRateValue)
        hrvValue = findViewById(R.id.hrvValue)
        temperatureValue = findViewById(R.id.temperatureValue)

        journalAccessCard = findViewById(R.id.journalAccessCard)
        settingsCard = findViewById(R.id.settingsCard)
        reportAnxietyFab = findViewById(R.id.reportAnxietyFab)

        updateConnectionStatus(ConnectionState.INITIALIZING)
        updateBiometricReadings(null)
    }

    private fun setupClickListeners() {
        journalAccessCard.setOnClickListener {
            // Launch CBT Prompt Activity for journal access
            val intent = Intent(this, CbtPromptActivity::class.java)
            startActivity(intent)
        }

        settingsCard.setOnClickListener {
            showConnectionDebugInfo()
        }

        reportAnxietyFab.setOnClickListener {
            showQuickAnxietyReport()
        }
    }

    private fun showQuickAnxietyReport() {
        AlertDialog.Builder(this)
            .setTitle("Report Anxiety")
            .setMessage("Are you experiencing anxiety that wasn't detected?")
            .setPositiveButton("Yes, report it") { _, _ ->
                reportMissedAnxiety()
            }
            .setNegativeButton("Cancel") { _, _ -> }
            .setNeutralButton("Open Journal") { _, _ ->
                // Open CBT journal for detailed analysis
                val intent = Intent(this, CbtPromptActivity::class.java)
                startActivity(intent)
            }
            .show()
    }

    private fun reportMissedAnxiety() {
        lifecycleScope.launch {
            try {
                // Force sync latest watch data first
                wearableSync.forceSyncFromWatch()

                anxietyDetectionEngine.reportManualAnxietyEvent(
                    timestamp = System.currentTimeMillis(),
                    userNotes = "Manually reported from main screen"
                )

                showToast("Anxiety reported. This helps improve detection accuracy.")

            } catch (e: Exception) {
                showToast("Failed to report anxiety")
            }
        }
    }

    private suspend fun initializeApp() {
        try {
            updateConnectionStatus(ConnectionState.INITIALIZING_AI)

            // Check if AI model is downloaded first
            if (!modelManager.isModelDownloaded()) {
                // Show download dialog and wait for user decision
                val downloadDialog = ModelDownloadDialog(this, modelManager, this)
                downloadDialog.showDownloadPrompt { success ->
                    if (success) {
                        // Model downloaded successfully, continue initialization
                        lifecycleScope.launch {
                            continueAIInitialization()
                        }
                    } else {
                        // User skipped download, show AI failed state
                        updateConnectionStatus(ConnectionState.AI_FAILED)
                        showToast(getString(R.string.ai_model_failed))
                    }
                }
                return // Exit here, continuation happens in callback
            }

            // Model already exists, proceed with normal initialization
            continueAIInitialization()

        } catch (e: Exception) {
            updateConnectionStatus(ConnectionState.ERROR)
            showToast(getString(R.string.initialization_failed))
        }
    }

    private suspend fun continueAIInitialization() {
        try {
            val aiInitialized = cognitiveAnalyzer.initialize()
            if (!aiInitialized) {
                updateConnectionStatus(ConnectionState.AI_FAILED)
                showToast(getString(R.string.ai_model_failed))
                return
            }

            // Initialize wearable sync
            wearableSync.initialize()

            requestHealthPermissions()

        } catch (e: Exception) {
            updateConnectionStatus(ConnectionState.ERROR)
            showToast(getString(R.string.initialization_failed))
        }
    }

    private fun requestHealthPermissions() {
        val permissions = arrayOf(
            "com.samsung.android.providers.health.permission.READ",
            "com.samsung.android.providers.health.permission.WRITE",
            android.Manifest.permission.POST_NOTIFICATIONS,
            android.Manifest.permission.FOREGROUND_SERVICE,
            android.Manifest.permission.FOREGROUND_SERVICE_HEALTH,
            android.Manifest.permission.BODY_SENSORS,
            android.Manifest.permission.ACTIVITY_RECOGNITION
        )
        permissionLauncher.launch(permissions)
    }

    private fun showPermissionDeniedUI() {
        updateConnectionStatus(ConnectionState.PERMISSION_DENIED)
        showToast(getString(R.string.health_permission_required))
    }

    private fun startAnxietyMonitoring() {
        lifecycleScope.launch {
            try {
                val serviceIntent = Intent(this@MainActivity, AnxietyMonitoringBackgroundService::class.java).apply {
                    action = AnxietyMonitoringBackgroundService.ACTION_START_MONITORING
                }
                startForegroundService(serviceIntent)

                isMonitoring = true
                updateConnectionStatus(ConnectionState.CONNECTING)

                startUIMonitoring()

            } catch (e: Exception) {
                updateConnectionStatus(ConnectionState.ERROR)
                showToast(getString(R.string.monitoring_failed))
            }
        }
    }

    private fun startUIMonitoring() {
        // Monitor recent readings
        lifecycleScope.launch {
            repository.observeRecentReadings(System.currentTimeMillis() - 300_000)
                .collectLatest { readings ->
                    val latestReading = readings.maxByOrNull { it.timestamp }
                    currentReading = latestReading

                    runOnUiThread {
                        updateBiometricReadings(latestReading)
                        updateConnectionStatus(
                            if (latestReading != null) ConnectionState.CONNECTED
                            else ConnectionState.NO_DATA
                        )
                    }
                }
        }

        // Monitor calibration status
        lifecycleScope.launch {
            monitorCalibrationStatus()
        }

        // Monitor data source status
        lifecycleScope.launch {
            monitorDataSourceStatus()
        }
    }

    private suspend fun monitorCalibrationStatus() {
        while (isMonitoring) {
            try {
                val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                val baseline = repository.getBaseline(currentHour)
                val recentReadings = repository.getRecentReadings(20)

                runOnUiThread {
                    when {
                        baseline != null && baseline.dataPoints >= 20 -> {
                            updateCalibrationStatus(CalibrationState.COMPLETE)
                        }
                        recentReadings.size >= 10 -> {
                            updateCalibrationStatus(CalibrationState.CALIBRATING)
                            updateCalibrationProgress(recentReadings.size, 20)
                        }
                        recentReadings.isNotEmpty() -> {
                            updateCalibrationStatus(CalibrationState.COLLECTING_DATA)
                            updateCalibrationProgress(recentReadings.size, 10)
                        }
                        else -> {
                            updateCalibrationStatus(CalibrationState.WAITING_FOR_DATA)
                        }
                    }
                }

            } catch (e: Exception) {
                runOnUiThread {
                    updateCalibrationStatus(CalibrationState.ERROR)
                }
            }

            delay(10_000)
        }
    }

    private suspend fun monitorDataSourceStatus() {
        while (isMonitoring) {
            try {
                val status = hybridDataSource.getDetailedStatus()
                val tier = hybridDataSource.getActiveTier()
                val isConnected = hybridDataSource.isConnected()
                val accuracy = hybridDataSource.getExpectedAccuracy()

                runOnUiThread {
                    updateDataSourceStatus(tier, isConnected, accuracy)
                }

            } catch (e: Exception) {
                // Continue monitoring
            }

            delay(5_000) // Check every 5 seconds
        }
    }

    private fun updateDataSourceStatus(
        tier: HybridBiometricDataSource.DataSourceTier,
        isConnected: Boolean,
        accuracy: Float
    ) {
        when {
            isConnected && tier == HybridBiometricDataSource.DataSourceTier.SAMSUNG_SDK -> {
                updateConnectionStatus(ConnectionState.CONNECTED)
                connectionStatusText.text = getString(R.string.connected) + " (Samsung SDK)"
            }
            isConnected && tier == HybridBiometricDataSource.DataSourceTier.HEALTH_CONNECT -> {
                updateConnectionStatus(ConnectionState.CONNECTED)
                connectionStatusText.text = getString(R.string.connected) + " (health Connect)"
            }
            !isConnected -> {
                updateConnectionStatus(ConnectionState.CONNECTING)
            }
        }
    }

    private fun showConnectionDebugInfo() {
        lifecycleScope.launch {
            val status = hybridDataSource.getDetailedStatus()
            val syncStatus = wearableSync.getSyncStatus()

            val debugText = buildString {
                appendLine("=== Data Source Status ===")
                appendLine("Active Tier: ${status["active_tier"]}")
                appendLine("Primary Source: ${status["primary_source"]}")
                appendLine("Secondary Source: ${status["secondary_source"]}")
                appendLine("Samsung Device: ${status["is_samsung_device"]}")
                appendLine("Samsung health Installed: ${status["samsung_health_installed"]}")
                appendLine()

                val samsungStatus = status["samsung_sensor_status"] as? Map<*, *>
                if (samsungStatus != null) {
                    appendLine("=== Samsung Sensor SDK ===")
                    appendLine("Initialized: ${samsungStatus["initialized"]}")
                    appendLine("Streaming: ${samsungStatus["streaming"]}")
                    val sensors = samsungStatus["sensors_active"] as? Map<*, *>
                    sensors?.forEach { (sensor, active) ->
                        appendLine("$sensor: $active")
                    }
                    appendLine("Confidence: ${String.format(Locale.getDefault(), "%.2f", samsungStatus["confidence"])}")
                    appendLine()
                }

                val healthConnectStatus = status["health_connect_status"] as? Map<*, *>
                if (healthConnectStatus != null) {
                    appendLine("=== health Connect ===")
                    appendLine("Initialized: ${healthConnectStatus["initialized"]}")
                    appendLine("Streaming: ${healthConnectStatus["streaming"]}")
                    appendLine("Permissions: ${healthConnectStatus["permissions_granted"]}")
                    appendLine()
                }

                appendLine("=== Wearable Sync ===")
                appendLine("Initialized: ${syncStatus["initialized"]}")
                appendLine("Watch Connected: ${syncStatus["watch_connected"]}")

                // Add ML learning stats
                appendLine()
                appendLine("=== ML Learning Stats ===")
                val detectionStats = anxietyDetectionEngine.getDetectionStats()
                detectionStats.forEach { (key, value) ->
                    appendLine("$key: $value")
                }
            }

            runOnUiThread {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.connection_status))
                    .setMessage(debugText)
                    .setPositiveButton(getString(android.R.string.ok), null)
                    .show()
            }
        }
    }

    private fun updateConnectionStatus(state: ConnectionState) {
        when (state) {
            ConnectionState.INITIALIZING -> {
                connectionStatusIcon.setImageResource(R.drawable.ic_watch_connected)
                connectionStatusIcon.setColorFilter(getColor(R.color.status_connecting))
                connectionStatusText.text = getString(R.string.connecting)
            }

            ConnectionState.INITIALIZING_AI -> {
                connectionStatusIcon.setImageResource(R.drawable.ic_psychology)
                connectionStatusIcon.setColorFilter(getColor(R.color.status_connecting))
                connectionStatusText.text = getString(R.string.analyzing)
            }

            ConnectionState.CONNECTING -> {
                connectionStatusIcon.setImageResource(R.drawable.ic_watch_connected)
                connectionStatusIcon.setColorFilter(getColor(R.color.status_connecting))
                connectionStatusText.text = getString(R.string.connecting)
            }

            ConnectionState.CONNECTED -> {
                connectionStatusIcon.setImageResource(R.drawable.ic_watch_connected)
                connectionStatusIcon.setColorFilter(getColor(R.color.status_connected))
                connectionStatusText.text = getString(R.string.connected)
            }

            ConnectionState.NO_DATA -> {
                connectionStatusIcon.setImageResource(R.drawable.ic_watch_connected)
                connectionStatusIcon.setColorFilter(getColor(R.color.status_connecting))
                connectionStatusText.text = getString(R.string.no_data)
            }

            ConnectionState.PERMISSION_DENIED -> {
                connectionStatusIcon.setImageResource(R.drawable.ic_watch_connected)
                connectionStatusIcon.setColorFilter(getColor(R.color.status_disconnected))
                connectionStatusText.text = getString(R.string.permission_denied)
            }

            ConnectionState.AI_FAILED -> {
                connectionStatusIcon.setImageResource(R.drawable.ic_psychology)
                connectionStatusIcon.setColorFilter(getColor(R.color.status_disconnected))
                connectionStatusText.text = getString(R.string.ai_model_failed)
            }

            ConnectionState.ERROR -> {
                connectionStatusIcon.setImageResource(R.drawable.ic_watch_connected)
                connectionStatusIcon.setColorFilter(getColor(R.color.status_disconnected))
                connectionStatusText.text = getString(R.string.initialization_failed)
            }
        }
    }

    private fun updateCalibrationStatus(state: CalibrationState) {
        when (state) {
            CalibrationState.WAITING_FOR_DATA -> {
                calibrationStatusText.text = getString(R.string.waiting_for_data)
                findViewById<LinearLayout>(R.id.calibrationProgressLayout).visibility = View.VISIBLE
            }

            CalibrationState.COLLECTING_DATA -> {
                calibrationStatusText.text = getString(R.string.collecting_data)
                findViewById<LinearLayout>(R.id.calibrationProgressLayout).visibility = View.VISIBLE
            }

            CalibrationState.CALIBRATING -> {
                calibrationStatusText.text = getString(R.string.calibrating)
                findViewById<LinearLayout>(R.id.calibrationProgressLayout).visibility = View.VISIBLE
            }

            CalibrationState.COMPLETE -> {
                calibrationStatusText.text = getString(R.string.calibration_complete)
                findViewById<LinearLayout>(R.id.calibrationProgressLayout).visibility = View.GONE
                findViewById<ImageView>(R.id.calibrationIcon).setColorFilter(getColor(R.color.status_connected))
            }

            CalibrationState.ERROR -> {
                calibrationStatusText.text = getString(R.string.calibration_error)
                findViewById<LinearLayout>(R.id.calibrationProgressLayout).visibility = View.GONE
                findViewById<ImageView>(R.id.calibrationIcon).setColorFilter(getColor(R.color.status_disconnected))
            }
        }
    }

    private fun updateCalibrationProgress(current: Int, target: Int) {
        val progress = (current * 100) / target
        calibrationProgressBar.progress = progress.coerceAtMost(100)
        calibrationProgressText.text = getString(R.string.readings_collected_format, current, target)
    }

    private fun updateBiometricReadings(reading: BiometricReading?) {
        // Heart Rate
        if (reading?.heartRate != null && reading.heartRate > 0) {
            heartRateValue.text = reading.heartRate.toString()
            heartRateValue.setTextColor(getColor(R.color.text_primary))
        } else {
            heartRateValue.text = getString(R.string.no_data_placeholder)
            heartRateValue.setTextColor(getColor(R.color.text_secondary))
        }

        // HRV
        if (reading?.hrvRmssd != null && reading.hrvRmssd > 0) {
            hrvValue.text = String.format(Locale.getDefault(), "%.1f", reading.hrvRmssd)
            hrvValue.setTextColor(getColor(R.color.text_primary))
        } else {
            hrvValue.text = getString(R.string.no_data_placeholder)
            hrvValue.setTextColor(getColor(R.color.text_secondary))
        }

        // Temperature
        if (reading?.skinTemperature != null && reading.skinTemperature > 0) {
            temperatureValue.text = String.format(Locale.getDefault(), "%.1f", reading.skinTemperature)
            temperatureValue.setTextColor(getColor(R.color.text_primary))
        } else {
            temperatureValue.text = getString(R.string.no_data_placeholder)
            temperatureValue.setTextColor(getColor(R.color.text_secondary))
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        currentReading?.let { updateBiometricReadings(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        isMonitoring = false
    }
}

enum class ConnectionState {
    INITIALIZING,
    INITIALIZING_AI,
    CONNECTING,
    CONNECTED,
    NO_DATA,
    PERMISSION_DENIED,
    AI_FAILED,
    ERROR
}

enum class CalibrationState {
    WAITING_FOR_DATA,
    COLLECTING_DATA,
    CALIBRATING,
    COMPLETE,
    ERROR
}