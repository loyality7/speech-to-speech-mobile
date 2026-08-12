package com.s2s.mobile.model

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground Service to ensure model downloads continue reliably in the background
 * even when the app is minimized, screen is locked, or another app is opened.
 */
class ModelDownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val binder = LocalBinder()

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private var downloader: ModelDownloader? = null

    /** Held so a second startDownload can be refused rather than orphaning the first. */
    private var job: Job? = null

    sealed class DownloadState {
        object Idle : DownloadState()
        data class Progress(val progress: ModelProgress) : DownloadState()
        object Completed : DownloadState()
        data class Error(val message: String) : DownloadState()
    }

    inner class LocalBinder : Binder() {
        fun getService(): ModelDownloadService = this@ModelDownloadService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    fun startDownload(modelsDir: File, specs: List<ModelSpec>) {
        // Two runs would write the same .part files and the second would replace the
        // downloader reference, leaving the first uncancellable. One at a time.
        if (job?.isActive == true) {
            Log.i(TAG, "download already in progress, ignoring start request")
            return
        }

        val downloader = ModelDownloader(modelsDir).also { this.downloader = it }

        val notification = buildNotification("Starting download...", 0)
        startForeground(NOTIFICATION_ID, notification)

        job = serviceScope.launch {
            try {
                downloader.downloadAll(specs) { p ->
                    _downloadState.value = DownloadState.Progress(p)
                    updateNotification(
                        title = "Downloading ${p.modelName}",
                        progress = p.percent,
                    )
                }
                _downloadState.value = DownloadState.Completed
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } catch (e: Exception) {
                _downloadState.value = DownloadState.Error(e.message ?: "Download failed")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    fun stopDownload() {
        downloader?.cancelDownload()
        job?.cancel()
        job = null
        _downloadState.value = DownloadState.Idle
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Model Downloads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows progress for ongoing speech model downloads"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, progress: Int): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("S2S Model Manager")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(title: String, progress: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(title, progress))
    }

    companion object {
        private const val TAG = "S2S-DownloadService"
        private const val CHANNEL_ID = "s2s_model_download_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
