package com.dagmesh.app.map

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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Dağda işe yarayan işaret türleri. Süresi 0 olanlar kalıcıdır. */
enum class PinType(val key: String, val title: String, val emoji: String, val color: Long, val lifeHours: Int, val hint: String) {
    WATER("su", "Su kaynağı", "💧", 0xFF3B9EFF, 0, "Çeşme, pınar, dere"),
    SHELTER("barinak", "Barınak / ağıl", "🛖", 0xFFB7865B, 0, "Yayla evi, ağıl, mağara"),
    DANGER("tehlike", "Tehlike", "⚠️", 0xFFE5484D, 0, "Uçurum, heyelan, kaygan yol"),
    ROAD_CLOSED("yol", "Yol kapalı", "⛔", 0xFFF08A3E, 72, "Kar, çamur, düşen ağaç"),
    SIGNAL("sebeke", "Şebeke çekiyor", "📶", 0xFF2EC4B6, 0, "Telefon/internet burada çekiyor"),
    HERD("suru", "Sürü burada", "🐑", 0xFF9AA5B1, 24, "Sürünün son görüldüğü yer"),
    MEET("bulusma", "Buluşma noktası", "📍", 0xFFA78BFA, 24, "Grup burada toplanacak"),
    CAMP("kamp", "Kamp yeri", "⛺", 0xFF4CAF7A, 168, "Çadır kurulabilir düzlük"),
    NOTE("not", "Not", "📝", 0xFFD4B24C, 168, "Diğer");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: NOTE
    }
}

data class Pin(
    val id: String,
    val type: PinType,
    val lat: Double,
    val lon: Double,
    val note: String,
    val authorId: String,
    val authorName: String,
    val time: Long,
    val expiresAt: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("t", type.key).put("la", lat).put("lo", lon).put("n", note)
        .put("a", authorId).put("an", authorName).put("ti", time).put("ex", expiresAt)

    companion object {
        fun fromJson(o: JSONObject): Pin? = try {
            Pin(
                id = o.getString("id"),
                type = PinType.of(o.optString("t")),
                lat = o.getDouble("la"),
                lon = o.getDouble("lo"),
                note = o.optString("n").take(200),
                authorId = o.optString("a"),
                authorName = o.optString("an").take(24),
                time = o.optLong("ti"),
                expiresAt = o.optLong("ex"),
            )
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Haritadaki ortak işaretler. Biri işaret koyunca menzildeki herkese gider;
 * yeni biri yanına gelince bildiğin işaretler ona da aktarılır (dedikodu gibi yayılır).
 */
object PinStore {
    private val _pins = MutableStateFlow<Map<String, Pin>>(emptyMap())
    val pins: StateFlow<Map<String, Pin>> = _pins.asStateFlow()

    /** Silinen işaretler geri gelmesin diye kimlikleri saklanır. */
    private val deleted = HashSet<String>()
    private lateinit var file: File
    private lateinit var mesh: MeshManager

    fun init(context: Context, mesh: MeshManager, scope: CoroutineScope) {
        this.mesh = mesh
        file = File(context.filesDir, "pins.json")
        load()
        // Yeni doğrudan bağlanan telefona bildiğimiz işaretleri aktar.
        scope.launch {
            var known = emptySet<String>()
            mesh.peers.collect { map ->
                val direct = map.values.filter { it.isDirect }.map { it.id }.toSet()
                val fresh = direct - known
                known = direct
                if (fresh.isNotEmpty() && _pins.value.isNotEmpty()) {
                    delay(3000)
                    fresh.forEach { syncTo(it) }
                }
            }
        }
        scope.launch {
            while (true) { delay(10 * 60_000); prune() }
        }
    }

    fun add(type: PinType, lat: Double, lon: Double, note: String) {
        val now = System.currentTimeMillis()
        val pin = Pin(
            id = UUID.randomUUID().toString().take(12),
            type = type, lat = lat, lon = lon, note = note.trim().take(200),
            authorId = mesh.myId, authorName = mesh.myName,
            time = now,
            expiresAt = if (type.lifeHours > 0) now + type.lifeHours * 3_600_000L else 0L,
        )
        _pins.update { it + (pin.id to pin) }
        save()
        mesh.broadcast(Packet.TYPE_PIN, pin.id, JSONObject().put("op", "add").put("pins", JSONArray().put(pin.toJson())).toString())
    }

    fun remove(pin: Pin) {
        if (pin.authorId != mesh.myId) {
            // Başkasının işaretini sadece kendi haritandan gizle.
            hide(pin.id)
            return
        }
        hide(pin.id)
        mesh.broadcast(Packet.TYPE_PIN, pin.id, JSONObject().put("op", "del").put("id", pin.id).put("a", pin.authorId).toString())
    }

    private fun hide(id: String) {
        deleted += id
        _pins.update { it - id }
        save()
    }

    fun onPacket(packet: Packet) {
        val o = try { JSONObject(packet.text ?: return) } catch (_: Exception) { return }
        when (o.optString("op")) {
            "add" -> {
                val arr = o.optJSONArray("pins") ?: return
                val now = System.currentTimeMillis()
                val incoming = (0 until arr.length()).mapNotNull { Pin.fromJson(arr.getJSONObject(it)) }
                    .filter { it.id !in deleted && (it.expiresAt == 0L || it.expiresAt > now) }
                if (incoming.isEmpty()) return
                _pins.update { map -> map + incoming.filter { it.id !in map }.associateBy { it.id } }
                save()
            }
            "del" -> {
                val id = o.optString("id")
                val existing = _pins.value[id]
                // Sadece işareti koyan silebilir.
                if (existing == null || existing.authorId == packet.from) {
                    deleted += id
                    _pins.update { it - id }
                    save()
                }
            }
        }
    }

    private fun syncTo(peerId: String) {
        prune()
        _pins.value.values.sortedByDescending { it.time }.take(300).chunked(60).forEach { part ->
            val arr = JSONArray().also { a -> part.forEach { a.put(it.toJson()) } }
            mesh.sendTo(Packet.TYPE_PIN, peerId, "sync", JSONObject().put("op", "add").put("pins", arr).toString())
        }
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        val before = _pins.value.size
        _pins.update { m -> m.filterValues { it.expiresAt == 0L || it.expiresAt > now } }
        if (_pins.value.size != before) save()
    }

    @Synchronized
    private fun save() {
        try {
            val o = JSONObject()
            o.put("pins", JSONArray().also { a -> _pins.value.values.forEach { a.put(it.toJson()) } })
            o.put("deleted", JSONArray(deleted.toList().takeLast(500)))
            file.writeText(o.toString())
        } catch (_: Exception) {}
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val o = JSONObject(file.readText())
            val d = o.optJSONArray("deleted")
            if (d != null) for (i in 0 until d.length()) deleted += d.getString(i)
            val arr = o.optJSONArray("pins") ?: return
            _pins.value = (0 until arr.length()).mapNotNull { Pin.fromJson(arr.getJSONObject(it)) }.associateBy { it.id }
            prune()
        } catch (_: Exception) {}
    }
}
