package com.shishyams.aicaller.call

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.shishyams.aicaller.data.Prefs
import com.shishyams.aicaller.net.ApiClient
import com.shishyams.aicaller.net.ApiException
import com.shishyams.aicaller.net.CommandHandler
import com.shishyams.aicaller.net.ConnectionState
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Places SIM calls on the dashboard's command, watches them, hangs them up, and reports how each one ended.
 *
 *  dial command    -> permission + safety checks -> TelecomManager.placeCall() from the chosen SIM
 *                     -> the command is acknowledged straight away ("the call was placed")
 *  phone state     -> OFFHOOK means the call is being dialled, IDLE afterwards means it is over
 *  call over       -> the call log tells the talk time: > 0 answered, 0 no answer / busy
 *                     -> a "call_event" is sent to the server (persisted and retried until it is accepted)
 *  hangup command  -> TelecomManager.endCall()
 *  time limit      -> the phone hangs up by itself after max_sec, even if it loses the server
 *  realtime block  -> (Step 7) a VoiceBridge listens and plays the AI's replies for the length of the call
 *
 * Why not simply read the call state to detect "answered"? Android only tells ordinary apps that a call is
 * active (OFFHOOK) from the moment it starts dialling, not when the other person picks up. The call log
 * entry written afterwards is the dependable source of truth without becoming the phone's default dialer app.
 * Voicemail pick-ups therefore count as answered (they have talk time).
 */
class CallManager private constructor(appContext: Context) {

    private class Active(val callId: Long, val token: String, val number: String, val maxSec: Int, val inbound: Boolean = false) {
        val placedAt: Long = System.currentTimeMillis()
        @Volatile var offhookAt: Long = 0L
        @Volatile var ended: Boolean = false
        val timers = CopyOnWriteArrayList<Runnable>()
        @Volatile var bridge: VoiceBridge? = null      // Step 7: lets the AI talk on this call
    }

    private val ctx: Context = appContext
    private val prefs: Prefs = Prefs.get(appContext)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val flushing = AtomicBoolean(false)
    private val retryScheduled = AtomicBoolean(false)
    private val pendingLock = Any()

    @Volatile private var active: Active? = null
    private var listening = false
    private var legacyListener: Any? = null
    private var modernCallback: Any? = null

    private fun telephony(): TelephonyManager? = ctx.getSystemService(TelephonyManager::class.java)
    private fun telecom(): TelecomManager? = ctx.getSystemService(TelecomManager::class.java)

    /* ── Phone state ────────────────────────────────────────────────────────── */

