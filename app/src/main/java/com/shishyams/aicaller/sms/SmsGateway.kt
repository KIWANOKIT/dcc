package com.shishyams.aicaller.sms

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import com.shishyams.aicaller.data.Prefs
import com.shishyams.aicaller.net.CommandHandler
import com.shishyams.aicaller.net.ConnectionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * SMS Gateway — Sim SMS Sender.
 *
 * Turns this phone into an SMS gateway for the dashboard, the same idea as the SMS Gateway for Android app
 * (https://sms-gate.app) that Integration Settings → "SMS Gateway — Sim SMS Sender" talks to, but built into
 * this app so the phone that already makes the AI calls can also send SMS from its own SIM — no second app.
 *
 * The dashboard queues a `send_sms` command over the existing secure link; [send] hands the text to Android's
 * SmsManager (long texts are split into parts automatically) and waits for the radio's answer, so the dashboard
 * learns whether the message really left the phone.
 *
 * Safety rails, all adjustable on the phone: a master switch, a pause between messages, and a daily limit.
 */
object SmsGateway {

    class Outcome(val ok: Boolean, val error: String? = null, val parts: Int = 0, val simSlot: Int = 0)

    private class Tracker(val total: Int) {
        val done = CompletableDeferred<String?>()   // null = every part was sent, otherwise the reason it failed
        val okParts = AtomicInteger(0)
    }

    private val trackers = ConcurrentHashMap<String, Tracker>()
    private val requestCodes = AtomicInteger(1000)
    private val gate = Mutex()                       // one message at a time, so the pause between them is real
    @Volatile private var lastSentAt = 0L

    private const val SEND_TIMEOUT_MS = 30_000L
    private const val MAX_TEXT = 1_600

    /* ── Readiness (reported to the dashboard with every heartbeat) ─────────── */

    /** Plain-words list of what still stops this phone from sending SMS; empty = ready. */
    fun missing(ctx: Context): List<String> {
        val out = mutableListOf<String>()
        if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) out.add("Telephony")
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) out.add("SMS permission")
        if (!Prefs.get(ctx).smsEnabled) out.add("SMS gateway switched off")
        return out
    }

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /* ── Command from the dashboard ─────────────────────────────────────────── */

    suspend fun sendCommand(ctx: Context, payload: JSONObject): CommandHandler.Result {
        val to = payload.optString("to")
        val text = payload.optString("message")
        val slot = if (payload.has("sim")) payload.optInt("sim", -1) else -1   // -1 = use the choice made on the phone
        val o = send(ctx, to, text, slot)
        val prefs = Prefs.get(ctx)
        return if (o.ok) {
            CommandHandler.Result(
                true,
                JSONObject().put("parts", o.parts).put("sim", o.simSlot).put("sent_today", prefs.smsSentToday)
            )
        } else {
            CommandHandler.Result(false, null, o.error)
        }
    }

    /* ── Sending ────────────────────────────────────────────────────────────── */

    /** @param simSlot 0 = default SIM, 1 / 2 = that SIM, -1 = whatever the phone's own setting says. */
    suspend fun send(ctx: Context, rawTo: String, rawText: String, simSlot: Int = -1): Outcome {
        val app = ctx.applicationContext
        val prefs = Prefs.get(app)

        val missing = missing(app)
        if (missing.isNotEmpty()) return fail(prefs, "Phone not ready: ${missing.joinToString(", ")}", count = false)

        val to = cleanNumber(rawTo) ?: return fail(prefs, "Invalid phone number", count = false)
        val text = rawText.trim()
        if (text.isEmpty()) return fail(prefs, "Empty message", count = false)
        if (text.length > MAX_TEXT) return fail(prefs, "Message too long (max $MAX_TEXT characters)", count = false)

        val limit = prefs.smsDailyLimit
        if (limit > 0 && prefs.smsSentToday >= limit) {
            return fail(prefs, "Daily limit of $limit messages reached on this phone", count = false)
        }

        return gate.withLock {
            // Respect the pause between messages.
            val wait = prefs.smsDelaySec * 1_000L - (SystemClock.elapsedRealtime() - lastSentAt)
            if (lastSentAt > 0 && wait > 0) delay(wait)

            val slot = if (simSlot >= 0) simSlot else prefs.smsSim
            val subId = subscriptionFor(app, slot)
            if (slot > 0 && subId < 0) return@withLock fail(prefs, "SIM $slot was not found in this phone")

            val outcome = try {
                dispatch(app, to, text, subId, slot)
            } catch (e: SecurityException) {
                fail(prefs, "Android blocked the SMS (permission)", count = false)
            } catch (e: Exception) {
                fail(prefs, e.message ?: "Could not send")
            }
            lastSentAt = SystemClock.elapsedRealtime()
            if (outcome.ok) {
                prefs.smsCount(true)
                ConnectionState.log("SMS sent to ${mask(to)} (${outcome.parts} part${if (outcome.parts == 1) "" else "s"})")
            }
            outcome
        }
    }

    private suspend fun dispatch(ctx: Context, to: String, text: String, subId: Int, slot: Int): Outcome {
        val sms = manager(ctx, subId)
        val parts = sms.divideMessage(text)
        val id = java.util.UUID.randomUUID().toString()
        val tracker = Tracker(parts.size)
        trackers[id] = tracker
        try {
            val sentIntents = ArrayList<PendingIntent>(parts.size)
            for (i in parts.indices) sentIntents.add(sentIntent(ctx, id, i))
            if (parts.size == 1) sms.sendTextMessage(to, null, parts[0], sentIntents[0], null)
            else sms.sendMultipartTextMessage(to, null, parts, sentIntents, null)

            val result = withTimeoutOrNull(SEND_TIMEOUT_MS) { tracker.done.await() }
                ?: if (tracker.done.isCompleted) null else "The phone did not confirm the send (no signal?)"
            return if (result == null) Outcome(true, null, parts.size, slot)
            else fail(Prefs.get(ctx), result)
        } finally {
            trackers.remove(id)
        }
    }

    private fun sentIntent(ctx: Context, id: String, part: Int): PendingIntent {
        val intent = Intent(ctx, SmsStatusReceiver::class.java)
            .setData(Uri.parse("aicsms://$id/$part"))          // makes every part's PendingIntent unique
            .putExtra(SmsStatusReceiver.EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            ctx, requestCodes.incrementAndGet(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** Called by [SmsStatusReceiver] when the radio reports the result for one part. */
    internal fun onSentResult(id: String, resultCode: Int) {
        val t = trackers[id] ?: return
        if (resultCode == Activity.RESULT_OK) {
            if (t.okParts.incrementAndGet() >= t.total) t.done.complete(null)
        } else {
            t.done.complete(reasonFor(resultCode))
        }
    }

    private fun reasonFor(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "Phone or carrier refused the SMS (generic failure)"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "Radio is off (airplane mode?)"
        SmsManager.RESULT_ERROR_NULL_PDU -> "Invalid message"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "No mobile service"
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "Android's SMS sending limit was reached"
        SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED, SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED ->
            "Sending to this short code is not allowed"
        else -> "SMS failed (code $code)"
    }

    /* ── SIM / SmsManager helpers ───────────────────────────────────────────── */

    /** SIM slot 1 or 2 -> Android subscription id; -1 when slot is 0 (default SIM) or the SIM is missing. */
    private fun subscriptionFor(ctx: Context, slot: Int): Int {
        if (slot <= 0) return -1
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return -1
        return try {
            val sm = ctx.getSystemService(SubscriptionManager::class.java)
            @Suppress("MissingPermission")
            sm?.activeSubscriptionInfoList?.firstOrNull { it.simSlotIndex == slot - 1 }?.subscriptionId ?: -1
        } catch (e: SecurityException) {
            -1
        }
    }

    @Suppress("DEPRECATION")
    private fun manager(ctx: Context, subId: Int): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = ctx.getSystemService(SmsManager::class.java)
            if (subId >= 0) base.createForSubscriptionId(subId) else base
        } else {
            if (subId >= 0) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
        }

    /** Keeps a leading + and digits only; 5–15 digits. */
    fun cleanNumber(raw: String): String? {
        val t = raw.trim()
        val plus = t.startsWith("+")
        val digits = t.filter { it.isDigit() }
        if (digits.length < 5 || digits.length > 15) return null
        return if (plus) "+$digits" else digits
    }

    private fun mask(n: String): String = if (n.length <= 4) n else "•••" + n.takeLast(4)

    private fun fail(prefs: Prefs, reason: String, count: Boolean = true): Outcome {
        if (count) prefs.smsCount(false)
        ConnectionState.log("SMS failed: $reason")
        return Outcome(false, reason)
    }
}
