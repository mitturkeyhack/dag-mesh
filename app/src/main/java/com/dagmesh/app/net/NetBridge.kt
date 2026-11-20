package com.dagmesh.app.net

import com.dagmesh.app.mesh.MeshManager
import com.dagmesh.app.mesh.Packet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * İnternet köprüsü: interneti olan bir telefon, mesh'teki internetsiz telefonların aile sunucusu isteklerini taşır.
 *
 * Güvenlik:
 * - İstekler zaten aile anahtarıyla imzalı (HMAC), mesajlar aile anahtarıyla şifreli. Köprü telefon içeriği okuyamaz,
 *   değiştiremez (imza bozulur), başka aileye yönlendiremez.
 * - Mesh paketi ayrıca uçtan uca şifreli: aradaki telefonlar isteği hiç göremez.
 * - Köprü sadece Dağ Mesh aile sunucusuna gider; başka adrese istek taşımaz (açık vekil sunucu olamaz).
 * - Köprü başına kişi başı saatlik sınır var: kimse köprünün internetini tüketemez.
 */
class NetBridge(
    private val scope: CoroutineScope,
    private val mesh: MeshManager,
) {
    sealed class Mode {
        data object Direct : Mode()
        data class ViaGateway(val name: String, val hops: Int) : Mode()
        data object None : Mode()
    }

    private val gateways = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String?>>()
    private val served = ConcurrentHashMap<String, ArrayDeque<Long>>()

    val mode: StateFlow<Mode> = combine(mesh.ai.internet, gateways, mesh.peers) { online, gw, peers ->
        when {
            online -> Mode.Direct
            else -> bestGateway(gw, peers)?.let { Mode.ViaGateway(peers[it]?.name ?: "köprü", peers[it]?.hops ?: 1) } ?: Mode.None
        }
    }.stateIn(scope, SharingStarted.Eagerly, Mode.None)

    /** Bu telefon başkalarına internet köprüsü olabilir mi? (İnternet var ve "Başkalarına köprü ol" açık.) */
    fun isGatewayNow(): Boolean = mesh.ai.internet.value && mesh.ai.share.value

    /**
     * Aile sunucusuna isteği gönderir: internet varsa doğrudan, yoksa en yakın köprü üzerinden.
     * Cevap gövdesini döner; hiçbir yol yoksa ya da cevap gelmezse null.
     */
    suspend fun call(body: String): String? {
        if (mesh.ai.internet.value) return post(body)
        val gateway = bestGateway(gateways.value, mesh.peers.value) ?: return null
        val requestId = Packet.newId()
        val deferred = CompletableDeferred<String?>()
        pending[requestId] = deferred
        try {
            val packet = mesh.newPacket(Packet.TYPE_NET, to = gateway, ref = requestId, text = body)
            if (!mesh.sendTracked(packet) { }) return null
            return withTimeoutOrNull(RELAY_TIMEOUT_MS) { deferred.await() }
        } finally {
            pending.remove(requestId)
        }
    }

    /** Ben köprüyüm: başkasının isteğini sunucuya taşı, cevabı geri gönder. */
    fun onRequest(packet: Packet) {
        val ref = packet.ref ?: return
        val body = packet.text
        if (body == null || body.length > MAX_BODY || !isGatewayNow() || !allow(packet.from)) {
            reply(packet.from, ref, null)
            return
        }
        scope.launch { reply(packet.from, ref, post(body)) }
    }

    fun onResponse(packet: Packet) {
        val ref = packet.ref ?: return
        val result = if (packet.kind == KIND_FAIL) null else packet.text
        if (packet.kind == KIND_FAIL) gateways.update { it - packet.from }
        pending[ref]?.complete(result)
    }

    fun onAnnounce(from: String, isGateway: Boolean) {
        if (from == mesh.myId) return
        if (isGateway) gateways.update { it + (from to System.currentTimeMillis()) }
        else if (from in gateways.value) gateways.update { it - from }
    }

    fun tick() {
        val cutoff = System.currentTimeMillis() - mesh.peerTimeoutMs
        gateways.update { map -> map.filterValues { it >= cutoff } }
    }

    // ---------------------------------------------------------------- yardımcılar

    private fun reply(to: String, ref: String, body: String?) {
        val packet = mesh.newPacket(Packet.TYPE_NET_R, to = to, ref = ref, text = body.orEmpty())
            .copy(kind = if (body == null) KIND_FAIL else KIND_OK)
        mesh.send(packet)
    }

    private fun allow(requester: String): Boolean {
        val now = System.currentTimeMillis()
        val times = served.getOrPut(requester) { ArrayDeque() }
        synchronized(times) {
            while (times.isNotEmpty() && now - times.first() > 3_600_000L) times.removeFirst()
            if (times.size >= PER_REQUESTER_HOURLY) return false
            times.addLast(now)
        }
        return true
    }

    private fun bestGateway(gw: Map<String, Long>, peers: Map<String, MeshManager.Peer>): String? {
        val cutoff = System.currentTimeMillis() - mesh.peerTimeoutMs
        val alive = gw.filter { (id, seen) -> seen >= cutoff && id != mesh.myId }
        return alive.keys.minWithOrNull(compareBy<String>({ peers[it]?.hops ?: 99 }, { -(alive[it] ?: 0L) }))
    }

    /** Sunucuya POST. 2xx-4xx cevabın gövdesini döner (hata gövdesi de bilgi taşır); ağ hatasında null. */
    private suspend fun post(body: String): String? = withContext(Dispatchers.IO) {
        val connection = try {
            URL(ENDPOINT).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return@withContext null
        }
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            if (code >= 500 || stream == null) null else stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /** Dağ Mesh aile sunucusu (Supabase Edge Function). Köprüler sadece buraya istek taşır. */
        const val ENDPOINT = "https://dhkhlptixkbyennclhji.supabase.co/functions/v1/aile"
        const val GATEWAY_MARK = "net"
        private const val KIND_OK = "ok"
        private const val KIND_FAIL = "fail"
        private const val MAX_BODY = 16_000
        private const val PER_REQUESTER_HOURLY = 240
        private const val RELAY_TIMEOUT_MS = 45_000L
        private const val GATEWAY_TIMEOUT_MS = 100_000L
    }
}
