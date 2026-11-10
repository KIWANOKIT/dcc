package com.shishyams.aicaller.call

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.shishyams.aicaller.App
import com.shishyams.aicaller.MainActivity
import com.shishyams.aicaller.R
import com.shishyams.aicaller.net.ConnectionState

/**
 * "AI voice mode" (Step 7).
 *
 * Android only lets an app use the microphone from the background if a foreground service of type
 * "microphone" was started while the app was on screen. This tiny service is that: the person taps
 * "Start AI voice mode" in the app (so the app is visible), the service starts, and from then on the phone can
 * listen during calls even with the screen off or another app in front. While it runs the phone reports
 * voice_ready = true to the dashboard.
 */
class VoiceModeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!DialerPermissions.has(this, Manifest.permission.RECORD_AUDIO)) {
            running = false
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val n = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, n)
            }
            running = true
            ConnectionState.log("AI voice mode is on")
        } catch (e: Exception) {
            // Android refused (for example the app was not on screen). The person can simply tap the button again.
            running = false
            ConnectionState.log("AI voice mode could not start: ${e.javaClass.simpleName}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        ConnectionState.log("AI voice mode is off")
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.voice_notif_title))
            .setContentText(getString(R.string.voice_notif_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 1002

        /** True while the service is running (read by DeviceInfo and CallManager). */
        @Volatile var running: Boolean = false
            private set

        /** Must be called while the app is on screen. */
        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, VoiceModeService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, VoiceModeService::class.java))
            running = false
        }
    }
}
