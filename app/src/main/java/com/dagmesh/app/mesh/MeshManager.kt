package com.dagmesh.app.mesh

import android.content.Context
import android.util.Log
import com.dagmesh.app.data.Identity
import com.dagmesh.app.data.MeshDao
import com.dagmesh.app.data.MessageEntity
import com.dagmesh.app.data.PeerEntity
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
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
import java.util.concurrent.ConcurrentHashMap

/**
 * Ağın kalbi.
 *
 * - Google Nearby Connections ile aynı anda hem yayın yapar hem tarar (Bluetooth, BLE, Wi‑Fi Direct, hotspot).
 * - Bulduğu her Dağ Mesh telefonuna otomatik bağlanır.
 * - Kendine gelmeyen paketleri diğer komşularına iletir (zincirleme / mesh).
 * - Gönderilemeyen mesajları saklar, yeni biri menzile girince tekrar dener (sakla-ilet).
 */
class MeshManager(
    context: Context,
    private val identity: Identity,
    private val dao: MeshDao,
    private val scope: CoroutineScope,
) {
    data class Peer(
        val id: String,
        val name: String,
        /** 1 = doğrudan bağlı, 2 = bir telefon aradan, ... */
        val hops: Int,
        val lastSeen: Long,
        val endpointId: String?,
    ) {
        val isDirect: Boolean get() = endpointId != null
    }

    data class IncomingMessage(val peerId: String, val peerName: String, val text: String)

    private val client: ConnectionsClient = Nearby.getConnectionsClient(context.applicationContext)

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

    private val endpointToUser = ConcurrentHashMap<String, String>()
    private val initiatedNames = ConcurrentHashMap<String, String>()
    private val discovered = ConcurrentHashMap<String, String>()
    private val requested: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val seen = object : LinkedHashMap<String, Boolean>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
            size > SEEN_CAPACITY
    }
    private var tickerJob: Job? = null

    private val myName: String get() = identity.name.value ?: "?"
    private val myEndpointName: String get() = "${identity.userId}|$myName"

    // ---------------------------------------------------------------- başlat / durdur

    fun start() {
        if (_running.value) return
        _running.value = true
        _error.value = null

        client.startAdvertising(
            myEndpointName,
            SERVICE_ID,
            lifecycleCallback,
            AdvertisingOptions.Builder().setStrategy(STRATEGY).build(),
        ).addOnFailureListener { onError("Yayın başlatılamadı", it) }

        client.startDiscovery(
            SERVICE_ID,
            discoveryCallback,
            DiscoveryOptions.Builder().setStrategy(STRATEGY).build(),
        ).addOnFailureListener { onError("Tarama başlatılamadı", it) }

        tickerJob = scope.launch {
            var tick = 0
            while (isActive) {
                delay(TICK_MS)
                tick++
                retryDiscovered()
                expirePeers()
                if (tick % ANNOUNCE_EVERY_TICKS == 0) announce()
            }
        }
    }

    fun stop() {
        if (!_running.value) return
        _running.value = false
        tickerJob?.cancel()
        tickerJob = null
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        endpointToUser.clear()
        initiatedNames.clear()
        discovered.clear()
        requested.clear()
        _peers.value = emptyMap()
    }

    // ---------------------------------------------------------------- mesaj gönderme

    fun sendMessage(peerId: String, text: String) {
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

    /** Mesajı ağa verir. Her denemede yeni paket kimliği alır, böylece yeniden gönderim zincirde engellenmez. */
    private fun transmit(message: MessageEntity) {
        val targets = route(message.peerId)
        if (targets.isEmpty()) return // kimse yok: mesaj sırada bekler
        val packet = Packet(
            type = Packet.TYPE_MSG,
            pid = Packet.newId(),
            ref = message.id,
            from = identity.userId,
            fromName = myName,
            to = message.peerId,
            text = message.text,
            ts = message.timestamp,
            ttl = Packet.DEFAULT_TTL,
            hops = 0,
        )
        remember(packet.pid)
        client.sendPayload(targets, Payload.fromBytes(packet.toBytes()))
            .addOnSuccessListener {
                scope.launch { dao.updateStatus(message.id, MessageEntity.STATUS_SENT) }
            }
            .addOnFailureListener { Log.w(TAG, "Mesaj gönderilemedi", it) }
    }

    private suspend fun flushPending() {
        val since = System.currentTimeMillis() - RESEND_WINDOW_MS
        for (message in dao.unconfirmed(since)) {
            transmit(message)
            delay(60)
        }
    }

    /** Alıcı doğrudan bağlıysa sadece ona, değilse tüm komşulara gönder. */
    private fun route(userId: String?): List<String> {
        val direct = userId?.let { _peers.value[it]?.endpointId }
        return if (direct != null) listOf(direct) else endpointToUser.keys.toList()
    }

    private fun sendPacket(packet: Packet, except: String? = null) {
        val targets = route(packet.to).filter { it != except }
        if (targets.isEmpty()) return
        client.sendPayload(targets, Payload.fromBytes(packet.toBytes()))
            .addOnFailureListener { Log.w(TAG, "Paket gönderilemedi", it) }
    }

    private fun announce() {
        if (endpointToUser.isEmpty()) return
        val packet = Packet(
            type = Packet.TYPE_ANNOUNCE,
            pid = Packet.newId(),
            ref = null,
            from = identity.userId,
            fromName = myName,
            to = null,
            text = null,
            ts = System.currentTimeMillis(),
            ttl = Packet.ANNOUNCE_TTL,
            hops = 0,
        )
        remember(packet.pid)
        sendPacket(packet)
    }

    // ---------------------------------------------------------------- gelen paketler

    private suspend fun handle(fromEndpoint: String, packet: Packet) {
        if (packet.from == identity.userId) return
        if (!remember(packet.pid)) return // bu paketi daha önce işledik

        notePeer(packet.from, packet.fromName, packet.hops + 1)

        when (packet.type) {
            Packet.TYPE_ANNOUNCE -> forward(packet, fromEndpoint)

            Packet.TYPE_MSG ->
                if (packet.to == identity.userId) receiveMessage(packet)
                else forward(packet, fromEndpoint)

            Packet.TYPE_ACK ->
                if (packet.to == identity.userId) {
                    packet.ref?.let { dao.updateStatus(it, MessageEntity.STATUS_DELIVERED) }
                } else {
                    forward(packet, fromEndpoint)
                }
        }
    }

    private suspend fun receiveMessage(packet: Packet) {
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
        val ack = Packet(
            type = Packet.TYPE_ACK,
            pid = Packet.newId(),
            ref = messageId,
            from = identity.userId,
            fromName = myName,
            to = packet.from,
            text = null,
            ts = System.currentTimeMillis(),
            ttl = Packet.DEFAULT_TTL,
            hops = 0,
        )
        remember(ack.pid)
        sendPacket(ack)

        if (inserted) {
            _incoming.tryEmit(IncomingMessage(packet.from, packet.fromName, packet.text.orEmpty()))
        }
    }

    private fun forward(packet: Packet, fromEndpoint: String) {
        if (packet.ttl <= 0) return
        sendPacket(packet.forwarded(), except = fromEndpoint)
    }

    private fun notePeer(userId: String, name: String, distance: Int) {
        val now = System.currentTimeMillis()
        _peers.update { map ->
            val existing = map[userId]
            val updated = when {
                existing?.isDirect == true -> existing.copy(name = name, lastSeen = now)
                existing != null && existing.hops < distance && now - existing.lastSeen < PEER_TIMEOUT_MS ->
                    existing.copy(name = name, lastSeen = now)
                else -> Peer(userId, name, distance, now, null)
            }
            map + (userId to updated)
        }
        scope.launch { dao.upsertPeer(PeerEntity(userId, name, now)) }
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

    // ---------------------------------------------------------------- bağlantı yönetimi

    private fun requestIfNeeded(endpointId: String, userId: String) {
        if (!_running.value) return
        if (endpointToUser.containsKey(endpointId) || endpointToUser.containsValue(userId)) return
        if (!requested.add(endpointId)) return
        client.requestConnection(myEndpointName, endpointId, lifecycleCallback)
            .addOnFailureListener {
                requested.remove(endpointId)
                Log.d(TAG, "Bağlantı isteği başarısız: $endpointId", it)
            }
    }

    private fun retryDiscovered() {
        for ((endpointId, userId) in discovered) requestIfNeeded(endpointId, userId)
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != SERVICE_ID) return
            val (userId, _) = parseEndpointName(info.endpointName) ?: return
            if (userId == identity.userId) return
            discovered[endpointId] = userId
            scope.launch {
                // İki telefon aynı anda istek atmasın: kimliği büyük olan biraz bekler.
                if (identity.userId > userId) delay(REQUEST_BACKOFF_MS)
                requestIfNeeded(endpointId, userId)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            discovered.remove(endpointId)
            requested.remove(endpointId)
        }
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            initiatedNames[endpointId] = info.endpointName
            client.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { Log.d(TAG, "Bağlantı kabul edilemedi", it) }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            requested.remove(endpointId)
            val endpointName = initiatedNames.remove(endpointId)
            if (!resolution.status.isSuccess) return
            val (userId, userName) = endpointName?.let { parseEndpointName(it) } ?: return

            if (endpointToUser.containsValue(userId)) {
                // Aynı kişiye ikinci bir bağlantı oluştu, fazlasını kapat.
                client.disconnectFromEndpoint(endpointId)
                return
            }
            endpointToUser[endpointId] = userId
            val now = System.currentTimeMillis()
            _peers.update { it + (userId to Peer(userId, userName, 1, now, endpointId)) }

            scope.launch {
                dao.upsertPeer(PeerEntity(userId, userName, now))
                announce()
                flushPending()
            }
        }

        override fun onDisconnected(endpointId: String) {
            val userId = endpointToUser.remove(endpointId) ?: return
            _peers.update { map ->
                if (map[userId]?.endpointId == endpointId) map - userId else map
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            val packet = Packet.parse(bytes) ?: return
            scope.launch { handle(endpointId, packet) }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private fun onError(message: String, e: Exception) {
        Log.e(TAG, message, e)
        _error.value = "$message (${e.message ?: "bilinmeyen hata"})"
    }

    private fun parseEndpointName(raw: String): Pair<String, String>? {
        val i = raw.indexOf('|')
        if (i <= 0) return null
        return raw.substring(0, i) to raw.substring(i + 1).ifBlank { "?" }
    }

    companion object {
        private const val TAG = "DagMesh"
        const val SERVICE_ID = "com.dagmesh.app.MESH"
        private val STRATEGY: Strategy = Strategy.P2P_CLUSTER

        private const val TICK_MS = 10_000L
        private const val ANNOUNCE_EVERY_TICKS = 3
        private const val PEER_TIMEOUT_MS = 100_000L
        private const val REQUEST_BACKOFF_MS = 2_500L
        private const val RESEND_WINDOW_MS = 48L * 60 * 60 * 1000
        private const val SEEN_CAPACITY = 4000
    }
}
