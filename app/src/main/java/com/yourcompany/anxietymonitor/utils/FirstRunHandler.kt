package com.yourcompany.anxietymonitor.utils

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages first-run experience and app configuration state
 */
@Singleton
class FirstRunHandler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "anxiety_monitor_prefs",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val KEY_FIRST_RUN = "first_run"
        private const val KEY_SETUP_COMPLETE = "data_setup_complete"
        private const val KEY_HISTORICAL_DATA_LOADED = "historical_data_loaded"
        private const val KEY_BASELINE_COUNT = "baseline_count"
        private const val KEY_DATA_SOURCE_TIER = "data_source_tier"
        private const val KEY_ONBOARDING_VERSION = "onboarding_version"

        const val CURRENT_ONBOARDING_VERSION = 2 // Increment when onboarding changes
    }

    /**
     * Check if this is the first run of the app
     */
    fun isFirstRun(): Boolean {
        return prefs.getBoolean(KEY_FIRST_RUN, true)
    }

    /**
     * Check if data setup has been completed
     */
    fun isSetupComplete(): Boolean {
        return prefs.getBoolean(KEY_SETUP_COMPLETE, false)
    }

    /**
     * Check if we need to show onboarding (new version)
     */
    fun needsOnboarding(): Boolean {
        val currentVersion = prefs.getInt(KEY_ONBOARDING_VERSION, 0)
        return currentVersion < CURRENT_ONBOARDING_VERSION
    }

    /**
     * Mark first run as complete
     */
    fun markFirstRunComplete() {
        prefs.edit()
            .putBoolean(KEY_FIRST_RUN, false)
            .apply()
    }

    /**
     * Mark data setup as complete
     */
    fun markSetupComplete(
        historicalDataLoaded: Boolean,
        baselineCount: Int,
        dataSourceTier: String
    ) {
        prefs.edit()
            .putBoolean(KEY_SETUP_COMPLETE, true)
            .putBoolean(KEY_HISTORICAL_DATA_LOADED, historicalDataLoaded)
            .putInt(KEY_BASELINE_COUNT, baselineCount)
            .putString(KEY_DATA_SOURCE_TIER, dataSourceTier)
            .putInt(KEY_ONBOARDING_VERSION, CURRENT_ONBOARDING_VERSION)
            .apply()
    }

    /**
     * Get setup configuration
     */
    fun getSetupConfig(): SetupConfig {
        return SetupConfig(
            historicalDataLoaded = prefs.getBoolean(KEY_HISTORICAL_DATA_LOADED, false),
            baselineCount = prefs.getInt(KEY_BASELINE_COUNT, 0),
            dataSourceTier = prefs.getString(KEY_DATA_SOURCE_TIER, "UNKNOWN") ?: "UNKNOWN",
            onboardingVersion = prefs.getInt(KEY_ONBOARDING_VERSION, 0)
        )
    }

    /**
     * Reset setup (for testing or re-configuration)
     */
    fun resetSetup() {
        prefs.edit()
            .putBoolean(KEY_SETUP_COMPLETE, false)
            .putBoolean(KEY_FIRST_RUN, true)
            .remove(KEY_HISTORICAL_DATA_LOADED)
            .remove(KEY_BASELINE_COUNT)
            .remove(KEY_DATA_SOURCE_TIER)
            .apply()
    }

    /**
     * Check if app has sufficient data for accurate detection
     */
    fun hasMinimumData(): Boolean {
        val config = getSetupConfig()
        return config.historicalDataLoaded || config.baselineCount >= 10
    }

    data class SetupConfig(
        val historicalDataLoaded: Boolean,
        val baselineCount: Int,
        val dataSourceTier: String,
        val onboardingVersion: Int
    )
}

// Extension function for MainActivity - TODO: Move to appropriate file when MainActivity is created
// fun MainActivity.checkFirstRun(firstRunHandler: FirstRunHandler): Boolean {
//     if (firstRunHandler.isFirstRun() || !firstRunHandler.isSetupComplete()) {
//         // Navigate to setup
//         val intent = Intent(this, DataSetupActivity::class.java)
//         startActivity(intent)
//         finish()
//         return true
//     }
//
//     // Check if re-onboarding is needed (app update)
//     if (firstRunHandler.needsOnboarding()) {
//         val intent = Intent(this, DataSetupActivity::class.java)
//         intent.putExtra("re_onboarding", true)
//         startActivity(intent)
//         return true
//     }
//
//     return false
// }