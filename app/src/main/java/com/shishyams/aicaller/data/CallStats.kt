package com.shishyams.aicaller.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import java.util.Calendar

/**
 * Today's call counts for the home screen, read from the phone's own call log (READ_CALL_LOG is already requested
 * for automatic calling), so it covers campaign calls and the owner's normal calls alike.
 *
 *  dialed        every outgoing call placed since midnight
 *  received      incoming calls that were answered
 *  notAnswered   incoming calls missed or rejected, plus outgoing calls nobody picked up (0 seconds talk time)
 */
object CallStats {

    data class Today(val dialed: Int, val received: Int, val notAnswered: Int)

    /** Counts for today (since local midnight), or null when the Call log permission is not granted / unreadable. */
    fun today(ctx: Context): Today? {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return null
        val midnight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        var dialed = 0
        var received = 0
        var notAnswered = 0
        return try {
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.TYPE, CallLog.Calls.DURATION),
                "${CallLog.Calls.DATE} >= ?",
                arrayOf(midnight.toString()),
                null
            )?.use { c ->
                val ti = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val di = c.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                while (c.moveToNext()) {
                    when (c.getInt(ti)) {
                        CallLog.Calls.OUTGOING_TYPE -> { dialed++; if (c.getInt(di) <= 0) notAnswered++ }
                        CallLog.Calls.INCOMING_TYPE -> received++
                        CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> notAnswered++
                    }
                }
            } ?: return null
            Today(dialed, received, notAnswered)
        } catch (e: Exception) {
            null
        }
    }
}
