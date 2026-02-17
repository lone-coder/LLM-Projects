package com.yourcompany.anxietymonitor.wear.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

/**
 * Dark color palette optimized for OLED displays on Wear OS devices
 */
private val WearColorPalette = Colors(
    primary = Color(0xFF4285F4),              // Google Blue
    primaryVariant = Color(0xFF1A73E8),       // Darker Blue
    secondary = Color(0xFF34A853),            // Google Green
    secondaryVariant = Color(0xFF137333),     // Darker Green
    background = Color(0xFF000000),           // Pure black for OLED
    surface = Color(0xFF1F1F1F),              // Dark gray for cards
    error = Color(0xFFEA4335),                // Google Red
    onPrimary = Color(0xFFFFFFFF),            // White text on primary
    onSecondary = Color(0xFFFFFFFF),          // White text on secondary
    onBackground = Color(0xFFFFFFFF),         // White text on background
    onSurface = Color(0xFFE8EAED),            // Light gray text on surface
    onSurfaceVariant = Color(0xFF9AA0A6),     // Medium gray for secondary text
    onError = Color(0xFFFFFFFF)               // White text on error
)

/**
 * Custom colors for biometric data display
 */
object BiometricColors {
    val heartRate = Color(0xFFE91E63)         // Pink for heart rate
    val hrv = Color(0xFF2196F3)               // Blue for HRV
    val temperature = Color(0xFFFF9800)       // Orange for temperature
    val activity = Color(0xFF4CAF50)          // Green for activity
    val anxiety = Color(0xFFF44336)           // Red for anxiety alerts
    val success = Color(0xFF4CAF50)           // Green for success states
    val warning = Color(0xFFFF9800)           // Orange for warnings
}

/**
 * Connection status colors
 */
object ConnectionColors {
    val connected = Color(0xFF4CAF50)         // Green for connected
    val disconnected = Color(0xFFF44336)      // Red for disconnected
    val connecting = Color(0xFFFF9800)        // Orange for connecting
}

/**
 * Main theme for the Wear OS app
 * Optimized for dark OLED displays with high contrast
 */
@Composable
fun WearAppTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colors = WearColorPalette,
        content = content
    )
}