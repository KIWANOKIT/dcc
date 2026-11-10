package com.shishyams.aicaller

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.LinearInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.progressindicator.LinearProgressIndicator

/** Shown every time the app is opened: the Shishyams name and a short loading bar, then the main screen. */
class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var animator: ValueAnimator? = null
    private val goMain = Runnable {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        findViewById<TextView>(R.id.splash_version).text = getString(R.string.version_fmt, BuildConfig.VERSION_NAME)

        val bar = findViewById<LinearProgressIndicator>(R.id.splash_progress)
        animator = ValueAnimator.ofInt(0, 100).apply {
            duration = 1800L
            interpolator = LinearInterpolator()
            addUpdateListener { bar.setProgressCompat(it.animatedValue as Int, false) }
            start()
        }
        handler.postDelayed(goMain, 1950L)
    }

    override fun onDestroy() {
        animator?.cancel()
        handler.removeCallbacks(goMain)
        super.onDestroy()
    }
}
