package com.yourcompany.anxietymonitor.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit // Added for SharedPreferences KTX extension
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("model_prefs", Context.MODE_PRIVATE)

    private val _downloadState = MutableStateFlow(ModelDownloadState.NOT_DOWNLOADED)
    val downloadState: StateFlow<ModelDownloadState> = _downloadState

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress

    companion object {
        private const val MODEL_URL = "https://pub-14b0919a40ae41aa91afaf6a62d4fadc.r2.dev/anxiety-monitor-model-storage/gemma3-1b-it-int4.task"
        private const val MODEL_FILENAME = "gemma3-1b-it-int4.task"
        private const val MODEL_VERSION = "1.0"
        private const val PREF_MODEL_DOWNLOADED = "model_downloaded"
        private const val PREF_MODEL_VERSION = "model_version"
    }

    fun isModelDownloaded(): Boolean {
        val modelFile = getModelFile()
        val isDownloaded = modelFile.exists() &&
                prefs.getBoolean(PREF_MODEL_DOWNLOADED, false) &&
                prefs.getString(PREF_MODEL_VERSION, "") == MODEL_VERSION

        if (isDownloaded) {
            _downloadState.value = ModelDownloadState.DOWNLOADED
        }
        return isDownloaded
    }

    fun getModelFile(): File {
        return File(context.filesDir, MODEL_FILENAME)
    }

    fun downloadModel(): Result<File> {
        return try {
            _downloadState.value = ModelDownloadState.DOWNLOADING
            _downloadProgress.value = 0f

            val client = OkHttpClient()
            val request = Request.Builder().url(MODEL_URL).build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                _downloadState.value = ModelDownloadState.ERROR
                return Result.failure(IOException("Download failed: ${response.code}"))
            }

            val body = response.body ?: throw IOException("Empty response body")
            val contentLength = body.contentLength()
            val modelFile = getModelFile()

            body.byteStream().use { input ->
                FileOutputStream(modelFile).use { output ->
                    val buffer = ByteArray(8192)
                    var totalBytesRead = 0L
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead

                        if (contentLength > 0) {
                            val progress = (totalBytesRead.toFloat() / contentLength.toFloat())
                            _downloadProgress.value = progress
                        }
                    }
                }
            }

            // Mark as downloaded using the KTX .edit { } block
            prefs.edit {
                putBoolean(PREF_MODEL_DOWNLOADED, true)
                putString(PREF_MODEL_VERSION, MODEL_VERSION)
            }

            _downloadState.value = ModelDownloadState.DOWNLOADED
            _downloadProgress.value = 1f

            Result.success(modelFile)

        } catch (e: Exception) {
            _downloadState.value = ModelDownloadState.ERROR
            // Also delete potentially corrupted file on failure
            getModelFile().delete()
            Result.failure(e)
        }
    }

    fun deleteModel() {
        val modelFile = getModelFile()
        if (modelFile.exists()) {
            modelFile.delete()
        }
        // Update preferences using the KTX .edit { } block
        prefs.edit {
            putBoolean(PREF_MODEL_DOWNLOADED, false)
            remove(PREF_MODEL_VERSION)
        }
        _downloadState.value = ModelDownloadState.NOT_DOWNLOADED
        _downloadProgress.value = 0f
    }
}

enum class ModelDownloadState {
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED,
    ERROR
}