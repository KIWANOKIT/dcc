package com.shishyams.aicaller

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shishyams.aicaller.call.CallManager
import com.shishyams.aicaller.call.CallOverlay
import com.shishyams.aicaller.call.DialerPermissions
import com.shishyams.aicaller.call.VoiceModeService
import com.shishyams.aicaller.data.DeviceInfo
import com.shishyams.aicaller.data.MyVoice
import com.shishyams.aicaller.data.Prefs
import com.shishyams.aicaller.databinding.ActivityMainBinding
import com.shishyams.aicaller.net.ApiClient
import com.shishyams.aicaller.net.ApiException
import com.shishyams.aicaller.net.ConnectionService
import com.shishyams.aicaller.net.ConnectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* works either way */ }

    private val callingPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            CallManager.get(this).ensureListening()
            render()
            if (prefs.isPaired) ConnectionService.reconnect(this)   // tell the dashboard right away
        }

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startVoiceMode() else render()
        }

    private var recorder: MediaRecorder? = null
    private var preview: MediaPlayer? = null

    private val recPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startRecording() else renderMyVoice()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs.get(this)

        b.footerVersion.text = getString(R.string.version_fmt, BuildConfig.VERSION_NAME)
        b.inputServer.setText(prefs.serverUrl)
        b.inputName.setText(DeviceInfo.defaultName())

        b.btnPair.setOnClickListener { doPair() }
        b.tileReconnect.setOnClickListener { ConnectionService.reconnect(this) }
        b.tileBattery.setOnClickListener { openBatterySettings() }
        b.tileCalling.setOnClickListener { askCallingPermissions() }
        b.tileUnpair.setOnClickListener { confirmUnpair() }
        b.tileVoice.setOnClickListener { toggleVoiceMode() }
        b.btnRec.setOnClickListener { toggleRecording() }
        b.btnPlay.setOnClickListener { togglePreview() }
        b.btnDel.setOnClickListener { deleteMyVoice() }
        b.btnOverlay.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        b.swAutoplay.isChecked = prefs.autoPlayMyVoice
        b.swAutoplay.setOnCheckedChangeListener { _, on -> prefs.autoPlayMyVoice = on }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { ConnectionState.state.collect { render() } }
                launch { ConnectionState.log.collect { lines -> renderLog(lines) } }
            }
        }

        askNotificationPermission()
        if (prefs.isPaired) ConnectionService.start(this)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onStop() {
        stopPreview()
        if (recorder != null) finishRecording()
        super.onStop()
    }

    /* ── My recorded voice ──────────────────────────────────────────────────── */

    private fun renderMyVoice(message: Int? = null) {
        val rec = recorder != null
        val has = MyVoice.exists(this)
        b.btnOverlay.visibility = if (CallOverlay.canShow(this)) View.GONE else View.VISIBLE
        b.btnRec.setText(if (rec) R.string.myvoice_stop else R.string.myvoice_record)
        b.btnPlay.isEnabled = has && !rec
        b.btnDel.isEnabled = has && !rec
        b.myvoiceStatus.setText(message ?: when {
            rec -> R.string.myvoice_recording
            preview != null -> R.string.myvoice_playing
            has -> R.string.myvoice_saved
            else -> R.string.myvoice_none
        })
    }

    private fun toggleRecording() {
        if (recorder != null) { finishRecording(); return }
        if (!DialerPermissions.has(this, Manifest.permission.RECORD_AUDIO)) {
            recPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startRecording()
    }

    private fun startRecording() {
        stopPreview()
        val tmp = MyVoice.tempFile(this)
        tmp.delete()
        try {
            val mr = newRecorder()
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioSamplingRate(44_100)
            mr.setAudioEncodingBitRate(96_000)
            mr.setMaxDuration(60_000)
            mr.setOutputFile(tmp.absolutePath)
            mr.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) runOnUiThread { finishRecording() }
            }
            mr.prepare()
            mr.start()
            recorder = mr
        } catch (e: Exception) {
            recorder = null
            tmp.delete()
            renderMyVoice(R.string.myvoice_failed)
            return
        }
        renderMyVoice()
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()

    private fun finishRecording() {
        val mr = recorder ?: return
        recorder = null
        var ok = true
        try { mr.stop() } catch (e: RuntimeException) { ok = false }      // stopped before any sound was captured
        try { mr.release() } catch (e: Exception) { /* already released */ }
        if (ok && MyVoice.commit(this)) renderMyVoice() else { MyVoice.tempFile(this).delete(); renderMyVoice(R.string.myvoice_failed) }
    }

    private fun togglePreview() {
        if (preview != null) { stopPreview(); return }
        if (!MyVoice.exists(this)) return
        try {
            val mp = MediaPlayer()
            mp.setDataSource(MyVoice.file(this).absolutePath)
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener { stopPreview() }
            mp.setOnErrorListener { _, _, _ -> stopPreview(); true }
            mp.prepareAsync()
            preview = mp
            b.btnPlay.setText(R.string.myvoice_stop)
            renderMyVoice()
        } catch (e: Exception) {
            stopPreview()
        }
    }

    private fun stopPreview() {
        val mp = preview ?: return
        preview = null
        try { mp.release() } catch (e: Exception) { /* already released */ }
        b.btnPlay.setText(R.string.myvoice_play)
        renderMyVoice()
    }

    private fun deleteMyVoice() {
        stopPreview()
        MyVoice.delete(this)
        renderMyVoice()
    }

    /* ── Pairing ────────────────────────────────────────────────────────────── */

    private fun doPair() {
        val server = normalizeServer(b.inputServer.text?.toString().orEmpty())
        if (server == null) {
            showPairError(getString(R.string.err_server))
            return
        }
        val code = b.inputCode.text?.toString().orEmpty().trim()
        if (code.isEmpty()) {
            showPairError(getString(R.string.err_code))
            return
        }
        val name = b.inputName.text?.toString().orEmpty().trim().ifEmpty { DeviceInfo.defaultName() }

        setBusy(true)
        lifecycleScope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    val api = ApiClient(server, null)
                    api.ping()   // fails fast with a clear message if the address is wrong
                    api.pair(code, prefs.deviceUid, name, DeviceInfo.snapshot(applicationContext))
                }
                prefs.savePairing(server, res.getString("token"), res.optString("institute_name"), name)
                val cfg = res.optJSONObject("config")
                if (cfg != null) {
                    prefs.saveConfig(cfg.optString("ws_url", ""), cfg.optInt("heartbeat_sec", 30), cfg.optInt("poll_hold_sec", 20))
                }
                ConnectionState.log("Paired with ${res.optString("institute_name")}")
                b.inputCode.setText("")
                ConnectionService.start(this@MainActivity)
            } catch (e: ApiException) {
                showPairError(e.message ?: getString(R.string.err_network))
            } catch (e: IOException) {
                showPairError(getString(R.string.err_network))
            } finally {
                setBusy(false)
                render()
            }
        }
    }

    private fun normalizeServer(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        if (!s.contains("://")) s = "https://$s"
        s = s.trimEnd('/').removeSuffix("/ai-calling-api.php").trimEnd('/')
        val url = s.toHttpUrlOrNull() ?: return null
        return if (url.isHttps) s else null
    }

    private fun showPairError(message: String) {
        b.pairError.text = message
        b.pairError.visibility = View.VISIBLE
    }

    private fun setBusy(busy: Boolean) {
        b.btnPair.isEnabled = !busy
        b.btnPair.alpha = if (busy) 0.6f else 1f
        b.pairProgress.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) b.pairError.visibility = View.GONE
    }

    /* ── Unpair ─────────────────────────────────────────────────────────────── */

    private fun confirmUnpair() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.unpair_title)
            .setMessage(R.string.unpair_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.btn_unpair) { _, _ -> doUnpair() }
            .show()
    }

    private fun doUnpair() {
        val server = prefs.serverUrl
        val token = prefs.deviceToken
        ConnectionService.stop(this)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    ApiClient(server, token).unpair()
                } catch (e: Exception) {
                    // Best effort — if the server cannot be reached the dashboard can remove the phone later.
                }
            }
            prefs.clearPairing()
            ConnectionState.set(ConnectionState.Link.IDLE)
            render()
        }
    }

    /* ── Rendering ──────────────────────────────────────────────────────────── */

    private fun render() {
        renderMyVoice()
        val paired = prefs.isPaired
        b.pairCard.visibility = if (paired) View.GONE else View.VISIBLE
        b.statusCard.visibility = if (paired) View.VISIBLE else View.GONE
        val s = ConnectionState.state.value

        if (!paired) {
            if (s.link == ConnectionState.Link.UNPAIRED && s.detail.isNotEmpty()) showPairError(s.detail)
            return
        }

        b.statusInstitute.text = prefs.instituteName.ifBlank { getString(R.string.app_name) }
        b.statusDevice.text = prefs.deviceName
        val (label, colorRes) = when (s.link) {
            ConnectionState.Link.ONLINE_WS -> getString(R.string.state_ws) to R.color.state_green
            ConnectionState.Link.ONLINE_HTTPS -> getString(R.string.state_https) to R.color.state_green
            ConnectionState.Link.CONNECTING -> getString(R.string.state_connecting) to R.color.state_amber
            ConnectionState.Link.OFFLINE -> getString(R.string.state_offline) to R.color.state_red
            ConnectionState.Link.UNPAIRED -> getString(R.string.state_unpaired) to R.color.state_gray
            ConnectionState.Link.IDLE -> getString(R.string.state_idle) to R.color.state_gray
        }
        b.statusText.text = label
        b.statusDot.setTextColor(ContextCompat.getColor(this, colorRes))
        b.statusDetail.text = if (s.link == ConnectionState.Link.OFFLINE) s.detail else ""
        b.statusBeat.text = if (s.lastBeatAt > 0) {
            getString(R.string.last_contact, DateFormat.getTimeFormat(this).format(Date(s.lastBeatAt)))
        } else {
            getString(R.string.no_contact)
        }
        val missing = DialerPermissions.missing(this)
        b.lblCalling.setText(if (missing.isEmpty()) R.string.btn_calling_ok else R.string.btn_calling_allow)
        if (missing.isNotEmpty()) b.statusDetail.text = getString(R.string.calling_missing, missing.joinToString(", "))
        b.lblBattery.setText(if (isIgnoringBatteryOptimizations()) R.string.btn_battery_ok else R.string.btn_battery_allow)
        b.lblVoice.setText(if (VoiceModeService.running) R.string.btn_voice_on else R.string.btn_voice_start)
        // While AI voice mode is on and this screen is visible, keep the screen awake (helps the phone stay reachable).
        if (VoiceModeService.running) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun renderLog(lines: List<String>) {
        b.logText.text = if (lines.isEmpty()) getString(R.string.log_empty) else lines.joinToString("\n")
    }

    /* ── Permissions / battery ──────────────────────────────────────────────── */

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun askCallingPermissions() {
        val todo = DialerPermissions.all().filter { !DialerPermissions.has(this, it) }
        if (todo.isEmpty()) return
        callingPermissions.launch(todo.toTypedArray())
    }

    /** AI voice mode (Step 7): the microphone foreground service must be started while this screen is visible. */
    private fun toggleVoiceMode() {
        if (VoiceModeService.running) {
            VoiceModeService.stop(this)
            render()
            if (prefs.isPaired) ConnectionService.reconnect(this)
            return
        }
        if (!DialerPermissions.has(this, Manifest.permission.RECORD_AUDIO)) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startVoiceMode()
    }

    private fun startVoiceMode() {
        VoiceModeService.start(this)
        // The service sets its flag a moment after it starts; refresh the screen and tell the dashboard.
        b.root.postDelayed({
            render()
            if (prefs.isPaired) ConnectionService.reconnect(this)
        }, 700)
    }

    private fun isIgnoringBatteryOptimizations(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun openBatterySettings() {
        if (isIgnoringBatteryOptimizations()) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
