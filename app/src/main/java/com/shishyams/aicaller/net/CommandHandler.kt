package com.shishyams.aicaller.net

import android.content.Context
import com.shishyams.aicaller.BuildConfig
import com.shishyams.aicaller.call.CallManager
import com.shishyams.aicaller.data.DeviceInfo
import org.json.JSONObject

/**
 * Runs commands pushed from the dashboard: link checks (ping, refresh_info) and calling (dial, hangup).
 */
class CommandHandler(private val ctx: Context) {

    class Result(val ok: Boolean, val data: JSONObject? = null, val error: String? = null)

    fun handle(command: String, payload: JSONObject): Result =
        when (command) {
            "ping" -> Result(
                true,
                JSONObject()
                    .put("device_time_ms", System.currentTimeMillis())
                    .put("app_version", BuildConfig.VERSION_NAME)
            )
            "refresh_info" -> Result(true, DeviceInfo.snapshot(ctx))
            "dial" -> CallManager.get(ctx).dial(payload)
            "hangup" -> CallManager.get(ctx).hangupCommand(payload)
            else -> Result(false, null, "unsupported_command:$command")
        }
}
