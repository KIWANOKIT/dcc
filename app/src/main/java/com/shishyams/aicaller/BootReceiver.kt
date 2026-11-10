package com.shishyams.aicaller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shishyams.aicaller.data.Prefs
import com.shishyams.aicaller.net.ConnectionService

/** Restarts the link after a reboot or an app update, but only if the phone is paired. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (Prefs.get(context).isPaired) {
                ConnectionService.start(context)
            }
        }
    }
}
