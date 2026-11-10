package com.shishyams.aicaller

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.shishyams.aicaller.call.CallManager
import com.shishyams.aicaller.call.FeedbackPrompt
import com.shishyams.aicaller.databinding.ActivityFeedbackBinding

/**
 * The one-tap "How did the call go?" screen shown when an answered call ends:
 * Interested / Not Interested / Call Later / DND. The choice is sent to the dashboard (Digital Call Centre > Call Feedback);
 * "Skip for now" keeps the call in the waiting list on the home screen.
 */
class FeedbackActivity : AppCompatActivity() {

    private lateinit var b: ActivityFeedbackBinding
    private var currentCallId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityFeedbackBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnInterested.setOnClickListener { choose("interested", R.string.fb_done_interested) }
        b.btnNotInterested.setOnClickListener { choose("not_interested", R.string.fb_done_not_interested) }
        b.btnCallLater.setOnClickListener { choose("callback", R.string.fb_done_call_later) }
        b.btnDnd.setOnClickListener { choose("dnc", R.string.fb_done_dnd) }
        b.btnSkip.setOnClickListener { finish() }

        if (!showCurrent()) finish()
    }

    override fun onResume() {
        super.onResume()
        visible = true
    }

    override fun onPause() {
        visible = false
        super.onPause()
    }

    /** Shows the newest call that still waits for feedback. Returns false when there is none. */
    private fun showCurrent(): Boolean {
        val item = FeedbackPrompt.latest(this) ?: return false
        currentCallId = item.callId
        b.fbNumber.text = item.number.ifBlank { getString(R.string.fb_unknown_number) }
        val more = FeedbackPrompt.waiting(this) - 1
        b.fbMore.visibility = if (more > 0) android.view.View.VISIBLE else android.view.View.GONE
        b.fbMore.text = resources.getQuantityString(R.plurals.fb_more_waiting, more.coerceAtLeast(0), more)
        return true
    }

    private fun choose(code: String, done: Int) {
        val item = FeedbackPrompt.latest(this) ?: run { finish(); return }
        if (item.callId != currentCallId) { showCurrent(); return }      // the list changed under the person's finger
        CallManager.get(this).submitFeedback(item.callId, item.token, code)
        FeedbackPrompt.remove(this, item.callId)
        Toast.makeText(this, done, Toast.LENGTH_SHORT).show()
        if (!showCurrent()) finish()
    }

    companion object {
        /** True while the feedback screen is on top (so the home screen does not open a second one). */
        @Volatile var visible = false
    }
}
