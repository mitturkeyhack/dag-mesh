package com.dagmesh.app.mesh

import android.content.Context
import android.os.Build
import com.dagmesh.app.ai.AiBridge
import com.dagmesh.app.call.CallManager
import com.dagmesh.app.crypto.MeshCrypto
import com.dagmesh.app.family.FamilyManager
import com.dagmesh.app.net.NetBridge
import com.dagmesh.app.sos.StatusBoard
import com.dagmesh.app.media.LocationHelper
import com.dagmesh.app.sos.SosManager
import com.dagmesh.app.data.Identity
import com.dagmesh.app.data.MeshDao
import com.dagmesh.app.data.MessageEntity
import com.dagmesh.app.data.PeerEntity
import com.dagmesh.app.data.Settings
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
    settings: Settings,
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

    /**
     * Doğrudan bağlantı. Karşı tarafın söylediği kimlik, imzalı duyurusu doğrulanana kadar güvenilmez:
     * doğrulanmamış bağlantıya mesaj yönlendirilmez, 30 sn içinde doğrulanmazsa kapatılır.
     */
    private class Link(val linkId: String, val userId: String, val userName: String, val transport: Transport) {
        val since: Long = System.currentTimeMillis()
        @Volatile var verified = false
        @Volatile var sawOldProtocol = false
    }

    val appContext: Context = context.applicationContext

    val transports: List<Transport> = buildList {
        add(NearbyTransport(appContext, identity, this@MeshManager, scope))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(WifiAwareTransport(appContext, identity, this@MeshManager, scope))
        }
    }

    val media = MediaTransfer(this, dao, scope)
    val calls = CallManager(appContext, identity, dao, scope, this)
    val power = PowerController(appContext, settings, this, scope)
    val sos = SosManager(appContext, scope, this)

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

    /** Kim bize yazıyor: kişi → "yazıyor" gösteriminin bitiş zamanı. */
    private val _typing = MutableStateFlow<Map<String, Long>>(emptyMap())
    val typing: StateFlow<Map<String, Long>> = _typing.asStateFlow()
    private val lastTypingSent = ConcurrentHashMap<String, Long>()

    private val links = ConcurrentHashMap<String, Link>()
    private val seen = object : LinkedHashMap<String, Boolean>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
            size > SEEN_CAPACITY
    }
    private var tickerJob: Job? = null

    /** Uçtan uca şifreleme ve imza. */
    val crypto = MeshCrypto(appContext, identity.vault)

    /** Eski (şifresiz) sürümle gelen telefonlar: kimlik → engel bitiş zamanı. */
    private val blocked = ConcurrentHashMap<String, Long>()
    private val _outdatedNearby = MutableStateFlow(0)
    /** Yakında eski sürüm kullandığı için bağlanılamayan telefon sayısı (güncellemeleri gerekir). */
    val outdatedNearby: StateFlow<Int> = _outdatedNearby.asStateFlow()

    /** SOS sınırı: kişi → son yeni SOS'ların zamanları. */
    private val sosStarts = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val knownSos = ConcurrentHashMap.newKeySet<String>()

    /** Yapay zekâ köprüsü (EVREN). Kişiler listesi hazır olduktan sonra kurulmalı. */
    val ai = AiBridge(appContext, dao, scope, this)

    /** İnternet köprüsü (aile sunucusuna giden istekleri taşır). Yapay zekâ köprüsünden sonra kurulmalı. */
    val net = NetBridge(scope, this)

    /** Ailem: aile kodu ile kurulan şifreli grup. */
    val family = FamilyManager(appContext, scope, this)

    /** Köy yoklaması: iyiyim / yaralıyım / yardım lazım. */
    val village = StatusBoard(appContext, scope, this)

    val identityVault get() = identity.vault

    val myName: String get() = identity.name.value ?: "?"
    val myId: String get() = identity.userId

    // ---------------------------------------------------------------- başlat / durdur

    fun start() {
        if (_running.value) return
        _running.value = true
        _error.value = null
        transports.forEach { it.start() }
        power.start()

        tickerJob = scope.launch {
            var tick = 0
            while (isActive) {
                delay(TICK_MS)
                tick++
                transports.forEach { it.tick() }
                expirePeers()
                checkLinks()
                if (tick % ANNOUNCE_EVERY_TICKS == 0) {
                    announce()
                    ai.tick()
                    net.tick()
                    village.tick()
                }
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
        power.stop()
        calls.hangup()
        transports.forEach { it.stop() }
        links.clear()
        _peers.value = emptyMap()
        _outdatedNearby.value = 0
    }

    fun isDirect(userId: String): Boolean = _peers.value[userId]?.isDirect == true

    // ---------------------------------------------------------------- kanal olayları

    /** Doğrulanmış bağlantı varsa ya da telefon eski sürüm diye engelliyse yeniden bağlanmaya gerek yok. */
    override fun isConnected(userId: String): Boolean =
        isBlocked(userId) || links.values.any { it.userId == userId && it.verified }

    override fun onLinkUp(transport: Transport, linkId: String, userId: String, userName: String) {
        if (isConnected(userId)) {
            // Bu kişiye başka bir kanaldan zaten (doğrulanmış) bağlıyız ya da eski sürüm: kapat.
            transport.disconnect(linkId)
            return
        }
        // Henüz doğrulanmadı: kişi listesine ve yönlendirmeye ancak imzalı duyurusu gelince girer.
        links[linkId] = Link(linkId, userId, userName, transport)
        if (_error.value != null && transport.state.value == TransportState.ACTIVE) _error.value = null
        scope.launch { announce() }
    }

    /** Bağlantının sahibi imzalı duyurusuyla kimliğini kanıtladı. */
    private fun onLinkVerified(link: Link, name: String) {
        if (link.verified) return
        link.verified = true
        // Aynı kişiye doğrulanmamış başka bağlantılar varsa kapat.
        links.values.filter { it !== link && it.userId == link.userId && !it.verified }.forEach {
            links.remove(it.linkId)
            it.transport.disconnect(it.linkId)
        }
        val now = System.currentTimeMillis()
        _peers.update { it + (link.userId to Peer(link.userId, name, 1, now, link.linkId, link.transport.label)) }
        scope.launch {
            dao.upsertPeer(PeerEntity(link.userId, name, now))
            flushPending()
        }
        village.onNewPeer()
    }

    /** 30 sn'de kimliğini kanıtlamayan bağlantıyı kapatır; eski sürümse bir süre engeller. */
    private fun checkLinks() {
        val now = System.currentTimeMillis()
        links.values.filter { !it.verified && now - it.since > VERIFY_TIMEOUT_MS }.forEach { link ->
            links.remove(link.linkId)
            link.transport.disconnect(link.linkId)
            if (link.sawOldProtocol) blocked[link.userId] = now + OUTDATED_BLOCK_MS
        }
        blocked.entries.removeIf { it.value < now }
        _outdatedNearby.value = blocked.size
        // Henüz doğrulanmamış bağlantı varsa duyuruyu tekrarla: ilk duyuru zamanlama yüzünden kaçmış olabilir.
        if (links.values.any { !it.verified }) announce()
    }

    private fun isBlocked(userId: String): Boolean = (blocked[userId] ?: 0L) > System.currentTimeMillis()

    override fun onLinkDown(linkId: String) {
        val link = links.remove(linkId) ?: return
        val replacement = links.values.firstOrNull { it.userId == link.userId && it.verified }
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
        if (packet.version < Packet.PROTOCOL_VERSION) {
            // Eski, şifresiz sürüm: işlenmez ve ilerletilmez (ilerletilse şifreli alanları bozardı).
            links[linkId]?.sawOldProtocol = true
            return
        }
        // Ses kareleri gecikmeye çok duyarlı: sıraya sokmadan hemen işle.
        if (packet.type == Packet.TYPE_AUDIO) {
            if (packet.to == identity.userId && links[linkId]?.verified == true) {
                crypto.open(packet)?.let { calls.onAudio(it) }
            }
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

    fun sendText(
        peerId: String,
        text: String,
        replyTo: MessageEntity? = null,
        kind: String = MessageEntity.KIND_TEXT,
    ) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val message = MessageEntity(
            kind = kind,
            id = Packet.newId(),
            peerId = peerId,
            fromMe = true,
            text = clean,
            timestamp = System.currentTimeMillis(),
            status = MessageEntity.STATUS_PENDING,
            hops = 0,
            isRead = true,
            replyToId = replyTo?.id,
            replyText = replyTo?.let { previewText(it.kind, it.text, it.fileName).take(REPLY_PREVIEW_MAX) },
            replyAuthor = replyTo?.let { if (it.fromMe) identity.userId else it.peerId },
        )
        scope.launch {
            dao.insertMessage(message)
            transmit(message)
        }
    }

    /** Konumu mesaj olarak gönderir ("enlem,boylam,doğruluk"). */
    fun sendLocation(peerId: String, location: android.location.Location) {
        sendText(peerId, LocationHelper.encode(location), kind = MessageEntity.KIND_LOCATION)
    }

    /**
     * Mesaja emoji tepkisi verir. Aynı emojiye tekrar basılırsa tepki kaldırılır.
     * Tepki karşı tarafa da gider (menzil dışındaysa ulaşmayabilir).
     */
    fun react(message: MessageEntity, emoji: String) {
        val newValue = if (message.myReaction == emoji) null else emoji
        scope.launch {
            dao.setMyReaction(message.id, newValue)
            if (message.peerId == AiBridge.PEER_ID) return@launch // yapay zekâya tepki gitmez
            val packet = newPacket(Packet.TYPE_REACT, to = message.peerId, ref = message.id, text = newValue.orEmpty())
            remember(packet.pid)
            sendPacket(packet)
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
        if (message.peerId == AiBridge.PEER_ID) {
            ai.transmit(message)
            return
        }
        if (message.isMedia) {
            media.send(message)
            return
        }
        val targets = route(message.peerId)
        if (targets.isEmpty()) return // kimse yok: mesaj sırada bekler
        val packet = newPacket(Packet.TYPE_MSG, to = message.peerId, ref = message.id, text = message.text)
            .copy(
                ts = message.timestamp,
                kind = message.kind.takeIf { it != MessageEntity.KIND_TEXT },
                replyTo = message.replyToId,
                replyText = message.replyText,
                replyAuthor = message.replyAuthor,
            )
        // Alıcının anahtarı henüz bilinmiyorsa mesaj sırada bekler; anahtar öğrenilince gider.
        val bytes = encode(packet) ?: return
        remember(packet.pid)
        sendTo(targets, bytes) { ok ->
            if (ok) scope.launch { dao.updateStatus(message.id, MessageEntity.STATUS_SENT) }
        }
    }

    private suspend fun flushPending(onlyPeer: String? = null) {
        val since = System.currentTimeMillis() - RESEND_WINDOW_MS
        for (message in dao.unconfirmed(since)) {
            if (onlyPeer != null && message.peerId != onlyPeer) continue
            transmit(message)
            delay(60)
        }
    }

    /**
     * Paketi gönderilecek baytlara çevirir. Bu telefonun oluşturduğu paket burada mühürlenir
     * (kişiye özelse şifrelenir, herkese gidiyorsa imzalanır). Başkasından gelip ilerletilen paket
     * olduğu gibi gider. Alıcının anahtarı bilinmiyorsa null.
     */
    private fun encode(packet: Packet): ByteArray? =
        if (packet.from == identity.userId && !packet.isSealed) crypto.seal(packet)?.toBytes() else packet.toBytes()

    // ---------------------------------------------------------------- yönlendirme

    /**
     * Alıcı doğrudan bağlıysa sadece ona, değilse tüm komşulara (kişi başına tek kanal).
     * Sadece kimliğini kanıtlamış bağlantılar kullanılır.
     */
    private fun route(userId: String?): List<Link> {
        val directId = userId?.let { _peers.value[it]?.linkId }
        val direct = directId?.let { links[it] }?.takeIf { it.verified }
        if (direct != null) return listOf(direct)
        return links.values.filter { it.verified }.groupBy { it.userId }.values.map { it.first() }
    }

    private fun sendTo(targets: List<Link>, bytes: ByteArray, onResult: ((Boolean) -> Unit)? = null) {
        targets.groupBy { it.transport }.forEach { (transport, list) ->
            transport.send(list.map { it.linkId }, bytes, onResult)
        }
    }

    private fun sendPacket(packet: Packet, exceptLinkId: String? = null) {
        val exceptUser = exceptLinkId?.let { links[it]?.userId }
        val targets = route(packet.to).filter { it.linkId != exceptLinkId && it.userId != exceptUser }
        if (targets.isEmpty()) return
        val bytes = encode(packet) ?: return
        sendTo(targets, bytes)
    }

    /**
     * Paketi gönderir ve karşıya iletilene kadar bekler (dosya parçalarının akış kontrolü için).
     * Hiç bağlantı yoksa veya süre dolarsa false döner.
     */
    suspend fun sendAwait(packet: Packet, timeoutMs: Long = 15_000): Boolean {
        val targets = route(packet.to)
        if (targets.isEmpty()) return false
        val bytes = encode(packet) ?: return false
        remember(packet.pid)
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
    /** Paketi (zincirleme) gönderir; gönderecek kimse yoksa false döner. */
    fun sendTracked(packet: Packet, onResult: (Boolean) -> Unit): Boolean {
        val targets = route(packet.to)
        if (targets.isEmpty()) return false
        val bytes = encode(packet) ?: return false
        remember(packet.pid)
        sendTo(targets, bytes, onResult)
        return true
    }

    /** Paketi (zincirleme) gönderir, sonucu beklemez. */
    fun send(packet: Packet) {
        remember(packet.pid)
        sendPacket(packet)
    }

    fun sendDirect(packet: Packet): Boolean {
        val link = packet.to?.let { _peers.value[it]?.linkId }?.let { links[it] }?.takeIf { it.verified } ?: return false
        val bytes = encode(packet) ?: return false
        link.transport.send(listOf(link.linkId), bytes, null)
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

    /**
     * Sohbet ekranı açıkken çağrılır: okunmamış mesajları okundu yapar ve gönderene
     * "okundu" bilgisi yollar (mavi çift tik).
     */
    suspend fun markConversationRead(peerId: String) {
        val ids = dao.unreadIncomingIds(peerId)
        dao.markRead(peerId)
        if (ids.isEmpty() || peerId == AiBridge.PEER_ID) return
        ids.chunked(40).forEach { batch ->
            val packet = newPacket(Packet.TYPE_READ, to = peerId, text = batch.joinToString(","))
            remember(packet.pid)
            sendPacket(packet)
        }
    }

    /** Yazarken çağrılır; karşı tarafa en fazla 3 sn'de bir "yazıyor" sinyali gider. */
    fun notifyTyping(peerId: String) {
        if (peerId == AiBridge.PEER_ID) return
        val now = System.currentTimeMillis()
        if (now - (lastTypingSent[peerId] ?: 0L) < TYPING_SEND_INTERVAL_MS) return
        lastTypingSent[peerId] = now
        val packet = newPacket(Packet.TYPE_TYPING, to = peerId, ttl = 2)
        remember(packet.pid)
        sendPacket(packet)
    }

    private fun clearTyping(peerId: String) {
        _typing.update { it - peerId }
    }

    /** Mesajı sadece bu telefondan siler (dosyası varsa onu da). */
    fun deleteLocal(message: MessageEntity) {
        scope.launch {
            dao.delete(message.id)
            message.localPath?.let { path ->
                val file = File(path)
                if (file.parentFile?.name == "media") file.delete()
            }
        }
    }

    // ---------------------------------------------------------------- herkese / tek kişiye yayın (SOS)

    /** Menzildeki herkese (zincirleme) bir paket yayınlar. */
    fun broadcast(type: String, ref: String, text: String) {
        val packet = newPacket(type, to = null, ref = ref, text = text)
        remember(packet.pid)
        sendPacket(packet)
    }

    /** Belirli bir kişiye (zincirleme) bir paket gönderir. */
    fun sendTo(type: String, to: String, ref: String, text: String) {
        val packet = newPacket(type, to = to, ref = ref, text = text)
        remember(packet.pid)
        sendPacket(packet)
    }

    fun emitIncoming(peerId: String, peerName: String, text: String) {
        _incoming.tryEmit(IncomingMessage(peerId, peerName, text))
    }

    /** Köprü durumu değişince komşulara hemen haber ver. */
    fun announceNow() {
        if (_running.value) scope.launch { announce() }
    }

    private fun announce() {
        if (links.isEmpty()) return
        // İnterneti ve yapay zekâ anahtarı olan telefon kendini "köprü" olarak duyurur.
        // Köprü işaretleri: "gw" = yapay zekâ köprüsü, "net" = internet (aile sunucusu) köprüsü.
        val mark = listOfNotNull(
            AiBridge.GATEWAY_MARK.takeIf { ai.isGatewayNow() },
            NetBridge.GATEWAY_MARK.takeIf { net.isGatewayNow() },
        ).joinToString(",").ifEmpty { null }
        val packet = newPacket(Packet.TYPE_ANNOUNCE, to = null, ttl = Packet.ANNOUNCE_TTL, text = mark)
        val bytes = encode(packet) ?: return
        remember(packet.pid)
        // Duyuru, kimliğini henüz kanıtlamamış bağlantılara da gider: karşı taraf bizi ancak bununla doğrular.
        sendTo(links.values.toList(), bytes)
    }

    // ---------------------------------------------------------------- gelen paketler

    private suspend fun handle(fromLinkId: String, raw: Packet) {
        if (raw.from == identity.userId) return
        val link = links[fromLinkId] ?: return
        // Kimliğini henüz kanıtlamamış bağlantıdan sadece kendi duyurusu kabul edilir.
        if (!link.verified && !(raw.type == Packet.TYPE_ANNOUNCE && raw.hops == 0)) return
        if (!remember(raw.pid)) return // bu paketi daha önce işledik

        val forMe = raw.to == identity.userId
        val hadKey = crypto.hasKey(raw.from)
        val packet: Packet = when {
            // Herkese giden: imzası tutmazsa atılır ve ilerletilmez (sahte SOS / işaret yayılmaz).
            raw.to == null -> if (crypto.verifyBroadcast(raw)) raw else return
            // Bana gelen: açılamazsa (sahte ya da bozuk) atılır.
            forMe -> crypto.open(raw) ?: return
            // Başkasına giden şifreli paket: okuyamayız, sadece taşırız.
            else -> {
                if (raw.type != Packet.TYPE_CALL) forward(raw, fromLinkId)
                return
            }
        }
        // Bu kişinin anahtarını ilk kez öğrendik: ona bekleyen mesajlarımızı şimdi gönderebiliriz.
        if (!hadKey && crypto.hasKey(packet.from)) scope.launch { flushPending(onlyPeer = packet.from) }

        if (packet.type == Packet.TYPE_ANNOUNCE && packet.hops == 0) {
            if (link.userId == packet.from) {
                onLinkVerified(link, packet.fromName)
            } else {
                // Bağlantıda başka kimlik söyleyip başkasının duyurusunu getiren: kapat.
                links.remove(fromLinkId)
                link.transport.disconnect(fromLinkId)
                return
            }
        }
        notePeer(packet.from, packet.fromName, packet.hops + 1)

        when (packet.type) {
            Packet.TYPE_ANNOUNCE -> {
                ai.onAnnounce(packet)
                net.onAnnounce(packet.from, packet.text?.split(',')?.contains(NetBridge.GATEWAY_MARK) == true)
                forward(packet, fromLinkId)
            }

            // İnternet köprüsü ve aile mesajları
            Packet.TYPE_NET -> if (forMe) net.onRequest(packet) else forward(packet, fromLinkId)
            Packet.TYPE_NET_R -> if (forMe) net.onResponse(packet) else forward(packet, fromLinkId)
            Packet.TYPE_FAM -> if (forMe) family.onMeshCopy(packet) else forward(packet, fromLinkId)

            // Köy yoklaması: herkes kaydeder, herkese iletilir.
            Packet.TYPE_STATUS -> {
                village.onStatus(packet)
                forward(packet, fromLinkId)
            }

            // Yapay zekâ köprüsü: soru köprüye, cevap soran telefona gider.
            Packet.TYPE_AI_Q -> if (forMe) ai.onQuestion(packet) else forward(packet, fromLinkId)
            Packet.TYPE_AI_A -> if (forMe) ai.onAnswer(packet) else forward(packet, fromLinkId)

            // Acil durum: herkes işler ve herkese iletir.
            Packet.TYPE_SOS -> {
                if (!allowSos(packet)) return
                sos.onSos(packet)
                forward(packet, fromLinkId)
            }

            // Harita işaretleri: herkes kaydeder, herkese iletilir.
            Packet.TYPE_PIN -> {
                com.dagmesh.app.map.PinStore.onPacket(packet)
                if (!forMe) forward(packet, fromLinkId)
            }

            Packet.TYPE_SOS_ACK -> if (forMe) sos.onAck(packet) else forward(packet, fromLinkId)

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

            Packet.TYPE_READ ->
                if (forMe) {
                    packet.text?.split(',')?.forEach { id -> if (id.isNotBlank()) dao.markReadByPeer(id.trim()) }
                } else {
                    forward(packet, fromLinkId)
                }

            Packet.TYPE_REACT ->
                if (forMe) {
                    packet.ref?.let { dao.setPeerReaction(it, packet.from, packet.text?.takeIf { e -> e.isNotBlank() }) }
                    if (!packet.text.isNullOrBlank()) {
                        emitIncoming(packet.from, packet.fromName, "${packet.text} tepkisi verdi")
                    }
                } else {
                    forward(packet, fromLinkId)
                }

            Packet.TYPE_TYPING ->
                if (forMe) {
                    _typing.update { it + (packet.from to System.currentTimeMillis() + TYPING_SHOW_MS) }
                } else {
                    forward(packet, fromLinkId)
                }
        }
    }

    /**
     * Kişi başına 10 dakikada en fazla 3 yeni SOS. Aynı SOS'un konum güncellemeleri ve iptali sınırsız.
     * Sahte SOS'u tamamen engellemez ama birinin ağı alarmla boğmasını önler.
     */
    private fun allowSos(packet: Packet): Boolean {
        val ref = packet.ref ?: return false
        val key = packet.from + "|" + ref
        if (key in knownSos) return true
        val now = System.currentTimeMillis()
        val starts = sosStarts.getOrPut(packet.from) { ArrayDeque() }
        synchronized(starts) {
            while (starts.isNotEmpty() && now - starts.first() > SOS_WINDOW_MS) starts.removeFirst()
            if (starts.size >= SOS_MAX_PER_WINDOW) return false
            starts.addLast(now)
        }
        knownSos += key
        return true
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
                isRead = false,
                kind = packet.kind ?: MessageEntity.KIND_TEXT,
                replyToId = packet.replyTo,
                replyText = packet.replyText,
                replyAuthor = packet.replyAuthor,
            )
        ) != -1L
        clearTyping(packet.from)

        // Mesaj daha önce gelmiş olsa bile tekrar onay gönder: gönderen onayı kaçırmış olabilir.
        sendAck(packet.from, messageId)

        if (inserted) {
            emitIncoming(
                packet.from,
                packet.fromName,
                previewText(packet.kind ?: MessageEntity.KIND_TEXT, packet.text.orEmpty(), null),
            )
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
        private const val VERIFY_TIMEOUT_MS = 45_000L
        private const val OUTDATED_BLOCK_MS = 10L * 60 * 1000
        private const val SOS_WINDOW_MS = 10L * 60 * 1000
        private const val SOS_MAX_PER_WINDOW = 3
        private const val ANNOUNCE_EVERY_TICKS = 3
        private const val PEER_TIMEOUT_MS = 100_000L
        private const val RESEND_WINDOW_MS = 48L * 60 * 60 * 1000
        private const val SEEN_CAPACITY = 20_000
        private const val TYPING_SHOW_MS = 5_000L
        private const val REPLY_PREVIEW_MAX = 120
        private const val TYPING_SEND_INTERVAL_MS = 3_000L
    }
}
