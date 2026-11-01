package com.dagmesh.app.mesh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.dagmesh.app.data.Identity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wi‑Fi Aware (NAN) kanalı.
 *
 * Bluetooth'a ihtiyaç duymadan, doğrudan Wi‑Fi radyosuyla yakındaki telefonları bulur
 * ve aralarında şifreli bir Wi‑Fi bağlantısı kurar. Menzili Bluetooth'tan uzundur ve
 * kullanıcıya onay penceresi göstermez.
 *
 * Bağlantı akışı:
 *  1. Her telefon hem yayın (publish) hem tarama (subscribe) yapar.
 *  2. Kimliği küçük olan taraf karşıya "HELLO" mesajı gönderir.
 *  3. Karşı taraf bir soket açar, Wi‑Fi Aware ağını hazırlar ve "READY" der.
 *  4. Başlatan taraf ağa katılır, sokete bağlanır; iki taraf kimlik alışverişi yapar.
 *  5. Bundan sonra paketler soket üzerinden uzunluk + bayt çerçeveleriyle gider.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class WifiAwareTransport(
    private val context: Context,
    private val identity: Identity,
    private val listener: LinkListener,
    private val scope: CoroutineScope,
) : Transport {

    override val key = "wa"
    override val label = "Wi‑Fi Aware"

    private val _state = MutableStateFlow(TransportState.OFF)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private val aware: WifiAwareManager? = context.getSystemService(WifiAwareManager::class.java)
    private val connectivity: ConnectivityManager = context.getSystemService(ConnectivityManager::class.java)

    @Volatile
    private var running = false
    private var discoveryWanted = true
    private var subscribing = false
    private var receiverRegistered = false
    private var session: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null

    private val discovered = ConcurrentHashMap<String, PeerHandle>()   // userId -> karşı tarafın yayını
    private val firstSeen = ConcurrentHashMap<String, Long>()
    private val handleUsers = ConcurrentHashMap<PeerHandle, String>()
    private val connecting: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val pendingCallbacks = ConcurrentHashMap<String, ConnectivityManager.NetworkCallback>()
    private val links = ConcurrentHashMap<String, AwareLink>()
    private val messageIds = AtomicInteger(1)

    private class AwareLink(
        val linkId: String,
        val userId: String,
        val socket: Socket,
        val out: DataOutputStream,
        val callback: ConnectivityManager.NetworkCallback,
        val server: ServerSocket?,
    ) {
        val writer: ExecutorService = Executors.newSingleThreadExecutor()
    }

    private val myName: String get() = identity.name.value ?: "?"

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (!running) return
            if (aware?.isAvailable == true) {
                if (session == null) attach()
            } else {
                closeSession()
                _state.value = TransportState.UNAVAILABLE
            }
        }
    }

    // ---------------------------------------------------------------- başlat / durdur

    override fun start() {
        if (running) return
        if (aware == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) {
            _state.value = TransportState.UNSUPPORTED
            return
        }
        running = true
        discoveryWanted = true
        ContextCompat.registerReceiver(
            context,
            stateReceiver,
            IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
        attach()
    }

    override fun stop() {
        if (!running) return
        running = false
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(stateReceiver)
            } catch (_: Exception) {
            }
            receiverRegistered = false
        }
        closeSession()
        _state.value = TransportState.OFF
    }

    private fun attach() {
        val manager = aware ?: return
        if (!manager.isAvailable) {
            _state.value = TransportState.UNAVAILABLE
            return
        }
        _state.value = TransportState.STARTING
        try {
            manager.attach(object : AttachCallback() {
                override fun onAttached(s: WifiAwareSession) {
                    if (!running) {
                        s.close()
                        return
                    }
                    session = s
                    startDiscovery(s)
                }

                override fun onAttachFailed() {
                    _state.value = TransportState.ERROR
                }
            }, main)
        } catch (e: SecurityException) {
            Log.w(TAG, "Wi‑Fi Aware izni yok", e)
            _state.value = TransportState.ERROR
        }
    }

    private fun startDiscovery(s: WifiAwareSession) {
        val info = "${identity.userId}|$myName".toByteArray(Charsets.UTF_8)
        try {
            s.publish(
                PublishConfig.Builder()
                    .setServiceName(SERVICE_NAME)
                    .setServiceSpecificInfo(info)
                    .build(),
                object : DiscoverySessionCallback() {
                    override fun onPublishStarted(session: PublishDiscoverySession) {
                        publishSession = session
                        _state.value = TransportState.ACTIVE
                    }

                    override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                        onPublisherMessage(peerHandle, message)
                    }

                    override fun onSessionTerminated() {
                        publishSession = null
                    }
                },
                main,
            )
            if (discoveryWanted) startSubscribe(s)
        } catch (e: SecurityException) {
            Log.w(TAG, "Wi‑Fi Aware izni yok", e)
            _state.value = TransportState.ERROR
        }
    }

    private fun startSubscribe(s: WifiAwareSession) {
        if (subscribeSession != null || subscribing) return
        subscribing = true
        try {
            s.subscribe(
                SubscribeConfig.Builder()
                    .setServiceName(SERVICE_NAME)
                    .build(),
                object : DiscoverySessionCallback() {
                    override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                        subscribing = false
                        if (!running || !discoveryWanted && links.isEmpty()) {
                            session.close()
                            return
                        }
                        subscribeSession = session
                    }

                    override fun onServiceDiscovered(
                        peerHandle: PeerHandle,
                        serviceSpecificInfo: ByteArray,
                        matchFilter: List<ByteArray>,
                    ) {
                        onDiscovered(peerHandle, serviceSpecificInfo)
                    }

                    override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                        onSubscriberMessage(peerHandle, message)
                    }

                    override fun onSessionTerminated() {
                        subscribing = false
                        subscribeSession = null
                    }
                },
                main,
            )
        } catch (e: SecurityException) {
            subscribing = false
            Log.w(TAG, "Wi‑Fi Aware izni yok", e)
        }
    }

    /**
     * Pil tasarrufu: taramayı (subscribe) durdurur/başlatır. Yayın açık kalır, böylece diğerleri
     * bizi bulmaya devam eder. Kurulu bir Wi‑Fi Aware bağlantısı varken tarama kapatılmaz,
     * çünkü bazı telefonlarda tarama oturumu kapanınca o bağlantılar da kopar.
     */
    override fun setDiscovery(enabled: Boolean) {
        main.post {
            if (!running) return@post
            discoveryWanted = enabled
            val s = session ?: return@post
            if (enabled) {
                startSubscribe(s)
            } else if (links.isEmpty() && connecting.isEmpty()) {
                subscribeSession?.close()
                subscribeSession = null
            }
        }
    }

    private fun closeSession() {
        links.values.toList().forEach { close(it) }
        pendingCallbacks.values.forEach { unregister(it) }
        pendingCallbacks.clear()
        publishSession?.close()
        subscribeSession?.close()
        session?.close()
        publishSession = null
        subscribeSession = null
        subscribing = false
        session = null
        discovered.clear()
        firstSeen.clear()
        handleUsers.clear()
        connecting.clear()
    }

    // ---------------------------------------------------------------- bulma ve el sıkışma

    private fun onDiscovered(handle: PeerHandle, info: ByteArray) {
        val (userId, _) = parseIdentity(String(info, Charsets.UTF_8)) ?: return
        if (userId == identity.userId) return
        discovered[userId] = handle
        handleUsers[handle] = userId
        firstSeen.putIfAbsent(userId, System.currentTimeMillis())
        maybeInitiate(userId)
    }

    override fun tick() {
        for (userId in discovered.keys) maybeInitiate(userId)
    }

    /**
     * Normalde kimliği küçük olan taraf başlatır. Karşı taraf 20 sn içinde başlatmazsa
     * (ör. yalnızca bir taraf diğerini görebiliyorsa) büyük olan da dener.
     */
    private fun maybeInitiate(userId: String) {
        if (!running) return
        if (listener.isConnected(userId) || hasLink(userId)) return
        val seenFor = System.currentTimeMillis() - (firstSeen[userId] ?: System.currentTimeMillis())
        if (identity.userId > userId && seenFor < FALLBACK_INITIATE_MS) return
        val handle = discovered[userId] ?: return
        val sub = subscribeSession ?: return
        if (!connecting.add(userId)) return

        sub.sendMessage(handle, messageIds.getAndIncrement(), "$HELLO|${identity.userId}|$myName".toByteArray(Charsets.UTF_8))

        scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (!hasLink(userId)) {
                pendingCallbacks.remove(userId)?.let { unregister(it) }
                connecting.remove(userId)
            }
        }
    }

    /** Yayın tarafı: biri bize HELLO dedi → soket aç, ağı hazırla, READY de. */
    private fun onPublisherMessage(handle: PeerHandle, message: ByteArray) {
        val text = String(message, Charsets.UTF_8)
        if (!text.startsWith("$HELLO|")) return
        val (userId, _) = parseIdentity(text.removePrefix("$HELLO|")) ?: return
        if (listener.isConnected(userId) || hasLink(userId)) return
        val pub = publishSession ?: return

        scope.launch(Dispatchers.IO) {
            val server = try {
                ServerSocket(0)
            } catch (e: IOException) {
                return@launch
            }
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) {
                    closeByCallback(this)
                }
            }
            try {
                val spec = WifiAwareNetworkSpecifier.Builder(pub, handle)
                    .setPskPassphrase(PSK)
                    .setPort(server.localPort)
                    .build()
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                    .setNetworkSpecifier(spec)
                    .build()
                connectivity.requestNetwork(request, callback)
            } catch (e: Exception) {
                Log.w(TAG, "Wi‑Fi Aware ağı istenemedi", e)
                closeQuietly(server)
                return@launch
            }

            main.post {
                try {
                    pub.sendMessage(handle, messageIds.getAndIncrement(), READY.toByteArray(Charsets.UTF_8))
                } catch (_: Exception) {
                }
            }

            try {
                server.soTimeout = CONNECT_TIMEOUT_MS.toInt()
                val socket = server.accept()
                runLink(socket, callback, server)
            } catch (e: Exception) {
                Log.d(TAG, "Wi‑Fi Aware bağlantısı kurulamadı", e)
                closeQuietly(server)
                unregister(callback)
            }
        }
    }

    /** Başlatan taraf: karşı taraf READY dedi → ağa katıl ve sokete bağlan. */
    private fun onSubscriberMessage(handle: PeerHandle, message: ByteArray) {
        if (String(message, Charsets.UTF_8) != READY) return
        val userId = handleUsers[handle] ?: return
        val sub = subscribeSession ?: return
        val opened = AtomicBoolean(false)

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val info = caps.transportInfo as? WifiAwareNetworkInfo ?: return
                val address = info.peerIpv6Addr ?: return
                val port = info.port
                if (port <= 0 || !opened.compareAndSet(false, true)) return
                val cb = this
                scope.launch(Dispatchers.IO) {
                    try {
                        val socket = network.socketFactory.createSocket(address, port)
                        pendingCallbacks.remove(userId)
                        runLink(socket, cb, null)
                    } catch (e: Exception) {
                        Log.d(TAG, "Wi‑Fi Aware soketine bağlanılamadı", e)
                        unregister(cb)
                        connecting.remove(userId)
                    }
                }
            }

            override fun onLost(network: Network) {
                closeByCallback(this)
            }
        }

        try {
            val spec = WifiAwareNetworkSpecifier.Builder(sub, handle)
                .setPskPassphrase(PSK)
                .build()
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                .setNetworkSpecifier(spec)
                .build()
            pendingCallbacks.put(userId, callback)?.let { unregister(it) }
            connectivity.requestNetwork(request, callback)
        } catch (e: Exception) {
            Log.w(TAG, "Wi‑Fi Aware ağı istenemedi", e)
            pendingCallbacks.remove(userId)
            connecting.remove(userId)
        }
    }

    // ---------------------------------------------------------------- soket bağlantısı

    /** Kimlik alışverişi yapar, bağlantıyı kaydeder ve kopana kadar gelen çerçeveleri okur. */
    private fun runLink(socket: Socket, callback: ConnectivityManager.NetworkCallback, server: ServerSocket?) {
        socket.tcpNoDelay = true
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

        writeFrame(out, "${identity.userId}|$myName".toByteArray(Charsets.UTF_8))
        socket.soTimeout = HANDSHAKE_TIMEOUT_MS
        val hello = readFrame(input) ?: throw IOException("El sıkışma yanıtı gelmedi")
        socket.soTimeout = 0
        val (userId, userName) = parseIdentity(String(hello, Charsets.UTF_8))
            ?: throw IOException("Geçersiz el sıkışma")

        val link = AwareLink("$key:${UUID.randomUUID()}", userId, socket, out, callback, server)
        links[link.linkId] = link
        connecting.remove(userId)
        listener.onLinkUp(this, link.linkId, userId, userName)

        try {
            while (running) {
                val frame = readFrame(input) ?: break
                listener.onData(link.linkId, frame)
            }
        } catch (e: IOException) {
            Log.d(TAG, "Wi‑Fi Aware bağlantısı koptu", e)
        } finally {
            close(link)
        }
    }

    override fun send(linkIds: List<String>, bytes: ByteArray, onResult: ((Boolean) -> Unit)?) {
        for (id in linkIds) {
            val link = links[id]
            if (link == null) {
                onResult?.invoke(false)
                continue
            }
            try {
                link.writer.execute {
                    try {
                        synchronized(link.out) { writeFrame(link.out, bytes) }
                        onResult?.invoke(true)
                    } catch (e: IOException) {
                        onResult?.invoke(false)
                        close(link)
                    }
                }
            } catch (_: Exception) {
                // Bağlantı kapanırken yazıcı durdurulmuş olabilir.
                onResult?.invoke(false)
            }
        }
    }

    override fun disconnect(linkId: String) {
        links[linkId]?.let { close(it) }
    }

    private fun hasLink(userId: String) = links.values.any { it.userId == userId }

    private fun close(link: AwareLink) {
        if (links.remove(link.linkId) == null) return
        closeQuietly(link.socket)
        link.server?.let { closeQuietly(it) }
        link.writer.shutdownNow()
        unregister(link.callback)
        listener.onLinkDown(link.linkId)
    }

    private fun closeByCallback(callback: ConnectivityManager.NetworkCallback) {
        links.values.filter { it.callback === callback }.forEach { close(it) }
        unregister(callback)
    }

    private fun unregister(callback: ConnectivityManager.NetworkCallback) {
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
    }

    private fun closeQuietly(c: java.io.Closeable) {
        try {
            c.close()
        } catch (_: Exception) {
        }
    }

    private fun writeFrame(out: DataOutputStream, bytes: ByteArray) {
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }

    private fun readFrame(input: DataInputStream): ByteArray? {
        val length = try {
            input.readInt()
        } catch (e: EOFException) {
            return null
        }
        if (length < 0 || length > MAX_FRAME_BYTES) throw IOException("Geçersiz çerçeve boyu: $length")
        return ByteArray(length).also { input.readFully(it) }
    }

    companion object {
        private const val TAG = "DagMesh/Aware"
        private const val SERVICE_NAME = "dagmesh"
        private const val PSK = "dagmesh-dag-agi-2026"
        private const val HELLO = "HELLO"
        private const val READY = "READY"
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val HANDSHAKE_TIMEOUT_MS = 15_000
        private const val FALLBACK_INITIATE_MS = 20_000L
        private const val MAX_FRAME_BYTES = 8 * 1024 * 1024
    }
}
