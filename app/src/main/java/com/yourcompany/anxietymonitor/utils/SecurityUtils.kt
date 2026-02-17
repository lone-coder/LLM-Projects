// ============================================================================
// app/src/main/java/com/yourcompany/anxietymonitor/utils/SecurityUtils.kt
// ============================================================================

package com.yourcompany.anxietymonitor.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import android.util.Base64
import java.security.SecureRandom

object SecurityUtils {

    private const val PREFS_NAME = "anxiety_monitor_secure_prefs"
    private const val KEY_ALIAS = "anxiety_monitor_key"

    fun getEncryptedSharedPreferences(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun generateSecureId(): String {
        val random = SecureRandom()
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)
    }

    fun hashString(input: String): String {
        val bytes = input.toByteArray()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hashedBytes = digest.digest(bytes)
        return Base64.encodeToString(hashedBytes, Base64.NO_WRAP)
    }

    fun isDeviceSecure(context: Context): Boolean {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        return keyguardManager.isDeviceSecure
    }
}
