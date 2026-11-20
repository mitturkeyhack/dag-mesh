package com.dagmesh.app.family

import android.content.Context
import android.util.Base64
import android.util.Log
import com.dagmesh.app.media.LocationHelper
import com.dagmesh.app.mesh.MeshManager
import com.dagmesh.app.mesh.Packet
import com.dagmesh.app.net.NetBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Ailem: aile kodu ile kurulan, uçtan uca şifreli küçük grup.
 *
 * Bir mesaj (yazı ya da "Güvendeyim" durumu) üç yoldan birden gider, hangisi açıksa:
 *  1. İnternet varsa doğrudan aile sunucusuna.
 *  2. İnternet yoksa mesh'teki bir köprü telefon üzerinden sunucuya ([NetBridge]).
 *  3. Yakındaki aile üyelerine mesh üzerinden doğrudan kopya (hiç internet olmasa da).
 * Aynı mesaj iki yoldan gelirse bir kez gösterilir. Mesh'ten gelen mesajı internete erişen üye sunucuya da bırakır:
 * böylece gönderenin hiç interneti olmasa bile mesaj uzaktaki aileye ulaşır.
 *
 * Her mesaj gönderenin kimlik anahtarıyla imzalı: aile içinden biri bile başkası adına yazamaz.
 */
class FamilyManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val mesh: MeshManager,
) {
    data class Message(
        val id: String,
        val from: String,
        val name: String,
        val ts: Long,
        val kind: String,
        val text: String,
        val status: String?,
        val lat: Double?,
        val lon: Double?,
        val via: String,
        val mine: Boolean,
        val synced: Boolean,
    )

    data class Member(
        val id: String,
        val name: String,
        val status: String?,
        val statusAt: Long,
        val lastSeen: Long,
        val via: String,
        val lat: Double?,
        val lon: Double?,
        val isMe: Boolean,
    )

    data class Info(val code: String, val fid: String) {
        val display: String get() = FamilyCrypto.display(code)
    }

    private val file = File(context.filesDir, "family.json")
    private val lock = Mutex()
    private var keys: FamilyCrypto.Keys? = null
    private var registered = false
    private var lastSeq = 0L
    private val outbox = LinkedHashMap<String, String>() // mesaj kimliği → şifreli kutu

    private val _info = MutableStateFlow<Info?>(null)
    val info: StateFlow<Info?> = _info.asStateFlow()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _members = MutableStateFlow<List<Member>>(emptyList())
    val members: StateFlow<List<Member>> = _members.asStateFlow()

    private val _unread = MutableStateFlow(0)
    val unread: StateFlow<Int> = _unread.asStateFlow()

    /** Ekranda aile sohbeti açık mı (bildirim göstermemek ve okunmamış saymamak için). */
    @Volatile var screenOpen = false

    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(Dispatchers.IO) {
            lock.withLock { load() }
            syncLoop()
        }
    }

    // ---------------------------------------------------------------- kurma / katılma

    /** Yeni aile kurar ve kodunu döner. */
    suspend fun create(): String {
        val code = FamilyCrypto.newCode()
        join(code)
        return code
    }

    /** Aile koduyla katılır. Kod geçersizse false. Türetme birkaç saniye sürebilir. */
    suspend fun join(input: String): Boolean {
        val code = FamilyCrypto.normalize(input) ?: return false
        val derived = withContext(Dispatchers.Default) { FamilyCrypto.derive(code) }
        lock.withLock {
            keys = derived
            registered = false
            lastSeq = 0
            outbox.clear()
            _messages.value = emptyList()
            _info.value = Info(code, derived.fid)
            save()
        }
        post(kind = KIND_JOIN, text = "", status = null)
        return true
    }

    /** Bu telefondan aileyi kaldırır (sunucudaki kutular 30 gün içinde kendiliğinden silinir). */
    suspend fun leave() = lock.withLock {
        keys = null
        registered = false
        lastSeq = 0
        outbox.clear()
        _info.value = null
        _messages.value = emptyList()
        _members.value = emptyList()
        _unread.value = 0
        file.delete()
    }

    // ---------------------------------------------------------------- gönderme

    fun sendText(text: String) {
        val clean = text.trim().take(MAX_TEXT)
        if (clean.isEmpty()) return
        scope.launch { post(KIND_TEXT, clean, null) }
    }

    /** "ok" / "hurt" / "help". Konum varsa eklenir. */
    fun sendStatus(status: String) {
        scope.launch { post(KIND_STATUS, "", status) }
    }

    fun markRead() {
        _unread.value = 0
    }

    private suspend fun post(kind: String, text: String, status: String?) {
        val k = keys ?: return
        val loc = if (kind == KIND_STATUS) LocationHelper.lastKnownQuick(context) else null
        val id = Packet.newId()
        val ts = System.currentTimeMillis()
        val lat = loc?.latitude?.let { Math.round(it * 1e5) / 1e5 }
        val lon = loc?.longitude?.let { Math.round(it * 1e5) / 1e5 }
        val me = mesh.myId
        val name = mesh.myName
        val sig = mesh.crypto.signDetached(canonical(id, me, name, ts, kind, text, status, lat, lon))
        val envelope = JSONObject()
            .put("v", 1).put("id", id).put("f", me).put("n", name).put("t", ts).put("k", kind)
            .put("x", text).put("kc", mesh.crypto.myCertB64).put("sg", sig)
        status?.let { envelope.put("s", it) }
        lat?.let { envelope.put("la", it) }
        lon?.let { envelope.put("lo", it) }
        val ct = FamilyCrypto.seal(k, id, envelope.toString())

        val message = Message(id, me, name, ts, kind, text, status, lat, lon, VIA_ME, mine = true, synced = false)
        lock.withLock {
            outbox[id] = ct
            _messages.value = (_messages.value + message).sortedBy { it.ts }
            recomputeMembers()
            save()
        }
        // Yakındaki aile üyelerine mesh üzerinden doğrudan kopya.
        val copy = JSONObject().put("fid", k.fid).put("ct", ct).toString()
        _members.value.filter { !it.isMe }.forEach { mesh.sendTo(Packet.TYPE_FAM, it.id, id, copy) }
        wake.trySend(Unit)
    }

    // ---------------------------------------------------------------- alma

    /** Mesh üzerinden doğrudan gelen aile mesajı kopyası. */
    fun onMeshCopy(packet: Packet) {
        val id = packet.ref ?: return
        val o = runCatching { JSONObject(packet.text ?: return) }.getOrNull() ?: return
        val k = keys ?: return
        if (o.optString("fid") != k.fid) return
        val ct = o.optString("ct").takeIf { it.isNotEmpty() } ?: return
        scope.launch {
            // İnternete erişebilen üye mesajı sunucuya da bırakır (gönderenin hiç interneti olmasa da uzağa ulaşsın).
            if (ingest(id, ct, VIA_MESH)) lock.withLock { if (id !in outbox) { outbox[id] = ct; save() } }
            wake.trySend(Unit)
        }
    }

    /** Şifreli kutuyu açar, imzayı doğrular, kaydeder. Yeni mesajsa true. */
    private suspend fun ingest(id: String, ct: String, via: String): Boolean {
        val k = keys ?: return false
        if (_messages.value.any { it.id == id }) return false
        val plain = FamilyCrypto.open(k, id, ct) ?: return false
        val o = runCatching { JSONObject(plain) }.getOrNull() ?: return false
        if (o.optString("id") != id) return false
        val from = o.optString("f")
        val name = o.optString("n", "?")
        val ts = o.optLong("t")
        val kind = o.optString("k")
        val text = o.optString("x")
        val status = o.optString("s").takeIf { it.isNotEmpty() }
        val lat = if (o.has("la")) o.optDouble("la") else null
        val lon = if (o.has("lo")) o.optDouble("lo") else null
        val ok = mesh.crypto.verifyDetached(
            from, o.optString("kc"), canonical(id, from, name, ts, kind, text, status, lat, lon), o.optString("sg"),
        )
        if (!ok) return false
        val mine = from == mesh.myId
        val message = Message(id, from, name, ts, kind, text, status, lat, lon, if (mine) VIA_ME else via, mine, synced = via != VIA_MESH)
        lock.withLock {
            if (_messages.value.any { it.id == id }) return false
            _messages.value = (_messages.value + message).sortedBy { it.ts }.takeLast(MAX_MESSAGES)
            recomputeMembers()
            save()
        }
        if (!mine) {
            if (!screenOpen) _unread.value += 1
            if (!screenOpen) mesh.emitIncoming(NOTIFY_PEER, "Ailem", preview(message))
        }
        return true
    }

    // ---------------------------------------------------------------- eşitleme

    private suspend fun syncLoop() {
        while (true) {
            if (keys != null) runCatching { syncOnce() }.onFailure { Log.w(TAG, "Aile eşitlemesi başarısız", it) }
            val wait = if (mesh.ai.internet.value) 15_000L else 30_000L
            withTimeoutOrNull(wait) { wake.receive() }
            delay(300)
        }
    }

    private suspend fun syncOnce() {
        val k = keys ?: return
        val mode = mesh.net.mode.value
        if (mode is NetBridge.Mode.None) return

        if (!registered) {
            val body = JSONObject().put("op", "create").put("fid", k.fid).put("auth", FamilyCrypto.authB64(k)).toString()
            val resp = mesh.net.call(body)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
            if (resp.optBoolean("ok")) lock.withLock { registered = true; save() } else return
        }

        for ((id, ct) in lock.withLock { outbox.toList() }) {
            val ts = System.currentTimeMillis()
            val body = JSONObject().put("op", "put").put("fid", k.fid).put("ts", ts).put("id", id).put("ct", ct)
                .put("mac", FamilyCrypto.mac(k, "put", ts, "$id\n$ct")).toString()
            val resp = mesh.net.call(body)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
            if (resp.optString("error") == "no_family") {
                // Sunucu aileyi tanımıyor (ör. sunucu sıfırlandı): bir sonraki turda yeniden kaydol.
                lock.withLock { registered = false; save() }
                return
            }
            if (resp.optBoolean("ok")) {
                lock.withLock {
                    outbox.remove(id)
                    _messages.value = _messages.value.map { if (it.id == id) it.copy(synced = true) else it }
                    save()
                }
            }
        }

        val direct = mode is NetBridge.Mode.Direct
        val limit = if (direct) 200 else 16
        repeat(5) {
            val ts = System.currentTimeMillis()
            val since = lastSeq
            val body = JSONObject().put("op", "get").put("fid", k.fid).put("ts", ts).put("since", since).put("limit", limit)
                .put("mac", FamilyCrypto.mac(k, "get", ts, since.toString())).toString()
            val resp = mesh.net.call(body)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
            val arr = resp.optJSONArray("messages") ?: return
            var maxSeq = since
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                maxSeq = maxOf(maxSeq, m.optLong("seq"))
                ingest(m.optString("id"), m.optString("ct"), if (direct) VIA_INTERNET else VIA_BRIDGE)
            }
            lock.withLock { lastSeq = maxSeq; save() }
            if (arr.length() < limit) return
        }
    }

    // ---------------------------------------------------------------- yardımcılar

    private fun recomputeMembers() {
        val me = mesh.myId
        val byId = LinkedHashMap<String, Member>()
        for (m in _messages.value) {
            val prev = byId[m.from]
            val isStatus = m.kind == KIND_STATUS && m.status != null
            byId[m.from] = Member(
                id = m.from,
                name = m.name,
                status = if (isStatus) m.status else prev?.status,
                statusAt = if (isStatus) m.ts else prev?.statusAt ?: 0L,
                lastSeen = maxOf(prev?.lastSeen ?: 0L, m.ts),
                via = m.via,
                lat = if (isStatus && m.lat != null) m.lat else prev?.lat,
                lon = if (isStatus && m.lon != null) m.lon else prev?.lon,
                isMe = m.from == me,
            )
        }
        _members.value = byId.values.sortedWith(compareBy({ !it.isMe }, { it.name.lowercase() }))
    }

    private fun canonical(
        id: String, from: String, name: String, ts: Long, kind: String, text: String,
        status: String?, lat: Double?, lon: Double?,
    ): ByteArray = listOf("dmf1", id, from, name, ts.toString(), kind, text, status.orEmpty(), lat?.toString().orEmpty(), lon?.toString().orEmpty())
        .joinToString("\u0000").toByteArray(Charsets.UTF_8)

    private fun preview(m: Message): String = when {
        m.kind == KIND_STATUS -> "${m.name}: " + statusLabel(m.status)
        m.kind == KIND_JOIN -> "${m.name} aileye katıldı"
        else -> "${m.name}: ${m.text.take(120)}"
    }

    private fun save() {
        val k = keys ?: return
        val o = JSONObject()
        val secret = k.code + "|" + Base64.encodeToString(k.encKey, Base64.NO_WRAP) + "|" + Base64.encodeToString(k.authKey, Base64.NO_WRAP)
        o.put("keys", mesh.identityVault.protect(secret.toByteArray(Charsets.UTF_8)))
        o.put("registered", registered)
        o.put("lastSeq", lastSeq)
        o.put("outbox", JSONArray().apply { outbox.forEach { (id, ct) -> put(JSONObject().put("id", id).put("ct", ct)) } })
        o.put("messages", JSONArray().apply {
            _messages.value.forEach { m ->
                put(JSONObject().put("id", m.id).put("f", m.from).put("n", m.name).put("t", m.ts).put("k", m.kind)
                    .put("x", m.text).put("s", m.status).put("la", m.lat).put("lo", m.lon).put("via", m.via)
                    .put("mine", m.mine).put("sy", m.synced))
            }
        })
        val tmp = File(file.parentFile, "family.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file)
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val o = JSONObject(file.readText())
            val secret = mesh.identityVault.unprotect(o.getString("keys"))?.toString(Charsets.UTF_8) ?: return
            val parts = secret.split("|")
            if (parts.size != 3) return
            val k = FamilyCrypto.Keys(parts[0], Base64.decode(parts[1], Base64.NO_WRAP), Base64.decode(parts[2], Base64.NO_WRAP))
            keys = k
            registered = o.optBoolean("registered")
            lastSeq = o.optLong("lastSeq")
            o.optJSONArray("outbox")?.let { arr ->
                for (i in 0 until arr.length()) arr.getJSONObject(i).let { outbox[it.getString("id")] = it.getString("ct") }
            }
            val list = mutableListOf<Message>()
            o.optJSONArray("messages")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    list += Message(
                        id = m.getString("id"), from = m.getString("f"), name = m.optString("n", "?"), ts = m.optLong("t"),
                        kind = m.optString("k"), text = m.optString("x"),
                        status = m.optString("s").takeIf { it.isNotEmpty() && it != "null" },
                        lat = if (m.has("la") && !m.isNull("la")) m.getDouble("la") else null,
                        lon = if (m.has("lo") && !m.isNull("lo")) m.getDouble("lo") else null,
                        via = m.optString("via"), mine = m.optBoolean("mine"), synced = m.optBoolean("sy"),
                    )
                }
            }
            _messages.value = list.sortedBy { it.ts }
            _info.value = Info(k.code, k.fid)
            recomputeMembers()
        } catch (e: Exception) {
            Log.w(TAG, "Aile kaydı okunamadı", e)
        }
    }

    companion object {
        private const val TAG = "DagMesh/Aile"
        const val NOTIFY_PEER = "family"
        const val KIND_TEXT = "text"
        const val KIND_STATUS = "status"
        const val KIND_JOIN = "join"
        const val VIA_ME = "ben"
        const val VIA_MESH = "mesh"
        const val VIA_INTERNET = "internet"
        const val VIA_BRIDGE = "köprü"
        private const val MAX_TEXT = 1500
        private const val MAX_MESSAGES = 500

        fun statusLabel(status: String?): String = when (status) {
            "ok" -> "Güvendeyim"
            "hurt" -> "Yaralıyım"
            "help" -> "Yardım lazım"
            else -> "Durum yok"
        }
    }
}
