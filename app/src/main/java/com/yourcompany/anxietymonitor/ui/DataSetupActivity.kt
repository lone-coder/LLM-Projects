package com.yourcompany.anxietymonitor.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.yourcompany.anxietymonitor.R
import com.yourcompany.anxietymonitor.data.health.HistoricalDataLoader
import com.yourcompany.anxietymonitor.data.health.HybridBiometricDataSource
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.text.SimpleDateFormat
import java.util.*
import com.yourcompany.anxietymonitor.domain.models.SensorCapability  // ADD THIS IMPORT


/**
 * Initial setup activity that loads historical data for immediate baseline establishment
 */
@AndroidEntryPoint
class DataSetupActivity : AppCompatActivity() {

    @Inject
    lateinit var historicalDataLoader: HistoricalDataLoader

    @Inject
    lateinit var hybridDataSource: HybridBiometricDataSource

    // UI Elements
    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var statusText: TextView
    private lateinit var detailsCard: CardView
    private lateinit var detailsText: TextView
    private lateinit var continueButton: Button
    private lateinit var skipButton: Button

    // Data source info views
    private lateinit var dataSourceCard: CardView
    private lateinit var primarySourceText: TextView
    private lateinit var accuracyText: TextView
    private lateinit var capabilitiesText: TextView

    companion object {
        private const val PREF_SETUP_COMPLETE = "data_setup_complete"
        private const val PREF_FIRST_RUN = "first_run"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_setup)

        initializeViews()

        // Check if setup already complete
        val prefs = getSharedPreferences("anxiety_monitor", MODE_PRIVATE)
        if (prefs.getBoolean(PREF_SETUP_COMPLETE, false)) {
            navigateToMainActivity()
            return
        }

