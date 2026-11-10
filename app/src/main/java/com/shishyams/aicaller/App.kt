package com.shishyams.aicaller

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = getString(R.string.channel_desc)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val feedback = NotificationChannel(FEEDBACK_CHANNEL_ID, getString(R.string.fb_channel_name), NotificationManager.IMPORTANCE_HIGH)
        feedback.description = getString(R.string.fb_channel_desc)
        getSystemService(NotificationManager::class.java).createNotificationChannel(feedback)
        com.shishyams.aicaller.call.FeedbackPrompt.load(this)
    }

    companion object {
        const val CHANNEL_ID = "aic_link"
        const val FEEDBACK_CHANNEL_ID = "aic_feedback"
    }
}
