package com.shishyams.aicaller.net

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.shishyams.aicaller.App
import com.shishyams.aicaller.MainActivity
import com.shishyams.aicaller.R
import com.shishyams.aicaller.call.CallManager
import com.shishyams.aicaller.data.DeviceInfo
import com.shishyams.aicaller.data.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Keeps this phone connected to the dashboard.
 *
 *  1. If the server announced a secure WebSocket address (wss://), connect to it: commands arrive
 *     instantly and the heartbeat is one small frame.
 *  2. Otherwise — or when the WebSocket cannot be reached — fall back to HTTPS long-polling,
 *     which works against any PHP host. While on HTTPS the WebSocket is retried every minute.
 *
 * Runs as a foreground service so Android does not kill it while the screen is off.
 */
class ConnectionService : Service() {

    private enum class SessionEnd { UNPAIRED, DROPPED, COULD_NOT_CONNECT, NO_WS }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private lateinit var prefs: Prefs
    private lateinit var handler: CommandHandler

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs.get(this)
        handler = CommandHandler(applicationContext)
        CallManager.get(applicationContext).ensureListening()

        // Keep the ongoing notification text in step with the connection state.
        scope.launch {
            ConnectionState.state
                .map { textFor(it.link) }
                .distinctUntilChanged()
                .collect { text ->
                    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must be called promptly after startForegroundService().
        startInForeground(getString(R.string.notif_connecting))

        if (!prefs.isPaired) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RECONNECT) {
            job?.cancel()
            job = null
        }
        if (job?.isActive != true) {
            job = scope.launch { supervise() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        Http.base.dispatcher.cancelAll()
        if (prefs.isPaired) ConnectionState.set(ConnectionState.Link.IDLE)
        super.onDestroy()
    }

    /* ── Supervisor ─────────────────────────────────────────────────────────── */

    private suspend fun supervise() {
        var failures = 0
        while (currentCoroutineContext().isActive) {
            if (!prefs.isPaired) return
            try {
                ConnectionState.set(ConnectionState.Link.CONNECTING, getString(R.string.state_connecting))
                val wsUrl = prefs.wsUrl
                val end = if (wsUrl.startsWith("wss://")) runWebSocketSession(wsUrl) else SessionEnd.NO_WS
                when (end) {
                    SessionEnd.UNPAIRED -> {
                        onUnpaired()
                        return
                    }
                    SessionEnd.DROPPED -> {
                        failures = 0
                        ConnectionState.log("WebSocket dropped — reconnecting")
                        delay(1_500)
                        continue
                    }
                    SessionEnd.NO_WS, SessionEnd.COULD_NOT_CONNECT -> {
                        if (end == SessionEnd.COULD_NOT_CONNECT) ConnectionState.log("WebSocket unavailable — using HTTPS")
                        runHttpsSession(if (end == SessionEnd.NO_WS) Long.MAX_VALUE else 60_000L)
                        failures = 0
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.http == 401) {
                    onUnpaired()
                    return
                }
                failures++
                ConnectionState.set(ConnectionState.Link.OFFLINE, e.message ?: "")
                ConnectionState.log("Server: ${e.message}")
            } catch (e: Exception) {
                failures++
                ConnectionState.set(ConnectionState.Link.OFFLINE, e.message ?: "Network error")
                ConnectionState.log("Network: ${e.message}")
            }
            delay(minOf(30_000L, 2_000L shl minOf(failures, 4)))
        }
    }

    /* ── HTTPS long-poll ────────────────────────────────────────────────────── */

    private suspend fun runHttpsSession(maxMillis: Long) {
        val api = ApiClient(prefs.serverUrl, prefs.deviceToken)
        val started = SystemClock.elapsedRealtime()
        while (currentCoroutineContext().isActive && SystemClock.elapsedRealtime() - started < maxMillis) {
            val t0 = SystemClock.elapsedRealtime()
            val resp = api.poll(DeviceInfo.snapshot(applicationContext), prefs.pollHoldSec)
            applyConfig(resp.optJSONObject("config"))
            val first = ConnectionState.state.value.link != ConnectionState.Link.ONLINE_HTTPS
            ConnectionState.set(ConnectionState.Link.ONLINE_HTTPS, getString(R.string.state_https), beat = true)
            if (first) ConnectionState.log("Connected over HTTPS")
            CallManager.get(applicationContext).flushAsync()

            val cmds = resp.optJSONArray("commands")
            val count = cmds?.length() ?: 0
            for (i in 0 until count) {
                val c = cmds!!.getJSONObject(i)
                val command = c.optString("command")
                ConnectionState.log("Command: $command")
                val r = handler.handle(command, c.optJSONObject("payload") ?: JSONObject())
                api.ack(c.optLong("id"), r.ok, r.data, r.error)
            }
            // Guard against a hot loop if the server returns immediately (poll hold of 0).
            if (count == 0 && SystemClock.elapsedRealtime() - t0 < 1_000) delay(1_000)
        }
    }

    /* ── Secure WebSocket ───────────────────────────────────────────────────── */

    private suspend fun runWebSocketSession(url: String): SessionEnd = coroutineScope {
        val ready = CompletableDeferred<Int>()
        val closed = CompletableDeferred<Pair<String, Boolean>>()
        val commands = Channel<Triple<Long, String, JSONObject>>(Channel.UNLIMITED)

        val link = WsLink(url, prefs.deviceToken.orEmpty(), object : WsLink.Listener {
            override fun onReady(heartbeatSec: Int) {
                ready.complete(heartbeatSec)
            }

            override fun onBeatAck() {
                ConnectionState.beat()
            }

            override fun onCommand(id: Long, command: String, payload: JSONObject) {
                commands.trySend(Triple(id, command, payload))
            }

            override fun onClosed(reason: String, unpaired: Boolean) {
                closed.complete(Pair(reason, unpaired))
            }
        })

        try {
            link.connect()

            // Wait up to 12 s for the server to accept our token.
            val heartbeatSec: Int? = withTimeoutOrNull(12_000L) {
                while (!ready.isCompleted && !closed.isCompleted) delay(50)
                if (ready.isCompleted) ready.await() else null
            }
            if (heartbeatSec == null) {
                val info = if (closed.isCompleted) closed.await() else null
                return@coroutineScope if (info?.second == true) SessionEnd.UNPAIRED else SessionEnd.COULD_NOT_CONNECT
            }

            ConnectionState.set(ConnectionState.Link.ONLINE_WS, getString(R.string.state_ws), beat = true)
            ConnectionState.log("Connected over WebSocket")
            CallManager.get(applicationContext).ensureListening()
            CallManager.get(applicationContext).flushAsync()

            val beatJob = launch {
                while (isActive) {
                    link.send(heartbeat())
                    delay(maxOf(10, heartbeatSec) * 1_000L)
                }
            }
            val commandJob = launch {
                for (c in commands) {
                    ConnectionState.log("Command: ${c.second}")
                    val r = handler.handle(c.second, c.third)
                    link.send(
                        JSONObject()
                            .put("type", "ack")
                            .put("id", c.first)
                            .put("ok", r.ok)
                            .put("result", r.data ?: JSONObject.NULL)
                            .put("error", r.error ?: JSONObject.NULL)
                    )
                    link.send(heartbeat())
                }
            }

            val end = closed.await()
            beatJob.cancel()
            commandJob.cancel()
            ConnectionState.log("WebSocket closed: ${end.first}")
            if (end.second) SessionEnd.UNPAIRED else SessionEnd.DROPPED
        } finally {
            link.close()
            commands.close()
        }
    }

    private fun heartbeat(): JSONObject =
        JSONObject().put("type", "heartbeat").put("info", DeviceInfo.snapshot(applicationContext))

    /* ── Helpers ────────────────────────────────────────────────────────────── */

    private fun applyConfig(cfg: JSONObject?) {
        if (cfg == null) return
        prefs.saveConfig(
            cfg.optString("ws_url", ""),
            cfg.optInt("heartbeat_sec", 30),
            cfg.optInt("poll_hold_sec", 20)
        )
    }

    private fun onUnpaired() {
        prefs.clearPairing()
        val msg = getString(R.string.msg_removed)
        ConnectionState.set(ConnectionState.Link.UNPAIRED, msg)
        ConnectionState.log(msg)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun textFor(link: ConnectionState.Link): String = when (link) {
        ConnectionState.Link.ONLINE_WS -> getString(R.string.notif_online_ws)
        ConnectionState.Link.ONLINE_HTTPS -> getString(R.string.notif_online_https)
        ConnectionState.Link.OFFLINE -> getString(R.string.notif_offline)
        else -> getString(R.string.notif_connecting)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    private fun startInForeground(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    companion object {
        const val ACTION_RECONNECT = "com.shishyams.aicaller.RECONNECT"
        private const val NOTIF_ID = 1001

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, ConnectionService::class.java))
        }

        fun reconnect(ctx: Context) {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, ConnectionService::class.java).setAction(ACTION_RECONNECT)
            )
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ConnectionService::class.java))
        }
    }
}
