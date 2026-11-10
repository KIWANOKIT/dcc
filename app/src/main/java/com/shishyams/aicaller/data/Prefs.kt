package com.shishyams.aicaller.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * Everything the app remembers, stored encrypted (AES-256, key kept in the Android Keystore).
 * The device token is a credential, so it never touches plain storage.
 */
class Prefs private constructor(context: Context) {

    private val sp: SharedPreferences = open(context)

    var serverUrl: String
        get() = sp.getString(K_SERVER, "") ?: ""
        set(value) = sp.edit { putString(K_SERVER, value) }

    var deviceToken: String?
        get() = sp.getString(K_TOKEN, null)
        set(value) = sp.edit { if (value == null) remove(K_TOKEN) else putString(K_TOKEN, value) }

    var instituteName: String
        get() = sp.getString(K_INSTITUTE, "") ?: ""
        set(value) = sp.edit { putString(K_INSTITUTE, value) }

    var deviceName: String
        get() = sp.getString(K_NAME, "") ?: ""
        set(value) = sp.edit { putString(K_NAME, value) }

    /** Public wss:// address announced by the server; empty means "use HTTPS only". */
    var wsUrl: String
        get() = sp.getString(K_WS, "") ?: ""
        set(value) = sp.edit { putString(K_WS, value) }

    var heartbeatSec: Int
        get() = sp.getInt(K_HEARTBEAT, 30)
        set(value) = sp.edit { putInt(K_HEARTBEAT, value) }

    var pollHoldSec: Int
        get() = sp.getInt(K_HOLD, 20)
        set(value) = sp.edit { putInt(K_HOLD, value) }

    /** Call results (JSON array) waiting to be accepted by the server; see CallManager. */
    var pendingEvents: String
        get() = sp.getString(K_PENDING, "[]") ?: "[]"
        set(value) = sp.edit { putString(K_PENDING, value) }

    /** Answered calls waiting for the caller's one-tap feedback (JSON array); see FeedbackPrompt. */
    var pendingFeedback: String
        get() = sp.getString(K_FEEDBACK, "[]") ?: "[]"
        set(value) = sp.edit { putString(K_FEEDBACK, value) }

    /** My-voice sample: play it automatically when a call is answered. */
    var autoPlayMyVoice: Boolean
        get() = sp.getBoolean(K_AUTOPLAY_MY_VOICE, false)
        set(value) = sp.edit { putBoolean(K_AUTOPLAY_MY_VOICE, value) }

    /** Random id generated once per install (instead of a hardware identifier). */
    val deviceUid: String
        get() {
            val existing = sp.getString(K_UID, null)
            if (!existing.isNullOrBlank()) return existing
            val fresh = UUID.randomUUID().toString().replace("-", "")
            sp.edit { putString(K_UID, fresh) }
            return fresh
        }

    val isPaired: Boolean
        get() = !deviceToken.isNullOrBlank() && serverUrl.isNotBlank()

    fun savePairing(server: String, token: String, institute: String, name: String) {
        sp.edit {
            putString(K_SERVER, server)
            putString(K_TOKEN, token)
            putString(K_INSTITUTE, institute)
            putString(K_NAME, name)
        }
    }

    fun saveConfig(ws: String, heartbeat: Int, hold: Int) {
        if (ws == wsUrl && heartbeat == heartbeatSec && hold == pollHoldSec) return
        sp.edit {
            putString(K_WS, ws)
            putInt(K_HEARTBEAT, heartbeat)
            putInt(K_HOLD, hold)
        }
    }

    /** Forgets the pairing but keeps the server address and the install id. */
    fun clearPairing() {
        sp.edit {
            remove(K_TOKEN)
            remove(K_INSTITUTE)
            remove(K_WS)
            remove(K_FEEDBACK)
        }
    }

    companion object {
        private const val FILE = "aic_secure_prefs"
        private const val K_SERVER = "server"
        private const val K_TOKEN = "token"
        private const val K_INSTITUTE = "institute"
        private const val K_NAME = "device_name"
        private const val K_WS = "ws_url"
        private const val K_HEARTBEAT = "heartbeat_sec"
        private const val K_HOLD = "poll_hold_sec"
        private const val K_UID = "device_uid"
        private const val K_PENDING = "pending_call_events"
        private const val K_FEEDBACK = "pending_call_feedback"
        private const val K_AUTOPLAY_MY_VOICE = "auto_play_my_voice"

        @Volatile
        private var instance: Prefs? = null

        fun get(context: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(context.applicationContext).also { instance = it }
            }

        private fun open(context: Context): SharedPreferences {
            fun create(): SharedPreferences {
                val key = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                return EncryptedSharedPreferences.create(
                    context,
                    FILE,
                    key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }
            return try {
                create()
            } catch (e: Exception) {
                // A corrupted Keystore entry (rare, e.g. after a restore) makes the file unreadable:
                // start clean — the phone simply has to be paired again.
                context.deleteSharedPreferences(FILE)
                create()
            }
        }
    }
}
