package com.shishyams.aicaller.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * The runtime permissions automatic calling needs (Step 6).
 *
 *  - CALL_PHONE          place the call
 *  - READ_PHONE_STATE    know when the call starts / ends, and pick SIM 1 or SIM 2
 *  - READ_CALL_LOG       read the talk time afterwards, which is how "answered" is told from "no answer"
 *  - ANSWER_PHONE_CALLS  hang up when the time limit is reached or the admin presses Skip / Stop
 */
object DialerPermissions {

    private val needed = listOf(
        Manifest.permission.CALL_PHONE to "Phone",
        Manifest.permission.READ_PHONE_STATE to "Phone state",
        Manifest.permission.READ_CALL_LOG to "Call log",
        Manifest.permission.ANSWER_PHONE_CALLS to "Hang up calls"
    )

    fun all(): Array<String> = needed.map { it.first }.toTypedArray()

    /** Friendly names of the permissions that are still not granted. */
    fun missing(ctx: Context): List<String> =
        needed.filter { ContextCompat.checkSelfPermission(ctx, it.first) != PackageManager.PERMISSION_GRANTED }
            .map { it.second }

    fun has(ctx: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED
}
