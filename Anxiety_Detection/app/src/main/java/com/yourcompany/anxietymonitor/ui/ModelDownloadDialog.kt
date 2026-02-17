package com.yourcompany.anxietymonitor.ui

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.yourcompany.anxietymonitor.R
import com.yourcompany.anxietymonitor.ai.ModelDownloadState
import com.yourcompany.anxietymonitor.ai.ModelManager
import kotlinx.coroutines.launch
import java.util.Locale

class ModelDownloadDialog(
    private val context: Context,
    private val modelManager: ModelManager,
    private val lifecycleOwner: LifecycleOwner
) {

    private var dialog: AlertDialog? = null
    private var progressBar: ProgressBar? = null
    private var statusText: TextView? = null

    fun showDownloadPrompt(onComplete: (Boolean) -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.ai_model_required_title))
            .setMessage(context.getString(R.string.ai_model_download_prompt))
            .setPositiveButton(context.getString(R.string.download)) { _, _ ->
                startDownload(onComplete)
            }
            .setNegativeButton(context.getString(R.string.later)) { _, _ ->
                onComplete(false)
            }
            .setCancelable(false)
            .show()
    }

    private fun startDownload(onComplete: (Boolean) -> Unit) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_model_download, null)
        progressBar = view.findViewById(R.id.progressBar)
        statusText = view.findViewById(R.id.statusText)

        dialog = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.downloading_ai_model_title))
            .setView(view)
            .setCancelable(false)
            .setNegativeButton(context.getString(R.string.cancel)) { _, _ ->
                // Cancel download
                onComplete(false)
            }
            .show()

        // Start download
        lifecycleOwner.lifecycleScope.launch {
            modelManager.downloadModel()
        }

        // Observe progress
        lifecycleOwner.lifecycleScope.launch {
            modelManager.downloadProgress.collect { progress ->
                progressBar?.progress = progress.toInt()
                statusText?.text = context.getString(
                    R.string.downloading_progress_format,
                    String.format(Locale.getDefault(), "%.0f", progress)
                )
            }
        }

        // Observe state
        lifecycleOwner.lifecycleScope.launch {
            modelManager.downloadState.collect { state ->
                when (state) {
                    ModelDownloadState.DOWNLOADED -> {
                        dialog?.dismiss()
                        onComplete(true)
                    }
                    ModelDownloadState.ERROR -> {
                        dialog?.dismiss()
                        showErrorDialog(onComplete)
                    }
                    else -> {}
                }
            }
        }
    }

    private fun showErrorDialog(onComplete: (Boolean) -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.download_failed_title))
            .setMessage(context.getString(R.string.download_failed_message))
            .setPositiveButton(context.getString(R.string.retry)) { _, _ ->
                startDownload(onComplete)
            }
            .setNegativeButton(context.getString(R.string.skip)) { _, _ ->
                onComplete(false)
            }
            .show()
    }
}