package com.shishyams.aicaller.call

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.shishyams.aicaller.App
import com.shishyams.aicaller.FeedbackActivity
import com.shishyams.aicaller.R
import com.shishyams.aicaller.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Answered calls that still wait for the caller's one-tap feedback (Interested / Not Interested / Call Later / DND).
 * The list is kept in the encrypted app storage, so nothing is lost if the app is closed before the person taps.
 * The answer itself goes to the dashboard through CallManager.submitFeedback(), which retries until it is accepted.
 */
object FeedbackPrompt {

    data class Item(val callId: Long, val token: String, val number: String)

    private const val MAX_WAITING = 30
    private const val NOTIF_ID = 4107
    private val lock = Any()

    private val _count = MutableStateFlow(-1)
    /** How many calls are waiting for feedback (-1 until the app has read the list once). */
    val count: StateFlow<Int> = _count

    /** True from the moment a call ends until the feedback screen has been shown for it once (so "Skip" is respected). */
    @Volatile var autoShow = false

    private fun read(ctx: Context): MutableList<Item> {
        val out = mutableListOf<Item>()
        try {
            val a = JSONArray(Prefs.get(ctx).pendingFeedback)
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                out.add(Item(o.optLong("call_id"), o.optString("token"), o.optString("number")))
            }
        } catch (e: Exception) { /* unreadable: start empty */ }
        return out
    }

    private fun write(ctx: Context, items: List<Item>) {
        val a = JSONArray()
        items.forEach { a.put(JSONObject().put("call_id", it.callId).put("token", it.token).put("number", it.number)) }
        Prefs.get(ctx).pendingFeedback = a.toString()
        _count.value = items.size
    }

    /** Reads the list once at start-up so the home screen can show how many are waiting. */
    fun load(ctx: Context) {
        synchronized(lock) { _count.value = read(ctx).size }
    }

    /** The newest call that is still waiting, or null. */
    fun latest(ctx: Context): Item? = synchronized(lock) { read(ctx).lastOrNull() }

    fun waiting(ctx: Context): Int = synchronized(lock) { read(ctx).size }

    /** A call has just ended after being answered: ask for feedback. */
    fun add(ctx: Context, callId: Long, token: String, number: String) {
        synchronized(lock) {
            val items = read(ctx)
            if (items.none { it.callId == callId }) items.add(Item(callId, token, number))
            while (items.size > MAX_WAITING) items.removeAt(0)
            write(ctx, items)
        }
        autoShow = true
        if (!com.shishyams.aicaller.MainActivity.visible && !FeedbackActivity.visible) notifyWaiting(ctx)   // the app is not on screen: say so with a notification
    }

    fun remove(ctx: Context, callId: Long) {
        val left = synchronized(lock) {
            val items = read(ctx).filter { it.callId != callId }
            write(ctx, items)
            items.size
        }
        if (left == 0) ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }

    fun clear(ctx: Context) {
        synchronized(lock) { write(ctx, emptyList()) }
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }

    private fun notifyWaiting(ctx: Context) {
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, FeedbackActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, App.FEEDBACK_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.fb_notif_title))
            .setContentText(ctx.getString(R.string.fb_notif_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)       // pops up straight away when the phone is locked / idle (where Android allows it)
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
        } catch (e: SecurityException) { /* notifications not allowed: the home-screen banner still shows it */ }
    }
}
