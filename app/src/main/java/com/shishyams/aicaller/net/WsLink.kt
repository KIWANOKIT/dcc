package com.shishyams.aicaller.net

import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One secure WebSocket (wss://) connection to bin/ai-calling-ws-server.php.
 * The first message is {"type":"auth","token":...} — the token is never put in the URL.
 */
class WsLink(
    private val url: String,
    private val token: String,
    private val listener: Listener
) {
    interface Listener {
        fun onReady(heartbeatSec: Int)
        fun onBeatAck()
        fun onCommand(id: Long, command: String, payload: JSONObject)
        fun onClosed(reason: String, unpaired: Boolean)
    }

    private var socket: WebSocket? = null
    private val finished = AtomicBoolean(false)

    fun connect() {
        val request = Request.Builder().url(url).build()
        socket = Http.ws.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("type", "auth").put("token", token).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = try {
                    JSONObject(text)
                } catch (e: JSONException) {
                    return
                }
                when (m.optString("type")) {
                    "auth_ok" -> listener.onReady(m.optInt("heartbeat_sec", 30))
                    "heartbeat_ack" -> listener.onBeatAck()
                    "command" -> listener.onCommand(
                        m.optLong("id"),
                        m.optString("command"),
                        m.optJSONObject("payload") ?: JSONObject()
                    )
                    "unpaired", "auth_error" -> finish("The server no longer accepts this phone.", true)
                    "replaced" -> finish("Another connection of this phone took over.", false)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                finish(reason.ifEmpty { "Connection closed" }, code == 4401 || code == 4403)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                finish(reason.ifEmpty { "Connection closed" }, code == 4401 || code == 4403)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                finish(t.message ?: "Connection failed", false)
            }
        })
    }

    fun send(json: JSONObject): Boolean = socket?.send(json.toString()) ?: false

    fun close() {
        socket?.close(1000, "bye")
        socket = null
    }

    private fun finish(reason: String, unpaired: Boolean) {
        if (finished.compareAndSet(false, true)) listener.onClosed(reason, unpaired)
    }
}
