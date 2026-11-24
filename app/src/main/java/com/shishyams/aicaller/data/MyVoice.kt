package com.shishyams.aicaller.data

import android.content.Context
import java.io.File

/**
 * The owner's own recorded voice (recorded inside the app). It is kept in the app's private storage and played
 * automatically on a campaign call as soon as the person answers (see VoiceBridge).
 */
object MyVoice {
    private const val NAME = "my_voice.m4a"
    private const val TMP = "my_voice_new.m4a"
    private const val MIN_BYTES = 1500L          // anything smaller is an empty / failed recording

    fun file(ctx: Context): File = File(ctx.filesDir, NAME)
    fun tempFile(ctx: Context): File = File(ctx.filesDir, TMP)

    fun exists(ctx: Context): Boolean = file(ctx).let { it.isFile && it.length() >= MIN_BYTES }

    /** The recording to play on calls, or null when there is none or automatic playing is switched off. */
    fun playable(ctx: Context): File? =
        if (Prefs.get(ctx).autoPlayMyVoice && exists(ctx)) file(ctx) else null

    /** Replaces the saved recording with the freshly recorded temporary one. Returns false if that recording is unusable. */
    fun commit(ctx: Context): Boolean {
        val t = tempFile(ctx)
        if (!t.isFile || t.length() < MIN_BYTES) { t.delete(); return false }
        val dest = file(ctx)
        dest.delete()
        return t.renameTo(dest)
    }

    fun delete(ctx: Context) {
        file(ctx).delete()
        tempFile(ctx).delete()
    }
}