        setupClickListeners()
        checkDataSources()
    }

    private fun initializeViews() {
        titleText = findViewById(R.id.setupTitleText)
        subtitleText = findViewById(R.id.setupSubtitleText)
        progressBar = findViewById(R.id.setupProgressBar)
        progressText = findViewById(R.id.progressText)
        statusText = findViewById(R.id.statusText)
        detailsCard = findViewById(R.id.detailsCard)
        detailsText = findViewById(R.id.detailsText)
        continueButton = findViewById(R.id.continueButton)
        skipButton = findViewById(R.id.skipButton)

        dataSourceCard = findViewById(R.id.dataSourceCard)
        primarySourceText = findViewById(R.id.primarySourceText)
        accuracyText = findViewById(R.id.accuracyText)
        capabilitiesText = findViewById(R.id.capabilitiesText)

        // Initial state
        progressBar.progress = 0
        detailsCard.visibility = View.GONE
        continueButton.isEnabled = false
    }

    private fun setupClickListeners() {
        continueButton.setOnClickListener {
            when (continueButton.text) {
                getString(R.string.load_data) -> startDataLoading()
                getString(R.string.continue_text) -> navigateToMainActivity()
            }
        }

        skipButton.setOnClickListener {
            showSkipConfirmation()
        }
    }

    private fun checkDataSources() {
        lifecycleScope.launch {
            try {
                statusText.text = "Detecting available data sources..."

                // Initialize hybrid data source
                val initialized = hybridDataSource.initialize()

                if (!initialized) {
                    showError("Failed to initialize data sources")
                    return@launch
                }

                // Show data source info
                updateDataSourceInfo()

                // Check for available historical data
                val healthAppInfo = historicalDataLoader.getHealthAppInfo()
                val hasHealthConnect = healthAppInfo["health Connect"] == true
                val hasSamsungHealth = healthAppInfo["Samsung health"] == true

                if (hasHealthConnect || hasSamsungHealth) {
                    val appsText = buildString {
                        if (hasHealthConnect) append("health Connect")
                        if (hasHealthConnect && hasSamsungHealth) append(" and ")
                        if (hasSamsungHealth) append("Samsung health")
                    }

                    titleText.text = "Historical Data Available!"
                    subtitleText.text = "We found $appsText on your device."
                    statusText.text = "We can load your existing health data to create personalized baselines immediately."

                    continueButton.text = getString(R.string.load_data)
                    continueButton.isEnabled = true

                    showDataSourceBenefits()
                } else {
                    titleText.text = "No Historical Data Found"
                    subtitleText.text = "Start fresh with real-time monitoring"
                    statusText.text = "The app will build personalized baselines over the next 2-4 weeks."

                    continueButton.text = getString(R.string.continue_text)
                    continueButton.isEnabled = true
                    skipButton.visibility = View.GONE
                }

            } catch (e: Exception) {
                showError("Error checking data sources: ${e.message}")
            }
        }
    }

    private fun updateDataSourceInfo() {
        val status = hybridDataSource.getDetailedStatus()
        val tier = hybridDataSource.getActiveTier()
        val accuracy = hybridDataSource.getExpectedAccuracy()
        val capabilities = hybridDataSource.getCapabilities()

        dataSourceCard.visibility = View.VISIBLE

        primarySourceText.text = when (tier) {
            HybridBiometricDataSource.DataSourceTier.SAMSUNG_SDK ->
                "Samsung Galaxy Watch (High Precision)"
            HybridBiometricDataSource.DataSourceTier.HEALTH_CONNECT ->
                "health Connect (Standard)"
            else -> "No data source available"
        }

        accuracyText.text = "Expected Accuracy: ${(accuracy * 100).toInt()}%"

        capabilitiesText.text = buildString {
            append("Available sensors: ")
            val capList = capabilities.map {
                when (it) {
                    SensorCapability.HEART_RATE -> "Heart Rate"
                    SensorCapability.HRV -> "HRV"
                    SensorCapability.SKIN_TEMPERATURE -> "Skin Temp"
                    SensorCapability.ACCELEROMETER -> "Motion"
                    SensorCapability.REAL_TIME_STREAMING -> "Real-time"
                }
            }
            append(capList.joinToString(", "))
        }
    }

    private fun showDataSourceBenefits() {
        detailsCard.visibility = View.VISIBLE
        detailsText.text = buildString {
            appendLine("✓ Immediate anxiety detection with personalized thresholds")
            appendLine("✓ No waiting period - works from day one")
            appendLine("✓ Better accuracy with your historical patterns")
            appendLine("✓ Circadian rhythm analysis from past data")
            appendLine("\nLoading typically takes 1-3 minutes.")
        }
    }

    private fun startDataLoading() {
        lifecycleScope.launch {
            try {
                // Disable buttons during loading
                continueButton.isEnabled = false
                skipButton.isEnabled = false

                titleText.text = "Loading Historical Data"
                subtitleText.text = "Please wait while we analyze your health patterns..."

                val result = historicalDataLoader.loadHistoricalData(
                    daysToLoad = 90, // Load 3 months
                    onProgress = { progress ->
                        runOnUiThread {
                            updateProgress(progress)
                        }
                    }
                )

                if (result.success) {
                    showLoadingSuccess(result)
                } else {
                    showError(result.message)
                }

            } catch (e: Exception) {
                showError("Failed to load data: ${e.message}")
            }
        }
    }

    private fun updateProgress(progress: HistoricalDataLoader.LoadProgress) {
        progressBar.progress = (progress.percentComplete * 100).toInt()
        progressText.text = "${progressBar.progress}%"
        statusText.text = progress.message

        // Update stage-specific UI
        when (progress.stage) {
            HistoricalDataLoader.STAGE_LOADING_HR -> {
                subtitleText.text = "Reading heart rate history..."
            }
            HistoricalDataLoader.STAGE_LOADING_HRV -> {
                subtitleText.text = "Analyzing heart rate variability..."
            }
            HistoricalDataLoader.STAGE_LOADING_TEMP -> {
                subtitleText.text = "Loading temperature data..."
            }
            HistoricalDataLoader.STAGE_CALCULATING_BASELINES -> {
                subtitleText.text = "Creating personalized baselines..."
            }
            HistoricalDataLoader.STAGE_COMPLETE -> {
                subtitleText.text = "Setup complete!"
            }
        }
    }

    private fun showLoadingSuccess(result: HistoricalDataLoader.LoadResult) {
        titleText.text = "Setup Complete!"
        titleText.setTextColor(ContextCompat.getColor(this, R.color.status_connected))

        subtitleText.text = "Your anxiety monitor is ready to use"

        val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        val oldestDate = result.oldestDataDate?.let { dateFormat.format(Date(it.toEpochMilli())) }

        detailsCard.visibility = View.VISIBLE
        detailsText.text = buildString {
            appendLine("✓ Loaded ${result.recordsLoaded} health readings")
            appendLine("✓ Analyzed ${result.daysOfData} days of data")
            if (oldestDate != null) {
                appendLine("✓ Historical data from $oldestDate")
            }
            appendLine("✓ Created ${result.baselinesCreated} personalized baselines")
            appendLine("\nYour anxiety detection is now calibrated to your unique patterns!")
        }

        progressBar.progress = 100
        progressText.text = "100%"
        statusText.text = "Ready to start monitoring"

        continueButton.text = getString(R.string.continue_text)
        continueButton.isEnabled = true
        skipButton.visibility = View.GONE

        // Mark setup as complete
        markSetupComplete()
    }

    private fun showError(message: String) {
        titleText.text = "Setup Error"
        titleText.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected))
        subtitleText.text = "Unable to complete setup"
        statusText.text = message

        continueButton.text = getString(R.string.continue_text)
        continueButton.isEnabled = true
        skipButton.text = getString(R.string.retry)

        skipButton.setOnClickListener {
            // Retry setup
            recreate()
        }
    }

    private fun showSkipConfirmation() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Skip Historical Data?")
            .setMessage("Without historical data:\n\n" +
                    "• Baselines will be built over 2-4 weeks\n" +
                    "• Detection accuracy will improve gradually\n" +
                    "• Initial detections may be less accurate\n\n" +
                    "Are you sure you want to skip?")
            .setPositiveButton("Skip") { _, _ ->
                markSetupComplete()
                navigateToMainActivity()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun markSetupComplete() {
        val prefs = getSharedPreferences("anxiety_monitor", MODE_PRIVATE)
        prefs.edit()
            .putBoolean(PREF_SETUP_COMPLETE, true)
            .putBoolean(PREF_FIRST_RUN, false)
            .apply()
    }

    private fun navigateToMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}