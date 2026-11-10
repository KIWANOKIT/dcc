package com.shishyams.aicaller

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.shishyams.aicaller.data.Prefs
import com.shishyams.aicaller.databinding.ActivityCampaignEditBinding
import com.shishyams.aicaller.net.ApiClient
import com.shishyams.aicaller.net.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** New Campaign / edit a draft campaign. Recordings of your own voice stay a dashboard feature and are kept as they are. */
class CampaignEditActivity : AppCompatActivity() {

    private lateinit var b: ActivityCampaignEditBinding
    private lateinit var prefs: Prefs
    private var campaignId = 0
    private val langKeys = listOf("auto", "hi", "mr", "en")
    private val langLabels = listOf("Auto-detect", "Hindi", "Marathi", "English")
    private var toneKeys = listOf("friendly", "professional", "casual")
    private var toneLabels = listOf("Friendly & warm", "Professional", "Casual & short")
    private val listBoxes = mutableListOf<Pair<Int, CheckBox>>()
    private var pendingLists: Set<Int> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCampaignEditBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs.get(this)
        campaignId = intent.getIntExtra("id", 0)
        if (campaignId > 0) b.editTitle.setText(R.string.camp_edit_title)

        b.fLanguage.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, langLabels)
        b.fAudGroup.setOnCheckedChangeListener { _, checked ->
            b.fListsBox.visibility = if (checked == R.id.f_aud_lists) View.VISIBLE else View.GONE
        }
        b.fSave.setOnClickListener { save() }
        load()
    }

    private fun api() = ApiClient(prefs.serverUrl, prefs.deviceToken)

    private fun busy(on: Boolean) {
        b.fProgress.visibility = if (on) View.VISIBLE else View.GONE
        b.fSave.isEnabled = !on
    }

    private fun showError(msg: String?) {
        b.fError.text = msg.orEmpty()
        b.fError.visibility = if (msg.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /** Fetches the imports (and tones) and, when editing, the campaign itself. */
    private fun load() {
        busy(true)
        lifecycleScope.launch {
            try {
                val (meta, camp) = withContext(Dispatchers.IO) {
                    val m = api().request("campaigns")
                    val c = if (campaignId > 0) api().request("campaign_get", JSONObject().put("id", campaignId)) else null
                    m to c
                }
                meta.optJSONObject("tones")?.let { t ->
                    toneKeys = t.keys().asSequence().toList()
                    toneLabels = toneKeys.map { t.getString(it) }
                }
                b.fTone.adapter = ArrayAdapter(this@CampaignEditActivity, android.R.layout.simple_spinner_dropdown_item, toneLabels)

                val c = camp?.optJSONObject("campaign")
                if (c != null) {
                    if (!c.optBoolean("editable")) {
                        showError(getString(R.string.camp_readonly))
                        b.fSave.isEnabled = false
                    }
                    fill(c)
                }
                buildLists(meta.optJSONArray("lists") ?: JSONArray())
                busy(false)
                if (c != null && !c.optBoolean("editable")) b.fSave.isEnabled = false
            } catch (e: ApiException) {
                busy(false); showError(e.message)
            } catch (e: Exception) {
                busy(false); showError(getString(R.string.status_offline_hint))
            }
        }
    }

    private fun fill(c: JSONObject) {
        val s = c.optJSONObject("script") ?: JSONObject()
        b.fName.setText(c.optString("name"))
        b.fLanguage.setSelection(langKeys.indexOf(c.optString("language_mode")).coerceAtLeast(0))
        b.fTone.setSelection(toneKeys.indexOf(s.optString("tone")).coerceAtLeast(0))
        b.fPersona.setText(s.optString("persona"))
        b.fGoal.setText(s.optString("goal"))
        b.fOpening.setText(s.optString("opening"))
        b.fPitch.setText(s.optString("pitch"))
        b.fNotint.setText(s.optString("not_interested"))
        b.fCallback.setText(s.optString("callback"))
        b.fClosing.setText(s.optString("closing"))
        b.fRules.setText(s.optString("rules"))
        val aud = c.optJSONObject("audience")
        if (aud?.optString("mode") == "lists") {
            val ids = aud.optJSONArray("lists") ?: JSONArray()
            pendingLists = (0 until ids.length()).map { ids.getInt(it) }.toSet()
            b.fAudLists.isChecked = true
        }
    }

    private fun buildLists(arr: JSONArray) {
        b.fListsBox.removeAllViews(); listBoxes.clear()
        if (arr.length() == 0) {
            b.fAudLists.isEnabled = false
            return
        }
        for (i in 0 until arr.length()) {
            val l = arr.getJSONObject(i)
            val cb = CheckBox(this).apply {
                text = "${l.getString("name")} (${l.getInt("count")})"
                textSize = 12f
                isChecked = l.getInt("id") in pendingLists
            }
            listBoxes += l.getInt("id") to cb
            b.fListsBox.addView(cb)
        }
    }

    private fun save() {
        showError(null)
        val body = JSONObject()
            .put("id", campaignId)
            .put("name", b.fName.text.toString())
            .put("language_mode", langKeys[b.fLanguage.selectedItemPosition])
            .put("tone", toneKeys.getOrElse(b.fTone.selectedItemPosition) { "friendly" })
            .put("persona", b.fPersona.text.toString())
            .put("goal", b.fGoal.text.toString())
            .put("opening", b.fOpening.text.toString())
            .put("pitch", b.fPitch.text.toString())
            .put("not_interested", b.fNotint.text.toString())
            .put("callback", b.fCallback.text.toString())
            .put("closing", b.fClosing.text.toString())
            .put("rules", b.fRules.text.toString())
            .put("audience_mode", if (b.fAudLists.isChecked) "lists" else "all")
            .put("list_ids", JSONArray(listBoxes.filter { it.second.isChecked }.map { it.first }))
        busy(true)
        lifecycleScope.launch {
            try {
                val res = withContext(Dispatchers.IO) { api().request("campaign_save", body) }
                android.widget.Toast.makeText(
                    this@CampaignEditActivity,
                    getString(R.string.camp_saved) + " " + res.optInt("queued") + " contacts queued.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                finish()
            } catch (e: ApiException) {
                busy(false); showError(e.message)
            } catch (e: Exception) {
                busy(false); showError(getString(R.string.status_offline_hint))
            }
        }
    }
}
