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
import com.s2s.mobile.config.AudioConfig

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

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.w(TAG, "onTrimMemory level=$level received by VoiceSessionService")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Listening"
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Voice assistant is active"
        val channelId = intent?.getStringExtra(EXTRA_CHANNEL_ID) ?: DEFAULT_CHANNEL_ID
        val notificationId = intent?.getIntExtra(EXTRA_NOTIFICATION_ID, DEFAULT_NOTIFICATION_ID) ?: DEFAULT_NOTIFICATION_ID
        val importance = intent?.getStringExtra(EXTRA_IMPORTANCE) ?: "LOW"
        val iconRes = intent?.getIntExtra(EXTRA_ICON_RES, android.R.drawable.ic_btn_speak_now)
            ?: android.R.drawable.ic_btn_speak_now

        createChannel(channelId, importance)

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    notificationId,
                    notification(channelId, iconRes, title, text),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(notificationId, notification(channelId, iconRes, title, text))
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

    private fun createChannel(channelId: String, importanceName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(channelId) != null) return
        val importance = when (importanceName) {
            "HIGH" -> NotificationManager.IMPORTANCE_HIGH
            "DEFAULT" -> NotificationManager.IMPORTANCE_DEFAULT
            "MIN" -> NotificationManager.IMPORTANCE_MIN
            "NONE" -> NotificationManager.IMPORTANCE_NONE
            else -> NotificationManager.IMPORTANCE_LOW
        }
        manager.createNotificationChannel(
            NotificationChannel(channelId, "Voice session", importance)
                .apply { description = "Shown while the assistant is listening" },
        )
    }

    private fun notification(channelId: String, iconRes: Int, title: String, text: String): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(iconRes)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
    }

    companion object {
        private const val TAG = "S2S-VoiceService"
        private const val DEFAULT_CHANNEL_ID = "s2s_voice_session_channel"
        private const val DEFAULT_NOTIFICATION_ID = 1002
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_CHANNEL_ID = "channel_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_IMPORTANCE = "importance"
        const val EXTRA_ICON_RES = "icon_res"

        /** Returns false if the service could not be started; the caller decides what that means. */
        fun start(context: Context, title: String?, text: String?, config: AudioConfig = AudioConfig()): Boolean =
            runCatching {
                val intent = Intent(context, VoiceSessionService::class.java)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_TEXT, text)
                    .putExtra(EXTRA_CHANNEL_ID, config.notificationChannelId)
                    .putExtra(EXTRA_NOTIFICATION_ID, config.notificationId)
                    .putExtra(EXTRA_IMPORTANCE, config.notificationImportance)
                    .putExtra(EXTRA_ICON_RES, config.notificationSmallIconRes)
                context.startForegroundService(intent)
                true
            }.getOrElse {
                Log.e(TAG, "startForegroundService refused", it)
                false
            }

        fun update(context: Context, title: String?, text: String?, config: AudioConfig = AudioConfig()): Boolean =
            start(context, title, text, config)

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, VoiceSessionService::class.java)) }
                .onFailure { Log.w(TAG, "stopService failed", it) }
        }
    }
}
