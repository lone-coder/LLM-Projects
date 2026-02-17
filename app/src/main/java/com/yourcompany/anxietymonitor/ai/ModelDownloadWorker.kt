package com.yourcompany.anxietymonitor.ai

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ModelDownloadWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        return@withContext try {
            // Create ModelManager directly (no Hilt injection)
            val modelManager = ModelManager(applicationContext)

            // Check if model is already downloaded
            if (modelManager.isModelDownloaded()) {
                Log.i(TAG, "Model already downloaded.")
                return@withContext Result.success()
            }

            // Download the model
            Log.d(TAG, "Starting model download...")
            val downloadResult = modelManager.downloadModel()
            if (downloadResult.isSuccess) {
                Log.i(TAG, "Model download successful.")
                Result.success()
            } else {
                Log.w(TAG, "Model download returned failure state.")
                Result.failure()
            }
        } catch (e: Exception) {
            // Log the exception to see the cause of the failure in Logcat
            Log.e(TAG, "Model download failed with an exception", e)
            Result.failure()
        }
    }

    companion object {
        // TAG for logging, used to identify messages from this class
        private const val TAG = "ModelDownloadWorker"
        const val WORK_NAME = "model_download_work"

        fun enqueue(context: Context) {
            val downloadRequest = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                downloadRequest
            )
        }
    }
}