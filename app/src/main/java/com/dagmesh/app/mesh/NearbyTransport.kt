package com.dagmesh.app.mesh

import android.content.Context
import android.util.Log
import com.dagmesh.app.data.Identity
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Google Nearby Connections kanalı.
 * Bluetooth / BLE ile bulur, bağlandıktan sonra hız ve menzil için kendiliğinden
 * Wi‑Fi Direct veya hotspot'a yükseltir.
 */
class NearbyTransport(
    context: Context,
    private val identity: Identity,
    private val listener: LinkListener,
    private val scope: CoroutineScope,
) : Transport {

    override val key = "nb"
    override val label = "Bluetooth + Wi‑Fi Direct"

    private val _state = MutableStateFlow(TransportState.OFF)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val client: ConnectionsClient = Nearby.getConnectionsClient(context.applicationContext)

    @Volatile
    private var running = false
    private var autoRetried = false

    private val connected = ConcurrentHashMap<String, String>()      // endpointId -> userId
    private val initiatedNames = ConcurrentHashMap<String, String>() // endpointId -> uç adı
    private val discovered = ConcurrentHashMap<String, String>()     // endpointId -> userId
    private val requested: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val myEndpointName: String get() = "${identity.userId}|${identity.name.value ?: "?"}"

    override fun start() {
        if (running) return
        running = true
        _state.value = TransportState.STARTING
        var failed = false

        client.startAdvertising(
            myEndpointName, SERVICE_ID, lifecycleCallback,
            AdvertisingOptions.Builder().setStrategy(STRATEGY).build(),
        ).addOnFailureListener { failed = true; onError("Yayın başlatılamadı", it) }

        client.startDiscovery(
            SERVICE_ID, discoveryCallback,
            DiscoveryOptions.Builder().setStrategy(STRATEGY).build(),
        ).addOnSuccessListener {
            if (!failed && running) _state.value = TransportState.ACTIVE
        }.addOnFailureListener { failed = true; onError("Tarama başlatılamadı", it) }
    }

    override fun stop() {
        if (!running) return
        running = false
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        val links = connected.keys.toList()
        connected.clear()
        initiatedNames.clear()
        discovered.clear()
        requested.clear()
        links.forEach { listener.onLinkDown(linkId(it)) }
        _state.value = TransportState.OFF
    }

    override fun send(linkIds: List<String>, bytes: ByteArray, onResult: ((Boolean) -> Unit)?) {
        val endpoints = linkIds.map { it.removePrefix("$key:") }.filter { connected.containsKey(it) }
        if (endpoints.isEmpty()) {
            onResult?.invoke(false)
            return
        }
        client.sendPayload(endpoints, Payload.fromBytes(bytes))
            .addOnSuccessListener { onResult?.invoke(true) }
            .addOnFailureListener {
                Log.w(TAG, "Gönderilemedi", it)
                onResult?.invoke(false)
            }
    }

    override fun disconnect(linkId: String) {
        val endpointId = linkId.removePrefix("$key:")
        client.disconnectFromEndpoint(endpointId)
        if (connected.remove(endpointId) != null) listener.onLinkDown(linkId)
    }

    override fun tick() {
        for ((endpointId, userId) in discovered) requestIfNeeded(endpointId, userId)
    }

    private fun linkId(endpointId: String) = "$key:$endpointId"

    private fun requestIfNeeded(endpointId: String, userId: String) {
        if (!running) return
        if (connected.containsKey(endpointId) || listener.isConnected(userId)) return
        if (!requested.add(endpointId)) return
        client.requestConnection(myEndpointName, endpointId, lifecycleCallback)
            .addOnFailureListener {
                requested.remove(endpointId)
                Log.d(TAG, "Bağlantı isteği başarısız: $endpointId", it)
            }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != SERVICE_ID) return
            val (userId, _) = parseIdentity(info.endpointName) ?: return
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
            val (userId, userName) = endpointName?.let { parseIdentity(it) } ?: return
            connected[endpointId] = userId
            listener.onLinkUp(this@NearbyTransport, linkId(endpointId), userId, userName)
        }

        override fun onDisconnected(endpointId: String) {
            if (connected.remove(endpointId) != null) listener.onLinkDown(linkId(endpointId))
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            listener.onData(linkId(endpointId), bytes)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private fun onError(message: String, e: Exception) {
        Log.e(TAG, message, e)
        _state.value = TransportState.ERROR
        listener.onTransportError(this, "$message (${e.message ?: "bilinmeyen hata"})")
        // Geçici hatalarda bir kez kendiliğinden tekrar dene.
        if (!autoRetried) {
            autoRetried = true
            scope.launch {
                delay(4_000)
                if (running && _state.value == TransportState.ERROR) {
                    stop()
                    start()
                }
            }
        }
    }

    companion object {
        private const val TAG = "DagMesh/Nearby"
        const val SERVICE_ID = "com.dagmesh.app.MESH"
        private val STRATEGY: Strategy = Strategy.P2P_CLUSTER
        private const val REQUEST_BACKOFF_MS = 2_500L
    }
}
