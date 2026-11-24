package com.shishyams.aicaller.call

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.shishyams.aicaller.R
import com.shishyams.aicaller.net.ConnectionState

/**
 * A small floating card shown while a call is on: one tap on "Play my voice" plays the owner's own recording into the
 * call, so it works even when the automatic "play when the person says hello" detection does not trigger.
 * Needs Android's "Display over other apps" permission (Settings.canDrawOverlays). Call show() / hide() on the main thread.
 */
object CallOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var wm: WindowManager? = null

    fun canShow(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    /** @param onPlay called when the button is tapped; it must call `done` when the recording has finished playing. */
    fun show(ctx: Context, onPlay: (done: () -> Unit) -> Unit) {
        if (root != null) return
        if (!canShow(ctx)) {
            ConnectionState.log("Allow \"Display over other apps\" to get the Play my voice button during calls")
            return
        }
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply { setColor(Color.parseColor("#F2111827")); cornerRadius = dp(28).toFloat() }
            elevation = dp(8).toFloat()
        }
        val label = TextView(ctx).apply {
            text = ctx.getString(R.string.overlay_on_call)
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, 0, dp(12), 0)
        }
        val btn = TextView(ctx).apply {
            text = ctx.getString(R.string.overlay_play)
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply { setColor(Color.parseColor("#2B4C9B")); cornerRadius = dp(20).toFloat() }
        }
        card.addView(label)
        card.addView(btn)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(96)
        }

        btn.setOnClickListener {
            btn.isEnabled = false
            btn.text = ctx.getString(R.string.overlay_playing)
            onPlay {
                main.post {
                    btn.text = ctx.getString(R.string.overlay_play)
                    btn.isEnabled = true
                }
            }
        }

        // Drag the card by its "On call" label if it covers something.
        var downX = 0f
        var downY = 0f
        label.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    lp.x += (e.rawX - downX).toInt()
                    lp.y += (e.rawY - downY).toInt()
                    downX = e.rawX; downY = e.rawY
                    try { wm?.updateViewLayout(card, lp) } catch (ex: Exception) { /* window gone */ }
                    true
                }
                else -> true
            }
        }

        try {
            val w = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            w.addView(card, lp)
            wm = w
            root = card
        } catch (e: Exception) {
            ConnectionState.log("Could not show the Play my voice button: ${e.javaClass.simpleName}")
        }
    }

    fun hide() {
        val v = root ?: return
        root = null
        try { wm?.removeView(v) } catch (e: Exception) { /* already removed */ }
        wm = null
    }
}
