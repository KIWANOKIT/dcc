package com.shishyams.aicaller.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.telephony.TelephonyManager
import com.shishyams.aicaller.BuildConfig
import com.shishyams.aicaller.call.DialerPermissions
import com.shishyams.aicaller.call.VoiceModeService
import org.json.JSONObject

/** What the phone reports to the dashboard. No permission beyond ACCESS_NETWORK_STATE is needed. */
object DeviceInfo {

    fun defaultName(): String {
        val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        return if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model".trim()
    }

    fun snapshot(ctx: Context): JSONObject {
        val o = JSONObject()
        val battery = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        if (level >= 0 && scale > 0) o.put("battery", level * 100 / scale)
        o.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
        o.put("network", network(ctx))
        o.put("sim_label", simLabel(ctx))
        o.put("app_version", BuildConfig.VERSION_NAME)
        o.put("android_version", Build.VERSION.RELEASE.orEmpty())

        // Calling readiness (app 1.1.0+): the dashboard only dials through a phone that reports ready.
        val missing = DialerPermissions.missing(ctx).toMutableList()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) missing.add("Android 9 or newer")
        if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) missing.add("Telephony")
        o.put("dial_ready", missing.isEmpty())
        o.put("dial_missing", missing.joinToString(", "))

        // Step 7 (app 1.2.0+): can the phone hear and speak during a call? Needs the microphone permission and
        // "AI voice mode" (the microphone foreground service) to be running.
        val voiceMissing = mutableListOf<String>()
        if (!DialerPermissions.has(ctx, android.Manifest.permission.RECORD_AUDIO)) voiceMissing.add("Microphone")
        if (!VoiceModeService.running) voiceMissing.add("AI voice mode")
        o.put("voice_ready", voiceMissing.isEmpty())
        o.put("voice_missing", voiceMissing.joinToString(", "))
        return o
    }

    private fun network(ctx: Context): String {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return "none"
        val net = cm.activeNetwork ?: return "none"
        val caps = cm.getNetworkCapabilities(net) ?: return "none"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }

    private fun simLabel(ctx: Context): String {
        val tm = ctx.getSystemService(TelephonyManager::class.java) ?: return "No SIM"
        if (tm.simState != TelephonyManager.SIM_STATE_READY) return "No SIM"
        val name = tm.simOperatorName?.trim().orEmpty()
        return if (name.isNotEmpty()) name else "SIM"
    }
}
