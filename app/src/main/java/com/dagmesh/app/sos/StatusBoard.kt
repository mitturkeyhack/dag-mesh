package com.dagmesh.app.sos

import android.content.Context
import com.dagmesh.app.mesh.MeshManager
import com.dagmesh.app.mesh.Packet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Köy yoklaması: herkes "iyiyim / yaralıyım / yardım lazım" durumunu mesh'e imzalı olarak yayar.
 * Durum 24 saat geçerli; yeni gelenler görsün diye 5 dakikada bir ve yeni biri bağlanınca tekrar yayılır.
 */
class StatusBoard(context: Context, private val scope: CoroutineScope, private val mesh: MeshManager) {

    data class Entry(val id: String, val name: String, val status: String, val at: Long)
    data class Mine(val status: String, val at: Long)

    private val prefs = context.getSharedPreferences("status", Context.MODE_PRIVATE)

    private val _mine = MutableStateFlow(loadMine())
    val mine: StateFlow<Mine?> = _mine.asStateFlow()

    private val _entries = MutableStateFlow<Map<String, Entry>>(emptyMap())
    val entries: StateFlow<Map<String, Entry>> = _entries.asStateFlow()

    private var lastBroadcast = 0L
    @Volatile private var broadcastScheduled = false

    fun set(status: String) {
        val m = Mine(status, System.currentTimeMillis())
        _mine.value = m
        prefs.edit().putString(KEY_STATUS, status).putLong(KEY_AT, m.at).apply()
        broadcast()
    }

    fun onStatus(packet: Packet) {
        val parts = packet.text?.split('|') ?: return
        val status = parts.getOrNull(0)?.takeIf { it in VALID } ?: return
        val at = parts.getOrNull(1)?.toLongOrNull() ?: return
        _entries.update { map ->
            val prev = map[packet.from]
            if (prev != null && prev.at >= at) map else map + (packet.from to Entry(packet.from, packet.fromName, status, at))
        }
    }

    /** Yeni biri bağlandı: durumumuzu kısa süre içinde tekrar yay (art arda bağlantılarda bir kez). */
    fun onNewPeer() {
        if (_mine.value == null || broadcastScheduled) return
        broadcastScheduled = true
        scope.launch {
            delay(3_000)
            broadcastScheduled = false
            broadcast()
        }
    }

    fun tick() {
        val now = System.currentTimeMillis()
        _entries.update { map -> map.filterValues { now - it.at < VALID_MS } }
        val m = _mine.value
        if (m != null && now - m.at >= VALID_MS) {
            _mine.value = null
            prefs.edit().clear().apply()
        } else if (m != null && now - lastBroadcast > REBROADCAST_MS) {
            broadcast()
        }
    }

    private fun broadcast() {
        val m = _mine.value ?: return
        lastBroadcast = System.currentTimeMillis()
        mesh.broadcast(Packet.TYPE_STATUS, Packet.newId(), "${m.status}|${m.at}")
    }

    private fun loadMine(): Mine? {
        val status = prefs.getString(KEY_STATUS, null) ?: return null
        val at = prefs.getLong(KEY_AT, 0L)
        return if (System.currentTimeMillis() - at < VALID_MS) Mine(status, at) else null
    }

    companion object {
        val VALID = setOf("ok", "hurt", "help")
        private const val KEY_STATUS = "status"
        private const val KEY_AT = "at"
        private const val VALID_MS = 24L * 60 * 60 * 1000
        private const val REBROADCAST_MS = 5L * 60 * 1000
    }
}
