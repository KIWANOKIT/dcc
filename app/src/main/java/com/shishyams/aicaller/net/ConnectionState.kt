package com.shishyams.aicaller.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the screen shows. The service writes it, the activity observes it (same process). */
object ConnectionState {

    enum class Link { IDLE, CONNECTING, ONLINE_WS, ONLINE_HTTPS, OFFLINE, UNPAIRED }

    data class Snapshot(
        val link: Link = Link.IDLE,
        val detail: String = "",
        val lastBeatAt: Long = 0L
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    fun set(link: Link, detail: String = "", beat: Boolean = false) {
        _state.update {
            it.copy(
                link = link,
                detail = detail,
                lastBeatAt = if (beat) System.currentTimeMillis() else it.lastBeatAt
            )
        }
    }

    fun beat() {
        _state.update { it.copy(lastBeatAt = System.currentTimeMillis()) }
    }

    fun log(line: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _log.update { (listOf("$ts  $line") + it).take(30) }
    }
}
