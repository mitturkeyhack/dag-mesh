package com.dagmesh.app.mesh

import android.content.Context
import android.os.Build
import com.dagmesh.app.call.CallManager
import com.dagmesh.app.data.Identity
import com.dagmesh.app.data.MeshDao
import com.dagmesh.app.data.MessageEntity
import com.dagmesh.app.data.PeerEntity
import com.dagmesh.app.data.previewText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Ağın kalbi: çok kanallı mesh motoru.
 *
 * - Birden fazla kanalı (Bluetooth/Nearby, Wi‑Fi Aware) aynı anda çalıştırır.
 *   Hangi kanal karşı telefonu önce bulursa onunla bağlanılır, biri koparsa diğeri devralır.
 * - Kendine gelmeyen paketleri diğer komşularına iletir (zincirleme / mesh).
 * - Gönderilemeyen mesajları ve dosyaları saklar, yeni biri menzile girince tekrar dener.
 * - Aynı paket farklı kanallardan ya da yollardan iki kez gelirse tekrarı ayıklar.
 */
class MeshManager(
    context: Context,
    private val identity: Identity,
    private val dao: MeshDao,
    private val scope: CoroutineScope,
) : LinkListener {

    data class Peer(
        val id: String,
        val name: String,
        /** 1 = doğrudan bağlı, 2 = bir telefon aradan, ... */
        val hops: Int,
        val lastSeen: Long,
        val linkId: String?,
        /** Doğrudan bağlıysa hangi kanalla bağlı olduğu. */
        val via: String?,
    ) {
        val isDirect: Boolean get() = linkId != null
    }

    data class IncomingMessage(val peerId: String, val peerName: String, val text: String)

    private data class Link(val linkId: String, val userId: String, val transport: Transport)

    val appContext: Context = context.applicationContext

    val transports: List<Transport> = buildList {
        add(NearbyTransport(appContext, identity, this@MeshManager, scope))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(WifiAwareTransport(appContext, identity, this@MeshManager, scope))
        }
    }

    val media = MediaTransfer(this, dao, scope)
    val calls = CallManager(appContext, identity, dao, scope, this)

    private val _peers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    val peers: StateFlow<Map<String, Peer>> = _peers.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _incoming = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 32)
    val incoming: SharedFlow<IncomingMessage> = _incoming.asSharedFlow()

    /** Şu an ekranda açık olan sohbet (bildirim göstermemek için). */
    @Volatile
    var activeChatPeerId: String? = null

    private val links = ConcurrentHashMap<String, Link>()
    private val seen = object : LinkedHashMap<String, Boolean>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
            size > SEEN_CAPACITY
    }
    private var tickerJob: Job? = null

    val myName: String get() = identity.name.value ?: "?"

    // ---------------------------------------------------------------- başlat / durdur

    fun start() {
        if (_running.value) return
        _running.value = true
        _error.value = null
        transports.forEach { it.start() }

        tickerJob = scope.launch {
            var tick = 0
            while (isActive) {
                delay(TICK_MS)
                tick++
                transports.forEach { it.tick() }
                expirePeers()
                if (tick % ANNOUNCE_EVERY_TICKS == 0) announce()
            }
        }
    }

    /** Hata sonrası (ör. izin yeni verildiğinde) ağı baştan kurar. */
    fun restart() {
        stop()
        start()
    }

    fun stop() {
        if (!_running.value) return
        _running.value = false
        tickerJob?.cancel()
        tickerJob = null
        calls.hangup()
        transports.forEach { it.stop() }
        links.clear()
        _peers.value = emptyMap()
    }

    fun isDirect(userId: String): Boolean = _peers.value[userId]?.isDirect == true

    // ---------------------------------------------------------------- kanal olayları

    override fun isConnected(userId: String): Boolean = links.values.any { it.userId == userId }

    override fun onLinkUp(transport: Transport, linkId: String, userId: String, userName: String) {
        if (isConnected(userId)) {
            // Bu kişiye başka bir kanaldan zaten bağlıyız; fazlasını kapat.
            transport.disconnect(linkId)
            return
        }
        links[linkId] = Link(linkId, userId, transport)
        val now = System.currentTimeMillis()
        _peers.update { it + (userId to Peer(userId, userName, 1, now, linkId, transport.label)) }
        if (_error.value != null && transport.state.value == TransportState.ACTIVE) _error.value = null

        scope.launch {
            dao.upsertPeer(PeerEntity(userId, userName, now))
            announce()
            flushPending()
        }
    }

    override fun onLinkDown(linkId: String) {
        val link = links.remove(linkId) ?: return
        val replacement = links.values.firstOrNull { it.userId == link.userId }
        _peers.update { map ->
            val peer = map[link.userId]
            when {
                peer == null || peer.linkId != linkId -> map
                replacement != null -> map + (link.userId to peer.copy(
                    linkId = replacement.linkId,
                    via = replacement.transport.label,
                ))
                else -> map - link.userId
            }
        }
    }

    override fun onData(linkId: String, bytes: ByteArray) {
        val packet = Packet.parse(bytes) ?: return
        // Ses kareleri gecikmeye çok duyarlı: sıraya sokmadan hemen işle.
        if (packet.type == Packet.TYPE_AUDIO) {
            if (packet.to == identity.userId) calls.onAudio(packet)
            return
        }
        scope.launch { handle(linkId, packet) }
    }

    override fun onTransportError(transport: Transport, message: String) {
        // Sadece hiçbir kanal çalışmıyorsa ya da ana kanal hata verdiyse kullanıcıya göster.
        val anyActive = transports.any { it !== transport && it.state.value == TransportState.ACTIVE }
        if (!anyActive || transport is NearbyTransport) _error.value = message
    }

    // ---------------------------------------------------------------- mesaj gönderme

    fun sendText(peerId: String, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val message = MessageEntity(
            id = Packet.newId(),
            peerId = peerId,
            fromMe = true,
            text = clean,
            timestamp = System.currentTimeMillis(),
            status = MessageEntity.STATUS_PENDING,
            hops = 0,
            isRead = true,
        )
        scope.launch {
            dao.insertMessage(message)
            transmit(message)
        }
    }

    /** Uygulama klasörüne kopyalanmış bir dosyayı gönderir. */
    fun sendMedia(
        messageId: String,
        peerId: String,
        kind: String,
        file: File,
        name: String,
        mime: String,
        durationMs: Long = 0,
        caption: String = "",
    ) {
        val message = MessageEntity(
            id = messageId,
            peerId = peerId,
            fromMe = true,
            text = caption,
            timestamp = System.currentTimeMillis(),
            status = MessageEntity.STATUS_PENDING,
            hops = 0,
            isRead = true,
            kind = kind,
            fileName = name,
            mime = mime,
            size = file.length(),
            durationMs = durationMs,
            progress = 0,
            localPath = file.absolutePath,
        )
        scope.launch {
            dao.insertMessage(message)
            transmit(message)
        }
    }

    /** Mesajı ağa verir. Her denemede yeni paket kimliği alır, böylece yeniden gönderim zincirde engellenmez. */
    private fun transmit(message: MessageEntity) {
        if (message.isMedia) {
            media.send(message)
            return
        }
        val targets = route(message.peerId)
        if (targets.isEmpty()) return // kimse yok: mesaj sırada bekler
        val packet = newPacket(Packet.TYPE_MSG, to = message.peerId, ref = message.id, text = message.text)
            .copy(ts = message.timestamp)
        remember(packet.pid)
        sendTo(targets, packet.toBytes()) { ok ->
            if (ok) scope.launch { dao.updateStatus(message.id, MessageEntity.STATUS_SENT) }
        }
    }

    private suspend fun flushPending() {
        val since = System.currentTimeMillis() - RESEND_WINDOW_MS
        for (message in dao.unconfirmed(since)) {
            transmit(message)
            delay(60)
        }
    }

    // ---------------------------------------------------------------- yönlendirme

    /** Alıcı doğrudan bağlıysa sadece ona, değilse tüm komşulara (kişi başına tek kanal). */
    private fun route(userId: String?): List<Link> {
        val directId = userId?.let { _peers.value[it]?.linkId }
        val direct = directId?.let { links[it] }
        if (direct != null) return listOf(direct)
        return links.values.groupBy { it.userId }.values.map { it.first() }
    }

    private fun sendTo(targets: List<Link>, bytes: ByteArray, onResult: ((Boolean) -> Unit)? = null) {
        targets.groupBy { it.transport }.forEach { (transport, list) ->
            transport.send(list.map { it.linkId }, bytes, onResult)
        }
    }

    private fun sendPacket(packet: Packet, exceptLinkId: String? = null) {
        val exceptUser = exceptLinkId?.let { links[it]?.userId }
        val targets = route(packet.to).filter { it.linkId != exceptLinkId && it.userId != exceptUser }
        if (targets.isNotEmpty()) sendTo(targets, packet.toBytes())
    }

    /**
     * Paketi gönderir ve karşıya iletilene kadar bekler (dosya parçalarının akış kontrolü için).
     * Hiç bağlantı yoksa veya süre dolarsa false döner.
     */
    suspend fun sendAwait(packet: Packet, timeoutMs: Long = 15_000): Boolean {
        val targets = route(packet.to)
        if (targets.isEmpty()) return false
        remember(packet.pid)
        val bytes = packet.toBytes()
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val done = AtomicBoolean(false)
                sendTo(targets, bytes) { ok ->
                    if (done.compareAndSet(false, true) && cont.isActive) cont.resume(ok)
                }
            }
        } ?: false
    }

    /** Sadece doğrudan bağlı kişiye gönderir (arama sinyalleri ve ses için). */
    fun sendDirect(packet: Packet): Boolean {
        val link = packet.to?.let { _peers.value[it]?.linkId }?.let { links[it] } ?: return false
        link.transport.send(listOf(link.linkId), packet.toBytes(), null)
        return true
    }

    fun newPacket(
        type: String,
        to: String?,
        ref: String? = null,
        text: String? = null,
        ttl: Int = Packet.DEFAULT_TTL,
        media: MediaMeta? = null,
        idx: Int = -1,
        data: ByteArray? = null,
        pid: String = Packet.newId(),
    ) = Packet(
        type = type,
        pid = pid,
        ref = ref,
        from = identity.userId,
        fromName = myName,
        to = to,
        text = text,
        ts = System.currentTimeMillis(),
        ttl = ttl,
        hops = 0,
        media = media,
        idx = idx,
        data = data,
    )

    fun sendAck(to: String, messageId: String) {
        val ack = newPacket(Packet.TYPE_ACK, to = to, ref = messageId)
        remember(ack.pid)
        sendPacket(ack)
    }

    fun sendNack(to: String, messageId: String, missing: List<Int>) {
        val nack = newPacket(Packet.TYPE_NACK, to = to, ref = messageId, text = missing.joinToString(","))
        remember(nack.pid)
        sendPacket(nack)
    }

    fun emitIncoming(peerId: String, peerName: String, text: String) {
        _incoming.tryEmit(IncomingMessage(peerId, peerName, text))
    }

    private fun announce() {
        if (links.isEmpty()) return
        val packet = newPacket(Packet.TYPE_ANNOUNCE, to = null, ttl = Packet.ANNOUNCE_TTL)
        remember(packet.pid)
        sendPacket(packet)
    }

    // ---------------------------------------------------------------- gelen paketler

    private suspend fun handle(fromLinkId: String, packet: Packet) {
        if (packet.from == identity.userId) return
        if (!remember(packet.pid)) return // bu paketi daha önce işledik

        notePeer(packet.from, packet.fromName, packet.hops + 1)
        val forMe = packet.to == identity.userId

        when (packet.type) {
            Packet.TYPE_ANNOUNCE -> forward(packet, fromLinkId)

            Packet.TYPE_MSG -> when {
                !forMe -> forward(packet, fromLinkId)
                packet.media != null -> media.onOffer(packet)
                else -> receiveText(packet)
            }

            Packet.TYPE_CHUNK -> if (forMe) media.onChunk(packet) else forward(packet, fromLinkId)

            Packet.TYPE_NACK -> if (forMe) media.onNack(packet) else forward(packet, fromLinkId)

            Packet.TYPE_ACK ->
                if (forMe) {
                    packet.ref?.let {
                        dao.updateStatus(it, MessageEntity.STATUS_DELIVERED)
                        dao.updateProgress(it, 100)
                    }
                } else {
                    forward(packet, fromLinkId)
                }

            Packet.TYPE_CALL -> if (forMe) calls.onSignal(packet)
        }
    }

    private suspend fun receiveText(packet: Packet) {
        val messageId = packet.ref ?: return
        val inserted = dao.insertMessage(
            MessageEntity(
                id = messageId,
                peerId = packet.from,
                fromMe = false,
                text = packet.text.orEmpty(),
                timestamp = System.currentTimeMillis(),
                status = MessageEntity.STATUS_DELIVERED,
                hops = packet.hops,
                isRead = activeChatPeerId == packet.from,
            )
        ) != -1L

        // Mesaj daha önce gelmiş olsa bile tekrar onay gönder: gönderen onayı kaçırmış olabilir.
        sendAck(packet.from, messageId)

        if (inserted) {
            emitIncoming(packet.from, packet.fromName, previewText(MessageEntity.KIND_TEXT, packet.text.orEmpty(), null))
        }
    }

    private fun forward(packet: Packet, fromLinkId: String) {
        if (packet.ttl <= 0) return
        sendPacket(packet.forwarded(), exceptLinkId = fromLinkId)
    }

    private fun notePeer(userId: String, name: String, distance: Int) {
        val now = System.currentTimeMillis()
        var isNew = false
        _peers.update { map ->
            val existing = map[userId]
            isNew = existing == null || existing.name != name
            val updated = when {
                existing?.isDirect == true -> existing.copy(name = name, lastSeen = now)
                existing != null && existing.hops < distance && now - existing.lastSeen < PEER_TIMEOUT_MS ->
                    existing.copy(name = name, lastSeen = now)
                else -> Peer(userId, name, distance, now, null, null)
            }
            map + (userId to updated)
        }
        // Veritabanına her pakette değil, yeni biri ya da isim değişince yaz (dosya aktarımında yük olmasın).
        if (isNew) scope.launch { dao.upsertPeer(PeerEntity(userId, name, now)) }
    }

    private fun expirePeers() {
        val cutoff = System.currentTimeMillis() - PEER_TIMEOUT_MS
        _peers.update { map -> map.filterValues { it.isDirect || it.lastSeen >= cutoff } }
    }

    /** true dönerse paket ilk kez görülüyor. */
    private fun remember(pid: String): Boolean = synchronized(seen) {
        if (seen.containsKey(pid)) false else {
            seen[pid] = true
            true
        }
    }

    companion object {
        private const val TICK_MS = 10_000L
        private const val ANNOUNCE_EVERY_TICKS = 3
        private const val PEER_TIMEOUT_MS = 100_000L
        private const val RESEND_WINDOW_MS = 48L * 60 * 60 * 1000
        private const val SEEN_CAPACITY = 20_000
    }
}
