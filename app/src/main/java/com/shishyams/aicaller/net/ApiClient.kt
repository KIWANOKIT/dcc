package com.shishyams.aicaller.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Shared HTTP clients (connection pooling, TLS with the system trust store). */
object Http {
    val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /** WebSocket client: no read timeout, a ping every 20 s detects a dead connection quickly. */
    val ws: OkHttpClient = base.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
}

/** The server answered with an error. [http] is the HTTP status (401 = this phone is not paired). */
class ApiException(val code: String, message: String, val http: Int) : IOException(message)

/**
 * Client for ai-calling-api.php. All calls are blocking — run them off the main thread.
 * Only https:// server addresses are accepted.
 */
class ApiClient(private val baseUrl: String, private val token: String?) {

    fun ping(): JSONObject = call("ping", JSONObject(), false)

    fun pair(code: String, uid: String, name: String, info: JSONObject): JSONObject {
        val body = JSONObject()
            .put("code", code)
            .put("device_uid", uid)
            .put("device_name", name)
            .put("sim_label", info.optString("sim_label"))
            .put("app_version", info.optString("app_version"))
            .put("android_version", info.optString("android_version"))
        return call("pair", body, false)
    }

    /** Long-poll: returns when a command is waiting or after [holdSec] seconds. */
    fun poll(info: JSONObject, holdSec: Int): JSONObject {
        val body = JSONObject().put("info", info).put("hold", holdSec)
        return call("poll", body, true, (holdSec + 15).toLong())
    }

    fun ack(id: Long, ok: Boolean, result: JSONObject?, error: String?) {
        val body = JSONObject().put("id", id).put("ok", ok)
        if (result != null) body.put("result", result)
        if (error != null) body.put("error", error)
        call("ack", body, true)
    }

    /** Reports how a call ended: {call_id, token, event, outcome, duration_sec, reason}. */
    fun callEvent(event: JSONObject): JSONObject = call("call_event", event, true)

    /**
     * Step 12 — a call is ringing: ask the server whether to answer it. Returns {answer, reason} or, when the app should
     * answer, {answer:true, call_id, token, max_sec, realtime, audio_b64}. May take a few seconds (the greeting can be synthesised).
     */
    fun inboundStart(number: String): JSONObject =
        call("inbound_start", JSONObject().put("number", number), true, 45)

    /** Generic authenticated call to any server action (used by the campaign screens). Blocking: run off the main thread. */
    fun request(action: String, body: JSONObject = JSONObject()): JSONObject = call(action, body, true)

    fun unpair() {
        call("unpair", JSONObject(), true)
    }

    /**
     * Step 7 — sends one thing the person said during a call. The body is the raw WAV (empty for kind = "silence"),
     * the details travel in the query string. Returns {action, heard, reply, audio_b64, ...}.
     */
    fun rtTurn(callId: Long, callToken: String, seq: Int, kind: String, durMs: Int, peak: Int, wav: ByteArray, barge: Boolean = false, cutMs: Int = 0): JSONObject {
        val root = baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw ApiException("bad_url", "The server address is not valid.", 0)
        if (!root.isHttps) throw ApiException("https_required", "Only secure https:// server addresses are allowed.", 0)
        val url = root.newBuilder()
            .addPathSegment("ai-calling-api.php")
            .addQueryParameter("action", "rt_turn")
            .addQueryParameter("call_id", callId.toString())
            .addQueryParameter("token", callToken)
            .addQueryParameter("seq", seq.toString())
            .addQueryParameter("kind", kind)
            .addQueryParameter("dur", durMs.toString())
            .addQueryParameter("peak", peak.toString())
            .apply { if (barge) { addQueryParameter("barge", "1"); addQueryParameter("cut", cutMs.toString()) } }   // this sentence interrupted the AI
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("X-Device-Token", token.orEmpty())
            .post(wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        // Speech-to-text + the AI brain + the voice can take several seconds.
        val client = Http.base.newBuilder()
            .readTimeout(70, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        return client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json: JSONObject? = try { JSONObject(text) } catch (e: JSONException) { null }
            if (json == null) {
                throw ApiException("bad_response", "Unexpected reply from the server (HTTP ${resp.code}).", resp.code)
            }
            if (!resp.isSuccessful || !json.optBoolean("ok", false)) {
                throw ApiException(
                    json.optString("error", "error"),
                    json.optString("message", "Request failed (HTTP ${resp.code})."),
                    resp.code
                )
            }
            json
        }
    }

    private fun call(action: String, body: JSONObject, auth: Boolean, readTimeoutSec: Long = 30): JSONObject {
        val root = baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw ApiException("bad_url", "The server address is not valid.", 0)
        if (!root.isHttps) {
            throw ApiException("https_required", "Only secure https:// server addresses are allowed.", 0)
        }
        val url = root.newBuilder()
            .addPathSegment("ai-calling-api.php")
            .addQueryParameter("action", action)
            .build()
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (auth) builder.header("X-Device-Token", token.orEmpty())

        val client = Http.base.newBuilder().readTimeout(readTimeoutSec, TimeUnit.SECONDS).build()
        val result: JSONObject = client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json: JSONObject? = try {
                JSONObject(text)
            } catch (e: JSONException) {
                null
            }
            if (json == null) {
                throw ApiException(
                    "bad_response",
                    "Unexpected reply from the server (HTTP ${resp.code}). Check the server address.",
                    resp.code
                )
            }
            if (!resp.isSuccessful || !json.optBoolean("ok", false)) {
                throw ApiException(
                    json.optString("error", "error"),
                    json.optString("message", "Request failed (HTTP ${resp.code})."),
                    resp.code
                )
            }
            json
        }
        return result
    }
}