    /** Starts watching the phone's call state (idempotent). Needs READ_PHONE_STATE. */
    @Synchronized
    @SuppressLint("MissingPermission")
    fun ensureListening() {
        if (listening || !DialerPermissions.has(ctx, Manifest.permission.READ_PHONE_STATE)) return
        val t = telephony() ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = StateCallback { state -> onState(state) }
                t.registerTelephonyCallback(ContextCompat.getMainExecutor(ctx), cb)
                modernCallback = cb
            } else {
                // PhoneStateListener must be created on a thread that has a Looper.
                main.post {
                    @Suppress("DEPRECATION")
                    val l = object : PhoneStateListener() {
                        @Deprecated("Deprecated in Java")
                        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                            onState(state)
                        }
                    }
                    @Suppress("DEPRECATION")
                    t.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
                    legacyListener = l
                }
            }
            listening = true
        } catch (e: Exception) {
            ConnectionState.log("Could not watch call state: ${e.message}")
        }
    }

    private fun onState(state: Int) {
        val a = active ?: return
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> if (a.offhookAt == 0L) a.offhookAt = System.currentTimeMillis()
            TelephonyManager.CALL_STATE_IDLE -> if (a.offhookAt != 0L) finish(a, null)
        }
    }

    /* ── Incoming calls (Step 12) ───────────────────────────────────────────── */

    @Volatile private var ringNumber: String? = null
    @Volatile private var ringTimer: Runnable? = null
    private val inboundBusy = AtomicBoolean(false)

    /**
     * Called by IncomingCallReceiver for every phone-state broadcast. On RINGING the app waits a few seconds (so the
     * owner can pick up first, and so the number has arrived), then asks the server whether to answer.
     */
    fun onPhoneBroadcast(state: String, number: String?) {
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                if (!number.isNullOrBlank()) ringNumber = number
                if (ringTimer != null) return
                if (!prefs.isPaired || active != null || !VoiceModeService.running) return
                if (!DialerPermissions.has(ctx, Manifest.permission.RECORD_AUDIO) ||
                    !DialerPermissions.has(ctx, Manifest.permission.ANSWER_PHONE_CALLS) ||
                    !DialerPermissions.has(ctx, Manifest.permission.READ_PHONE_STATE)) return
                val r = Runnable { ringTimer = null; handleRinging() }
                ringTimer = r
                main.postDelayed(r, ANSWER_DELAY_MS)
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK, TelephonyManager.EXTRA_STATE_IDLE -> {
                // Picked up by the owner, or the caller gave up: do not answer.
                ringTimer?.let { main.removeCallbacks(it) }
                ringTimer = null
                if (state == TelephonyManager.EXTRA_STATE_IDLE) ringNumber = null
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleRinging() {
        val t = telephony() ?: return
        if (t.callState != TelephonyManager.CALL_STATE_RINGING || active != null) return
        if (!inboundBusy.compareAndSet(false, true)) return
        val number = ringNumber.orEmpty()
        worker.execute {
            try {
                val resp = try {
                    ApiClient(prefs.serverUrl, prefs.deviceToken).inboundStart(number)
                } catch (e: Exception) {
                    ConnectionState.log("Incoming call: server not reachable — left to ring")
                    return@execute
                }
                if (!resp.optBoolean("answer", false)) {
                    ConnectionState.log("Incoming call left to ring (${resp.optString("reason")})")
                    return@execute
                }
                main.post { answerInbound(resp, number) }
            } finally {
                inboundBusy.set(false)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun answerInbound(resp: JSONObject, number: String) {
        val callId = resp.optLong("call_id")
        val token = resp.optString("token")
        if (callId <= 0 || token.length != 32) return
        val maxSec = resp.optInt("max_sec", 180).coerceIn(30, 900)
        val rt = resp.optJSONObject("realtime") ?: JSONObject()
        val t = telephony()
        if (t == null || t.callState != TelephonyManager.CALL_STATE_RINGING || active != null) {
            // The caller hung up (or the owner answered) while the server was thinking.
            reportInboundFailed(callId, token, "missed_before_answer")
            return
        }
        val a = Active(callId, token, number, maxSec, inbound = true)
        active = a
        ensureListening()
        val accepted = try {
            telecom()?.acceptRingingCall()
            true
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            false
        }
        if (!accepted) {
            active = null
            reportInboundFailed(callId, token, "answer_failed")
            return
        }
        ConnectionState.log("Answering an incoming call (#$callId)")
        val greeting = resp.optString("audio_b64", "").ifEmpty { null }
        val bridge = VoiceBridge(ctx, callId, token, rt, greetingB64 = greeting, inbound = true) { hangUp() }
        a.bridge = bridge
        bridge.start()

        // Watchdog 1: Android never connected the call.
        val notConnected = Runnable { if (!a.ended && a.offhookAt == 0L) finish(a, "not_placed") }
        // Watchdog 2: time limit — hang up locally, whether or not the server is reachable.
        val limit = Runnable {
            if (!a.ended) {
                hangUp()
                val giveUp = Runnable { if (!a.ended) finish(a, "hangup_failed") }
                a.timers.add(giveUp)
                main.postDelayed(giveUp, 15_000L)
            }
        }
        a.timers.add(notConnected); a.timers.add(limit)
        main.postDelayed(notConnected, 10_000L)
        main.postDelayed(limit, maxSec * 1_000L)
    }

    private fun reportInboundFailed(callId: Long, token: String, reason: String) {
        enqueueEvent(
            JSONObject().put("call_id", callId).put("token", token).put("event", "ended")
                .put("outcome", "failed").put("duration_sec", 0).put("reason", reason)
        )
    }

    /* ── Commands ───────────────────────────────────────────────────────────── */

    @SuppressLint("MissingPermission")
    fun dial(p: JSONObject): CommandHandler.Result {
        val callId = p.optLong("call_id")
        val token = p.optString("token")
        val number = p.optString("number")
        val slot = p.optInt("sim_slot", 0)
        val maxSec = p.optInt("max_sec", 60).coerceIn(20, 600)
        val rt = p.optJSONObject("realtime")?.takeIf { it.optBoolean("enabled") }

        if (callId <= 0 || token.length != 32 || !NUMBER_RE.matches(number)) {
            return CommandHandler.Result(false, null, "bad_dial_request")
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            // Automatic hang-up (needed to keep the one-by-one pace) exists from Android 9.
            return CommandHandler.Result(false, null, "android_version:9_required")
        }
        if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            return CommandHandler.Result(false, null, "no_telephony")
        }
        val missing = DialerPermissions.missing(ctx)
        if (missing.isNotEmpty()) {
            return CommandHandler.Result(false, null, "permission_missing:" + missing.joinToString(","))
        }
        val t = telephony() ?: return CommandHandler.Result(false, null, "no_telephony")
        if (active != null || t.callState != TelephonyManager.CALL_STATE_IDLE) {
            return CommandHandler.Result(false, null, "phone_busy")   // the phone's owner is on a call
        }

        if (rt != null) {
            // The AI is meant to talk: never place a call that would stay silent.
            if (!DialerPermissions.has(ctx, Manifest.permission.RECORD_AUDIO)) {
                return CommandHandler.Result(false, null, "permission_missing:Microphone")
            }
            if (!VoiceModeService.running) return CommandHandler.Result(false, null, "voice_mode_off")
        }

        var handle: PhoneAccountHandle? = null
        if (slot > 0) {
            handle = handleForSlot(slot) ?: return CommandHandler.Result(false, null, "sim_not_found:$slot")
        }

        ensureListening()
        val a = Active(callId, token, number, maxSec)
        active = a
        try {
            val extras = Bundle()
            if (handle != null) extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
            // placeCall (not ACTION_CALL) because Android 10+ stops background services starting call screens.
            telecom()?.placeCall(Uri.fromParts("tel", number, null), extras)
                ?: run { active = null; return CommandHandler.Result(false, null, "no_telephony") }
        } catch (e: SecurityException) {
            active = null
            return CommandHandler.Result(false, null, "permission_missing:Phone")
        } catch (e: Exception) {
            active = null
            return CommandHandler.Result(false, null, "dial_failed:" + (e.javaClass.simpleName))
        }
        ConnectionState.log("Calling (#$callId)")
        if (rt != null) {
            val bridge = VoiceBridge(ctx, callId, token, rt) { hangUp() }
            a.bridge = bridge
            bridge.start()
        }

        // Watchdog 1: the call never started dialling (airplane mode, no signal, number refused).
        val notPlaced = Runnable { if (!a.ended && a.offhookAt == 0L) finish(a, "not_placed") }
        // Watchdog 2: time limit — hang up locally, whether or not the server is reachable.
        val limit = Runnable {
            if (!a.ended) {
                hangUp()
                val giveUp = Runnable { if (!a.ended) finish(a, "hangup_failed") }
                a.timers.add(giveUp)
                main.postDelayed(giveUp, 15_000L)
            }
        }
        a.timers.add(notPlaced); a.timers.add(limit)
        main.postDelayed(notPlaced, 20_000L)
        main.postDelayed(limit, maxSec * 1_000L)

        return CommandHandler.Result(true, JSONObject().put("placed", true).put("sim_slot", slot))
    }

    fun hangupCommand(p: JSONObject): CommandHandler.Result {
        val a = active ?: return CommandHandler.Result(true, JSONObject().put("already_ended", true))
        if (a.token != p.optString("token")) return CommandHandler.Result(false, null, "call_mismatch")
        return if (hangUp()) CommandHandler.Result(true, JSONObject().put("hangup", true))
        else CommandHandler.Result(false, null, "hangup_failed")
    }

    @SuppressLint("MissingPermission")
    private fun hangUp(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        if (!DialerPermissions.has(ctx, Manifest.permission.ANSWER_PHONE_CALLS)) return false
        return try {
            telecom()?.endCall() ?: false
        } catch (e: Exception) {
            false
        }
    }

    /** SIM 1 / SIM 2 -> the Telecom account that dials through it, or null if that SIM is not in the phone. */
    @SuppressLint("MissingPermission")
    private fun handleForSlot(slot: Int): PhoneAccountHandle? {
        return try {
            val sm = ctx.getSystemService(SubscriptionManager::class.java) ?: return null
            val sub = sm.getActiveSubscriptionInfoForSimSlotIndex(slot - 1) ?: return null
            val tel = telephony() ?: return null
            val tc = telecom() ?: return null
            for (h in tc.callCapablePhoneAccounts) {
                val matches = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    tel.createForPhoneAccountHandle(h)?.subscriptionId == sub.subscriptionId
                } else {
                    val icc = sub.iccId
                    h.id == sub.subscriptionId.toString() || (!icc.isNullOrEmpty() && h.id.contains(icc))
                }
                if (matches) return h
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /* ── A call is over ─────────────────────────────────────────────────────── */

    /** [forced] is null for a normal end, or "not_placed" / "hangup_failed" when a watchdog closed it. */
    private fun finish(a: Active, forced: String?) {
        synchronized(a) {
            if (a.ended) return
            a.ended = true
        }
        a.timers.forEach { main.removeCallbacks(it) }
        if (active === a) active = null
        val bridge = a.bridge
        bridge?.stop()

        // The call-log lookup waits a few seconds for Android to write the entry — keep it off the main thread.
        worker.execute {
            val dialStart = if (a.offhookAt > 0) a.offhookAt else a.placedAt
            val elapsedSec = ((System.currentTimeMillis() - dialStart) / 1000).toInt()
            var outcome: String
            var duration = 0
            val reason: String
            when {
                forced == "not_placed" -> { outcome = "failed"; reason = "not_placed" }
                forced == "hangup_failed" -> { outcome = "failed"; reason = "hangup_failed" }
                else -> {
                    val talk = talkSeconds(a)
                    when {
                        talk == null && a.inbound && a.offhookAt > 0 -> { outcome = "answered"; duration = elapsedSec; reason = "call_log_unavailable" }
                        talk == null -> { outcome = "no_answer"; reason = "call_log_unavailable" }
                        talk > 0 -> { outcome = "answered"; duration = talk; reason = "answered" }
                        elapsedSec < SHORT_CALL_SEC -> { outcome = "busy"; reason = "busy" }
                        else -> { outcome = "no_answer"; reason = "no_answer" }
                    }
                }
            }
            ConnectionState.log("Call #${a.callId} ended: $outcome")
            enqueueEvent(
                JSONObject()
                    .put("call_id", a.callId)
                    .put("token", a.token)
                    .put("event", "ended")
                    .put("outcome", outcome)
                    .put("duration_sec", duration)
                    .put("reason", reason)
                    .also { if (bridge != null) it.put("mic_peak", bridge.micPeak) }
            )
            // An answered call: ask the caller how it went (Interested / Not Interested / Call Later / DND).
            if (outcome == "answered") FeedbackPrompt.add(ctx, a.callId, a.token, a.number)
        }
    }

    /** Talk time (seconds) of the outgoing call to this number, or null if the call log cannot be read. */
    private fun talkSeconds(a: Active): Int? {
        if (!DialerPermissions.has(ctx, Manifest.permission.READ_CALL_LOG)) return null
        val target = digits(a.number).takeLast(10)
        for (attempt in 0 until 5) {
            if (attempt > 0) {
                try { Thread.sleep(1_200L) } catch (e: InterruptedException) { return null }
            }
            try {
                val cursor = ctx.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DURATION),
                    "${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.TYPE} = ?",
                    arrayOf((a.placedAt - 20_000L).toString(), (if (a.inbound) CallLog.Calls.INCOMING_TYPE else CallLog.Calls.OUTGOING_TYPE).toString()),
                    "${CallLog.Calls.DATE} DESC"
                )
                cursor?.use { c ->
                    val ni = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                    val di = c.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                    while (c.moveToNext()) {
                        if (target.isEmpty() || digits(c.getString(ni).orEmpty()).takeLast(10) == target) return c.getInt(di)
                    }
                }
            } catch (e: SecurityException) {
                return null
            } catch (e: Exception) {
                // try again
            }
        }
        return null
    }

    private fun digits(s: String): String = s.filter { it.isDigit() }

    /* ── Reporting results (persisted, retried until the server accepts them) ── */

    private fun readPending(): JSONArray =
        try { JSONArray(prefs.pendingEvents) } catch (e: Exception) { JSONArray() }

    private fun enqueueEvent(e: JSONObject) {
        synchronized(pendingLock) {
            val all = readPending().apply { put(e) }
            val kept = JSONArray()
            for (i in maxOf(0, all.length() - MAX_PENDING) until all.length()) kept.put(all.get(i))
            prefs.pendingEvents = kept.toString()
        }
        flushAsync()
    }

    private fun dropFirst() {
        synchronized(pendingLock) {
            val all = readPending()
            val rest = JSONArray()
            for (i in 1 until all.length()) rest.put(all.get(i))
            prefs.pendingEvents = rest.toString()
        }
    }

    /** True while this app has a call of its own going (the feedback screen waits until it is over). */
    fun isOnCall(): Boolean = active != null

    /** The caller's one-tap feedback for an answered call: interested | not_interested | callback | dnc. Sent like a call result (retried until accepted). */
    fun submitFeedback(callId: Long, token: String, feedback: String) {
        enqueueEvent(JSONObject().put("call_id", callId).put("token", token).put("event", "feedback").put("feedback", feedback))
    }

    fun hasPending(): Boolean = synchronized(pendingLock) { readPending().length() > 0 }

    /** Sends any results still waiting (call this whenever the connection is known to be good). */
    fun flushAsync() {
        if (!hasPending() || !flushing.compareAndSet(false, true)) return
        worker.execute {
            try {
                flushNow()
            } finally {
                flushing.set(false)
            }
        }
    }

    private fun flushNow() {
        while (true) {
            val first = synchronized(pendingLock) { readPending().optJSONObject(0) } ?: return
            if (!prefs.isPaired) {
                synchronized(pendingLock) { prefs.pendingEvents = "[]" }
                return
            }
            try {
                ApiClient(prefs.serverUrl, prefs.deviceToken).callEvent(first)
                dropFirst()
            } catch (e: ApiException) {
                // 4xx = the server will never accept this report (unpaired / malformed): drop it. 5xx = try again.
                if (e.http in 400..499) dropFirst() else { scheduleRetry(); return }
            } catch (e: IOException) {
                scheduleRetry()
                return
            }
        }
    }

    private fun scheduleRetry() {
        if (retryScheduled.compareAndSet(false, true)) {
            main.postDelayed({
                retryScheduled.set(false)
                flushAsync()
            }, 10_000L)
        }
    }

    companion object {
        private val NUMBER_RE = Regex("^\\+?[0-9]{6,15}$")
        private const val ANSWER_DELAY_MS = 2_500L     // incoming calls: wait ~3 rings before answering
        private const val SHORT_CALL_SEC = 12      // an unanswered call this short was refused / busy, not left to ring out
        private const val MAX_PENDING = 50

        @Volatile private var instance: CallManager? = null

        fun get(ctx: Context): CallManager =
            instance ?: synchronized(this) {
                instance ?: CallManager(ctx.applicationContext).also { instance = it }
            }
    }
}

@RequiresApi(Build.VERSION_CODES.S)
private class StateCallback(private val onChange: (Int) -> Unit) :
    TelephonyCallback(), TelephonyCallback.CallStateListener {
    override fun onCallStateChanged(state: Int) {
        onChange(state)
    }
}
