package com.s2s.mobile.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Keeps the microphone alive while the app is not in the foreground.
 *
 * Android stops delivering audio to a backgrounded process, so without this the
 * assistant goes deaf the moment the user switches apps or locks the screen —
 * and does so silently, which reads as "it stopped working" rather than as a
 * permission problem.
 *
 * The engine starts and stops this around its own lifecycle. An app that already
 * runs its own microphone-typed foreground service should set
 * `AudioConfig.manageForegroundService = false` and keep using its own; two
 * services of the same type is redundant, not additive.
 */
class VoiceSessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Listening"
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Voice assistant is active"

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification(title, text),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification(title, text))
            }
        }.onFailure {
            // Android 14+ refuses a microphone-typed service started while the app
            // is in the background, and throws rather than degrading. Stop cleanly:
            // the engine surfaces the failure, instead of the process dying here.
            Log.e(TAG, "could not enter foreground", it)
            stopSelf()
        }

        // Restarting without the app is pointless — the engine's models live in the
        // process that died with it.
        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Voice session", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while the assistant is listening" },
        )
    }

    private fun notification(title: String, text: String): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
    }

    companion object {
        private const val TAG = "S2S-VoiceService"
        private const val CHANNEL_ID = "s2s_voice_session_channel"
        private const val NOTIFICATION_ID = 1002
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"

        /** Returns false if the service could not be started; the caller decides what that means. */
        fun start(context: Context, title: String?, text: String?): Boolean = runCatching {
            val intent = Intent(context, VoiceSessionService::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TEXT, text)
            context.startForegroundService(intent)
            true
        }.getOrElse {
            Log.e(TAG, "startForegroundService refused", it)
            false
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, VoiceSessionService::class.java)) }
                .onFailure { Log.w(TAG, "stopService failed", it) }
        }
    }
}
