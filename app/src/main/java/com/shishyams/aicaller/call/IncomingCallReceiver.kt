package com.shishyams.aicaller.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

/**
 * Android's "phone state changed" broadcast. It is the only place an ordinary app is told the number of an incoming
 * call (it needs READ_PHONE_STATE and READ_CALL_LOG, which the app already asks for). The number can be missing from
 * the first RINGING broadcast and arrive in a second one, so CallManager waits a moment before it decides.
 */
class IncomingCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        @Suppress("DEPRECATION")
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        CallManager.get(context).onPhoneBroadcast(state, number)
    }
}
