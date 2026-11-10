package com.shishyams.aicaller.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Base64
import com.shishyams.aicaller.data.Prefs
import com.shishyams.aicaller.net.ApiClient
import com.shishyams.aicaller.net.ApiException
import com.shishyams.aicaller.net.ConnectionState
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Lets the AI talk during ONE call (Step 7).
 *
 * Android does not allow an ordinary app to put sound into a SIM call or to record the other person directly, so the
 * bridge works acoustically: it switches the call to loudspeaker, listens to the other person through the microphone
 * and plays the AI's voice through the speaker (the other person hears it through the phone's microphone).
 *
 * Loop (one thread, strictly turn-taking so the phone never hears its own voice):
 *   LISTEN  read the microphone in 20 ms frames; an energy detector finds where a sentence starts and ends
 *   SEND    upload that sentence (WAV 16 kHz mono) to ai-calling-api.php?action=rt_turn
 *   SPEAK   play the MP3 reply, wait until it ends, ignore the microphone a moment longer (echo tail), then LISTEN again
 *
 * Until the other person has said a short "hello" nothing is spoken: Android does not tell an app when a call is
 * answered, so the first human voice is what starts the conversation. The server decides what is a human greeting
 * (it ignores caller-tunes, voicemail and announcements).
 */
class VoiceBridge(
    private val ctx: Context,
    private val callId: Long,
    private val callToken: String,
    cfg: JSONObject,
    private val greetingB64: String? = null,     // incoming call: the greeting (the admin's own recording) to play first
    private val inbound: Boolean = false,        // incoming call: the AI speaks first, so there is no "waiting for hello"
    private val hangUp: () -> Unit
) {
    private val prefs = Prefs.get(ctx)
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val silenceMs = cfg.optInt("silence_ms", 900).coerceIn(400, 3000)
    private val minSpeechMs = cfg.optInt("min_speech_ms", 350).coerceIn(150, 1500)
    private val maxUttMs = cfg.optInt("max_utt_ms", 20_000).coerceIn(3_000, 30_000)
    private val idleMs = cfg.optInt("idle_sec", 9).coerceIn(4, 30) * 1000L
    private val forceSpeaker = cfg.optBoolean("speaker", true)

    // AI voice agent: let the person interrupt the AI while it is speaking (off unless the dashboard switched it on).
    private val bargeIn = cfg.optBoolean("barge_in", false)
    private val bargeMult: Double
    private val bargeFrames: Int
    private val bargeMinRms: Double
    init {
        when (cfg.optString("barge_sens", "medium")) {
            "low"  -> { bargeMult = 3.0; bargeFrames = 10; bargeMinRms = 0.09 }
            "high" -> { bargeMult = 1.7; bargeFrames = 5;  bargeMinRms = 0.045 }
            else   -> { bargeMult = 2.2; bargeFrames = 7;  bargeMinRms = 0.06 }
        }
    }

    @Volatile private var stopped = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private var player: MediaPlayer? = null

    /** Loudest microphone level seen during the call, 0-100 (reported with the call result for the audio check). */
    @Volatile var micPeak: Int = 0
        private set

    private var seq = 0
    @Volatile private var talking = false          // the server has answered at least once
    private var preTalkUploads = 0
    private var failures = 0
    @Volatile private var carryFrames: List<ShortArray>? = null    // what the person said while cutting the AI off: the start of their next sentence
    private var bargeNext = false                                  // the sentence being recorded began as an interruption
    private var lastCutMs = 0                                      // how long the AI had been speaking when it was cut off
    private var savedSpeaker = false
    private var savedVoiceVolume = -1

    fun start() {
        if (thread != null) return
        thread = Thread({ runLoop() }, "aic-voice").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        stopped = true
        try { player?.stop() } catch (e: Exception) { /* not playing */ }
        thread?.interrupt()
    }

    /* ── Main loop ──────────────────────────────────────────────────────────── */

    private fun runLoop() {
        val rec = openRecorder()
        if (rec == null) {
            ConnectionState.log("Call #$callId: microphone not available — ending the call")
            hangUp()
            return
        }
        record = rec
        routeToSpeaker()
        try {
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IllegalStateException("not recording")
            if (inbound) {
                talking = true
                if (!greetingB64.isNullOrEmpty()) {
                    Thread.sleep(INBOUND_GREETING_DELAY_MS)     // let the line settle after answering
                    if (!speak(greetingB64, rec)) drain(rec)
                }
            }
            listenLoop(rec)
        } catch (e: InterruptedException) {
            // stop() was called
        } catch (e: Exception) {
            ConnectionState.log("Voice error: ${e.javaClass.simpleName}")
            if (!stopped) {
                hangUp()
            }
        } finally {
            try { rec.stop() } catch (e: Exception) { /* already stopped */ }
            rec.release()
            record = null
            restoreAudio()
        }
    }

    private fun openRecorder(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        for (source in intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            try {
                val r = AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, RATE))
                if (r.state == AudioRecord.STATE_INITIALIZED) return r
                r.release()
            } catch (e: SecurityException) {
                return null
            } catch (e: Exception) {
                // try the next source
            }
        }
        return null
    }

    private fun listenLoop(rec: AudioRecord) {
        val frame = ShortArray(FRAME)
        val preRoll = ArrayDeque<ShortArray>()
        val utter = ArrayList<ShortArray>()
        var floor = 0.01
        var inSpeech = false
        var loudRun = 0
        var loudFrames = 0
        var quietFrames = 0
        var lastActivity = SystemClock.elapsedRealtime()
        var sinceRoute = 0

        while (!stopped) {
            val carry = carryFrames
            if (carry != null) {                                   // the person cut the AI off: their words so far are the start of this sentence
                carryFrames = null
                inSpeech = true; utter.clear(); utter.addAll(carry); preRoll.clear()
                loudFrames = carry.size; loudRun = 0; quietFrames = 0
                bargeNext = true
                lastActivity = SystemClock.elapsedRealtime()
            }
            val n = rec.read(frame, 0, FRAME)
            if (n <= 0) {
                if (n == AudioRecord.ERROR_DEAD_OBJECT || n == AudioRecord.ERROR_INVALID_OPERATION) throw IllegalStateException("read $n")
                continue
            }
            if (++sinceRoute >= 75) { sinceRoute = 0; routeToSpeaker() }       // Android resets the route when the call connects

            var peak = 0
            var sum = 0.0
            for (i in 0 until n) { val a = abs(frame[i].toInt()); if (a > peak) peak = a; sum += frame[i].toDouble() * frame[i].toDouble() }
            val rms = sqrt(sum / n) / 32768.0
            micPeak = max(micPeak, (peak * 100 / 32767))

            val loud = rms > max(0.015, floor * 3.0)
            if (!inSpeech) floor = floor * 0.95 + rms * 0.05

            val copy = frame.copyOf(n)
            if (!inSpeech) {
                preRoll.addLast(copy); if (preRoll.size > PREROLL_FRAMES) preRoll.removeFirst()
                loudRun = if (loud) loudRun + 1 else 0
                if (loudRun >= 3) {                                    // 60 ms of sound = a sentence has started
                    inSpeech = true; loudFrames = loudRun; quietFrames = 0
                    utter.clear(); utter.addAll(preRoll); preRoll.clear()
                    lastActivity = SystemClock.elapsedRealtime()
                }
            } else {
                utter.add(copy)
                if (loud) { loudFrames++; quietFrames = 0 } else quietFrames++
                val lenMs = utter.size * FRAME_MS
                if (quietFrames * FRAME_MS >= silenceMs || lenMs >= maxUttMs) {
                    inSpeech = false; loudRun = 0
                    val speechMs = loudFrames * FRAME_MS
                    if (speechMs >= minSpeechMs) {
                        val pcm = join(utter, utter.size - (quietFrames - TAIL_FRAMES).coerceAtLeast(0))   // keep a short tail, drop the rest
                        utter.clear(); loudFrames = 0; quietFrames = 0
                        if (!handleUtterance(rec, pcm, speechMs)) return
                        lastActivity = SystemClock.elapsedRealtime()
                        continue
                    }
                    utter.clear(); loudFrames = 0; quietFrames = 0       // a click or cough
                    bargeNext = false
                }
            }

            // The person went quiet after the AI spoke: let the AI check they are still there.
            if (talking && !inSpeech && SystemClock.elapsedRealtime() - lastActivity >= idleMs) {
                if (!send("silence", ByteArray(0), 0)?.let { handleReply(rec, it) }.orFalse()) return
                lastActivity = SystemClock.elapsedRealtime()
            }
        }
    }

    private fun Boolean?.orFalse(): Boolean = this ?: true     // a failed upload just means "keep listening"

    /** @return false when the bridge must stop (call over or the AI hung up). */
    private fun handleUtterance(rec: AudioRecord, pcm: ShortArray, speechMs: Int): Boolean {
        val lenMs = pcm.size * 1000 / RATE
        // Before the conversation has started only a short "hello" matters — never upload music or announcements.
        if (!talking) {
            if (lenMs > FIRST_HELLO_MAX_MS) return true
            if (preTalkUploads >= MAX_PRE_TALK_UPLOADS) return true
            preTalkUploads++
        }
        val barged = bargeNext
        bargeNext = false
        val reply = send("speech", wav(pcm), lenMs, barged) ?: return true
        return handleReply(rec, reply)
    }

    private fun handleReply(rec: AudioRecord, r: JSONObject): Boolean {
        val action = r.optString("action", "ignore")
        if (action == "ignore") return true
        talking = true
        val b64 = r.optString("audio_b64", "")
        val interrupted = if (b64.isNotEmpty()) speak(b64, rec) else false
        if (action == "hangup") {
            ConnectionState.log("Call #$callId: the AI ended the call")
            hangUp()
            return false
        }
        if (!interrupted) drain(rec)         // after an interruption the microphone buffer holds the person's words: keep them
        return true
    }

    /* ── Network ────────────────────────────────────────────────────────────── */

    /** Uploads one utterance. Returns the server's reply, or null when it could not be handled (keep listening). */
    private fun send(kind: String, wav: ByteArray, durMs: Int, barged: Boolean = false): JSONObject? {
        if (!prefs.isPaired) return null
        seq++
        return try {
            val r = ApiClient(prefs.serverUrl, prefs.deviceToken).rtTurn(callId, callToken, seq, kind, durMs, micPeak, wav, barged, if (barged) lastCutMs else 0)
            failures = 0
            r
        } catch (e: ApiException) {
            // 404 / 409: that call is over or the AI conversation was switched off — nothing more to do.
            if (e.http == 404 || e.http == 409) { stopped = true } else failures++
            ConnectionState.log("AI turn rejected: ${e.code}")
            null
        } catch (e: IOException) {
            failures++
            ConnectionState.log("AI turn failed: network")
            null
        }
    }

    /* ── Audio ──────────────────────────────────────────────────────────────── */

    @Suppress("DEPRECATION")
    private fun routeToSpeaker() {
        if (!forceSpeaker) return
        try {
            if (!audio.isSpeakerphoneOn) {
                if (!savedSpeaker && savedVoiceVolume == -1) {
                    savedSpeaker = false
                    savedVoiceVolume = audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
                    try { audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0) } catch (e: SecurityException) { /* volume locked */ }
                }
                audio.isSpeakerphoneOn = true
            }
        } catch (e: Exception) {
            // some phones refuse; the audio check on the dashboard will show whether the microphone heard anything
        }
    }

    @Suppress("DEPRECATION")
    private fun restoreAudio() {
        try {
            audio.isSpeakerphoneOn = savedSpeaker
            if (savedVoiceVolume >= 0) audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, savedVoiceVolume, 0)
        } catch (e: Exception) { /* ignore */ }
    }

    /**
     * Plays the MP3 and blocks until it has finished, the call ended, or (with barge-in on) the person started talking.
     * @return true when the person interrupted the AI
     */
    private fun speak(b64: String, rec: AudioRecord? = null): Boolean {
        val file = File(ctx.cacheDir, "aic_reply_$callId.mp3")
        try {
            file.writeBytes(Base64.decode(b64, Base64.DEFAULT))
            return playFile(file, if (bargeIn) rec else null)
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            ConnectionState.log("Could not play the AI voice: ${e.javaClass.simpleName}")
        } finally {
            file.delete()
        }
        return false
    }

    /**
     * Plays any audio file (MP3, M4A, WAV) through the call and blocks until it has finished (or the call ended).
     * With [watch] set, the microphone is read while playing: the level of the AI's own voice (heard back through the
     * microphone) is measured for the first 0.6 s, and a sustained jump well above it means the person started talking —
     * playback stops at once and what they said so far is kept in [carryFrames].
     * @return true when playback was cut short by the person speaking
     */
    private fun playFile(file: File, watch: AudioRecord? = null): Boolean {
        var interrupted = false
        try {
            val done = CountDownLatch(1)
            val playingSince = java.util.concurrent.atomic.AtomicLong(0L)       // set by the player thread when audio actually starts
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener { it.start(); playingSince.set(SystemClock.elapsedRealtime()) }
            mp.setOnCompletionListener { done.countDown() }
            mp.setOnErrorListener { _, _, _ -> done.countDown(); true }
            mp.prepareAsync()
            val started = SystemClock.elapsedRealtime()
            if (watch == null) {
                while (!stopped && !done.await(150, TimeUnit.MILLISECONDS)) {
                    if (SystemClock.elapsedRealtime() - started > 90_000L) break
                    routeToSpeaker()
                }
            } else {
                val frame = ShortArray(FRAME)
                val recent = ArrayDeque<ShortArray>()
                var baseSum = 0.0; var baseN = 0; var baseline = 0.0
                var smooth = 0.0; var run = 0; var tick = 0
                while (!stopped && done.count > 0L) {
                    if (SystemClock.elapsedRealtime() - started > 90_000L) break
                    if (++tick % 75 == 0) routeToSpeaker()
                    val n = watch.read(frame, 0, FRAME)
                    if (n <= 0) { if (done.await(20, TimeUnit.MILLISECONDS)) break else continue }
                    val since = playingSince.get()
                    if (since == 0L) continue                              // still preparing: nothing of ours is audible yet
                    var sum = 0.0
                    for (i in 0 until n) sum += frame[i].toDouble() * frame[i].toDouble()
                    val rms = sqrt(sum / n) / 32768.0
                    recent.addLast(frame.copyOf(n)); if (recent.size > BARGE_KEEP_FRAMES) recent.removeFirst()
                    val elapsed = SystemClock.elapsedRealtime() - since
                    if (elapsed < BARGE_WARMUP_MS) {                       // learn how loud the AI's own voice is on this phone
                        if (elapsed > BARGE_SETTLE_MS) { baseSum += rms; baseN++; baseline = baseSum / baseN }
                        continue
                    }
                    smooth = smooth * 0.6 + rms * 0.4
                    val threshold = max(bargeMinRms, baseline * bargeMult)
                    if (smooth > threshold) run++ else { run = 0; baseline = baseline * 0.98 + rms * 0.02 }
                    if (run >= bargeFrames) {
                        interrupted = true
                        lastCutMs = elapsed.toInt()
                        carryFrames = recent.toList()
                        try { mp.stop() } catch (e: Exception) { /* already stopped */ }
                        ConnectionState.log("Call #$callId: the person interrupted the AI")
                        break
                    }
                }
            }
            try { mp.release() } catch (e: Exception) { /* already released */ }
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            ConnectionState.log("Could not play the voice: ${e.javaClass.simpleName}")
        } finally {
            player = null
        }
        return interrupted
    }

    /** Throws away what the microphone heard while the AI was thinking / speaking (its own echo). */
    private fun drain(rec: AudioRecord) {
        val junk = ShortArray(FRAME)
        val until = SystemClock.elapsedRealtime() + ECHO_TAIL_MS
        while (!stopped && SystemClock.elapsedRealtime() < until) {
            if (rec.read(junk, 0, FRAME) <= 0) break
        }
        // Whatever piled up in the buffer during playback is stale as well.
        var guard = 0
        while (!stopped && guard++ < 200) {
            val got = rec.read(junk, 0, FRAME, AudioRecord.READ_NON_BLOCKING)
            if (got <= 0) break
        }
    }

    private fun join(frames: List<ShortArray>, count: Int): ShortArray {
        val use = frames.subList(0, count.coerceIn(0, frames.size))
        val out = ShortArray(use.sumOf { it.size })
        var o = 0
        for (f in use) { System.arraycopy(f, 0, out, o, f.size); o += f.size }
        return out
    }

    /** 16-bit mono 16 kHz WAV. */
    private fun wav(pcm: ShortArray): ByteArray {
        val dataLen = pcm.size * 2
        val buf = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(36 + dataLen).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        buf.put("data".toByteArray()).putInt(dataLen)
        for (s in pcm) buf.putShort(s)
        return buf.array()
    }

    companion object {
        private const val RATE = 16_000
        private const val FRAME_MS = 20
        private const val FRAME = RATE * FRAME_MS / 1000       // 320 samples
        private const val PREROLL_FRAMES = 15                  // 300 ms kept from before the sentence is detected
        private const val TAIL_FRAMES = 8                      // 160 ms of the trailing quiet is kept
        private const val ECHO_TAIL_MS = 450L
        private const val BARGE_SETTLE_MS = 150L               // ignore the first moments of playback (ramp-up) when measuring the AI's own level
        private const val BARGE_WARMUP_MS = 600L               // then measure for this long before watching for an interruption
        private const val BARGE_KEEP_FRAMES = 20               // 400 ms of microphone kept so the start of the interruption is not lost
        private const val FIRST_HELLO_MAX_MS = 5_500
        private const val MAX_PRE_TALK_UPLOADS = 12
        private const val INBOUND_GREETING_DELAY_MS = 700L
    }
}
