package com.shishyams.aicaller.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives the radio's answer ("sent" or the reason it failed) for every SMS part handed to SmsManager. */
class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        SmsGateway.onSentResult(id, resultCode)
    }

    companion object {
        const val EXTRA_ID = "sms_id"
    }
}
