// ============================================================================
// app/src/main/java/com/yourcompany/anxietymonitor/utils/PermissionUtils.kt
// ============================================================================

package com.yourcompany.anxietymonitor.utils

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object PermissionUtils {

    fun hasAllRequiredPermissions(context: Context): Boolean {
        return Constants.REQUIRED_PERMISSIONS.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun getMissingPermissions(context: Context): List<String> {
        return Constants.REQUIRED_PERMISSIONS.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasHealthPermissions(context: Context): Boolean {
        val healthPermissions = arrayOf(
            "com.samsung.android.providers.health.permission.READ",
            "com.samsung.android.providers.health.permission.WRITE"
        )
        return healthPermissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasBiometricCapability(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
    }
}