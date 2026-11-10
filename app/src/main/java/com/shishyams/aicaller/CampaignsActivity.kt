package com.shishyams.aicaller

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shishyams.aicaller.data.Prefs
import com.shishyams.aicaller.databinding.ActivityCampaignsBinding
import com.shishyams.aicaller.net.ApiClient
import com.shishyams.aicaller.net.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Create and manage AI calling campaigns from the phone. Everything goes through ai-calling-api.php. */
class CampaignsActivity : AppCompatActivity() {

    private lateinit var b: ActivityCampaignsBinding
    private lateinit var prefs: Prefs
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCampaignsBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs.get(this)
        b.btnNewCampaign.setOnClickListener { startActivity(Intent(this, CampaignEditActivity::class.java)) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refresh()
                    delay(5000)
                }
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun api() = ApiClient(prefs.serverUrl, prefs.deviceToken)

    private suspend fun refresh() {
        if (busy) return
        try {
            val res = withContext(Dispatchers.IO) { api().request("campaigns") }
            render(res)
        } catch (e: ApiException) {
            b.campLive.text = e.message
        } catch (e: Exception) {
            b.campLive.text = getString(R.string.status_offline_hint)
        }
    }

    private fun render(res: JSONObject) {
        val live = res.optJSONObject("live")
        b.campLive.text = live?.optString("phase_text").orEmpty()
        val arr = res.optJSONArray("campaigns")
        b.campList.removeAllViews()
        if (arr == null || arr.length() == 0) {
            b.campList.addView(TextView(this).apply {
                setText(R.string.camp_empty)
                setTextColor(ContextCompat.getColor(context, R.color.slate))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(40), 0, 0)
            })
            return
        }
        for (i in 0 until arr.length()) b.campList.addView(row(arr.getJSONObject(i)))
    }

    private fun row(c: JSONObject): View {
        val id = c.getInt("id")
        val status = c.getString("status")
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(4)) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = c.getString("name")
            setTextColor(ContextCompat.getColor(context, R.color.ink))
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        head.addView(TextView(this).apply {
            text = status.replaceFirstChar { it.uppercase() }
            textSize = 11f
            setTextColor(ContextCompat.getColor(context, when (status) {
                "running" -> R.color.state_green
                "paused" -> R.color.state_amber
                "stopped" -> R.color.state_red
                else -> R.color.slate
            }))
        })
        col.addView(head)
        col.addView(TextView(this).apply {
            text = "${c.getInt("called")}/${c.getInt("total")} called · ${c.getInt("answered")} answered · ${c.getString("language_mode").uppercase()}"
            setTextColor(ContextCompat.getColor(context, R.color.slate))
            textSize = 11f
        })

        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun add(label: Int, onClick: () -> Unit) {
            btns.addView(MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                setText(label); textSize = 12f; minWidth = 0; minimumWidth = 0
                insetTop = 0; insetBottom = 0
                setPadding(dp(8), 0, dp(8), 0)
                layoutParams = LinearLayout.LayoutParams(-2, dp(34))
                setOnClickListener { onClick() }
            })
        }
        when (status) {
            "draft" -> {
                add(R.string.camp_start) { confirmStart(id) }
                add(R.string.camp_edit) { startActivity(Intent(this, CampaignEditActivity::class.java).putExtra("id", id)) }
                add(R.string.camp_copy) { act("campaign_duplicate", id) }
                add(R.string.camp_delete) { confirmDelete(id) }
            }
            "running" -> {
                add(R.string.camp_pause) { control(id, "pause") }
                add(R.string.camp_skip) { control(id, "skip") }
                add(R.string.camp_stop) { confirmStop(id) }
            }
            "paused" -> {
                add(R.string.camp_resume) { control(id, "resume") }
                add(R.string.camp_skip) { control(id, "skip") }
                add(R.string.camp_stop) { confirmStop(id) }
            }
            else -> {
                add(R.string.camp_copy) { act("campaign_duplicate", id) }
                add(R.string.camp_delete) { confirmDelete(id) }
            }
        }
        col.addView(btns)
        card.addView(col)
        return card
    }

    private fun confirmStart(id: Int) {
        val box = CheckBox(this).apply { setText(R.string.camp_confirm_start); textSize = 12f }
        val wrap = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(box) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.camp_start)
            .setView(wrap)
            .setPositiveButton(R.string.camp_start) { _, _ -> control(id, "start", box.isChecked) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmStop(id: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.camp_stop)
            .setMessage("The rest of this campaign's call list will be cancelled and cannot be resumed.")
            .setPositiveButton(R.string.camp_stop) { _, _ -> control(id, "stop") }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(id: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.camp_delete)
            .setMessage("Delete this campaign and its call records?")
            .setPositiveButton(R.string.camp_delete) { _, _ -> act("campaign_delete", id) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun control(id: Int, op: String, understand: Boolean = false) =
        act("campaign_control", id, JSONObject().put("op", op).put("understand", understand))

    private fun act(action: String, id: Int, extra: JSONObject = JSONObject()) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try {
                val body = extra.put("id", id)
                val res = withContext(Dispatchers.IO) { api().request(action, body) }
                val msg = res.optString("message")
                if (msg.isNotEmpty()) Toast.makeText(this@CampaignsActivity, msg, Toast.LENGTH_SHORT).show()
            } catch (e: ApiException) {
                Toast.makeText(this@CampaignsActivity, e.message, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@CampaignsActivity, "Could not reach the server.", Toast.LENGTH_LONG).show()
            } finally {
                busy = false
                refresh()
            }
        }
    }
}
