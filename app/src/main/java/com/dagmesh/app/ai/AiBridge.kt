package com.dagmesh.app.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.dagmesh.app.data.MeshDao
import com.dagmesh.app.data.MessageEntity
import com.dagmesh.app.data.PeerEntity
import com.dagmesh.app.mesh.MeshManager
import com.dagmesh.app.mesh.Packet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Yapay zekâ servisinin ayarları (EVREN ya da başka bir OpenAI uyumlu servis). */
data class AiConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
) {
    val isReady: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()
}

/**
 * Yapay zekâ köprüsü.
 *
 * - Bu telefonda internet ve anahtar varsa soru doğrudan yapay zekâya gider.
 * - Yoksa soru mesh üzerinden zincirleme olarak en yakın "köprü" telefona gider.
 *   Köprü (interneti ve anahtarı olan, paylaşımı açık telefon) soruyu yapay zekâya sorar,
 *   cevabı yine mesh üzerinden geri yollar. Anahtar köprü telefonda kalır, kimseye gitmez.
 * - Hiç köprü yoksa soru sırada bekler; köprü bulununca ya da internet gelince kendiliğinden gider.
 *
 * Köprüler kendilerini 30 sn'de bir giden "duyuru" paketine "gw" işareti ekleyerek tanıtır.
 */
class AiBridge(
    context: Context,
    private val dao: MeshDao,
    private val scope: CoroutineScope,
    private val mesh: MeshManager,
) {
    /** Kullanıcıya gösterilen bağlantı durumu. */
    sealed class Mode {
        /** Ayar yapılmamış ve yakında köprü yok. */
        data object NotConfigured : Mode()
        /** Bu telefon doğrudan soruyor. */
        data object Direct : Mode()
        /** Soru başka bir telefon üzerinden gidiyor. */
        data class ViaGateway(val name: String, val hops: Int) : Mode()
        /** Ayarlı ama şu an internet de köprü de yok: sorular sırada bekler. */
        data object Waiting : Mode()
    }

    private val prefs = context.getSharedPreferences("ai", Context.MODE_PRIVATE)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private val _config = MutableStateFlow(
        AiConfig(
            baseUrl = prefs.getString(KEY_URL, "").orEmpty(),
            apiKey = prefs.getString(KEY_KEY, "").orEmpty(),
            model = prefs.getString(KEY_MODEL, "").orEmpty(),
        )
    )
    val config: StateFlow<AiConfig> = _config.asStateFlow()

    private val _share = MutableStateFlow(prefs.getBoolean(KEY_SHARE, true))
    /** Başkalarının sorularına köprü olmak açık mı? */
    val share: StateFlow<Boolean> = _share.asStateFlow()

    private val _internet = MutableStateFlow(checkInternet())
    val internet: StateFlow<Boolean> = _internet.asStateFlow()

    /** Yakındaki köprüler: kullanıcı kimliği → son görülme. */
    private val _gateways = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Cevabı beklenen soruların kimlikleri ("düşünüyor…" göstermek için). */
    private val _thinking = MutableStateFlow<Set<String>>(emptySet())
    val thinking: StateFlow<Set<String>> = _thinking.asStateFlow()

    /** Bu açılıştan beri başkaları için cevaplanan soru sayısı. */
    private val _served = MutableStateFlow(0)
    val served: StateFlow<Int> = _served.asStateFlow()

    val mode: StateFlow<Mode> = combine(_config, _internet, _gateways, mesh.peers) { config, online, gateways, peers ->
        when {
            config.isReady && online -> Mode.Direct
            else -> {
                val best = bestGateway(gateways, peers)
                when {
                    best != null -> Mode.ViaGateway(peers[best]?.name ?: "köprü", peers[best]?.hops ?: 1)
                    config.isReady -> Mode.Waiting
                    else -> Mode.NotConfigured
                }
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, Mode.NotConfigured)

    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val lastGatewaySend = ConcurrentHashMap<String, Long>()

    /** Köprü tarafı: cevaplanan sorular (aynı soru tekrar gelirse yeniden sormadan aynı cevabı yolla). */
    private val answered = object : LinkedHashMap<String, Pair<String, Boolean>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, Boolean>>?) = size > 200
    }
    private val gatewayWork = ConcurrentHashMap.newKeySet<String>()
    private val servedTimes = ArrayDeque<Long>()

    init {
        scope.launch { dao.upsertPeer(PeerEntity(PEER_ID, NAME, System.currentTimeMillis())) }
        runCatching {
            connectivity?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = setInternet(hasValidated(caps))
                override fun onLost(network: Network) = setInternet(checkInternet())
            })
        }
    }

    // ---------------------------------------------------------------- ayarlar

    fun save(config: AiConfig, share: Boolean) {
        val clean = config.copy(
            baseUrl = AiClient.normalizeBaseUrl(config.baseUrl),
            apiKey = config.apiKey.trim(),
            model = config.model.trim(),
        )
        prefs.edit()
            .putString(KEY_URL, clean.baseUrl)
            .putString(KEY_KEY, clean.apiKey)
            .putString(KEY_MODEL, clean.model)
            .putBoolean(KEY_SHARE, share)
            .apply()
        _config.value = clean
        _share.value = share
        mesh.announceNow()
        flush()
    }

    /** Bu telefon şu an başkalarına köprü olabilir mi? */
    fun isGatewayNow(): Boolean = _config.value.isReady && _share.value && _internet.value

    // ---------------------------------------------------------------- soru gönderme (soran taraf)

    /** MeshManager'ın gönderme sırası buraya yönlendirir (ilk gönderim ve yeniden denemeler). */
    fun transmit(message: MessageEntity) {
        if (!message.fromMe || message.peerId != PEER_ID) return
        if (_config.value.isReady && _internet.value) askDirect(message) else askViaGateway(message)
    }

    private fun askDirect(message: MessageEntity) {
        if (!inFlight.add(message.id)) return
        setThinking(message.id, true)
        scope.launch {
            try {
                dao.updateStatus(message.id, MessageEntity.STATUS_SENT)
                val result = AiClient.chat(_config.value, history(message), message.text)
                when (result) {
                    is AiClient.Result.Ok -> deliverAnswer(message.id, result.text, isError = false, hops = 0)
                    is AiClient.Result.Fail ->
                        // İnternet koptuysa hata gösterme: soru sırada kalsın, sonra yeniden denenir.
                        if (checkInternet()) deliverAnswer(message.id, result.reason, isError = true, hops = 0)
                }
            } finally {
                inFlight.remove(message.id)
                setThinking(message.id, false)
            }
        }
    }

    private fun askViaGateway(message: MessageEntity) {
        val gateway = bestGateway(_gateways.value, mesh.peers.value) ?: return // köprü yok: sırada bekler
        val now = System.currentTimeMillis()
        val last = lastGatewaySend[message.id]
        if (last != null && now - last < RESEND_GAP_MS) return
        lastGatewaySend[message.id] = now

        scope.launch {
            val turns = history(message)
            val payload = JSONObject().put("q", message.text).put("h", JSONArray().apply {
                turns.forEach { put(JSONObject().put("r", it.role).put("c", it.content)) }
            })
            val packet = mesh.newPacket(Packet.TYPE_AI_Q, to = gateway, ref = message.id, text = payload.toString())
            val routed = mesh.sendTracked(packet) { ok ->
                if (ok) scope.launch { dao.updateStatus(message.id, MessageEntity.STATUS_SENT) }
            }
            if (routed) {
                setThinking(message.id, true)
                delay(THINKING_TIMEOUT_MS)
                setThinking(message.id, false)
            } else {
                lastGatewaySend.remove(message.id)
            }
        }
    }

    /** Cevap (ya da hata) geldi: sohbete yapay zekânın mesajı olarak ekle. */
    private suspend fun deliverAnswer(questionId: String, text: String, isError: Boolean, hops: Int) {
        val inserted = dao.insertMessage(
            MessageEntity(
                id = "ai-$questionId",
                peerId = PEER_ID,
                fromMe = false,
                text = if (isError) "$ERROR_PREFIX$text" else text,
                timestamp = System.currentTimeMillis(),
                status = MessageEntity.STATUS_DELIVERED,
                hops = hops,
                isRead = false,
            )
        ) != -1L
        dao.updateStatus(questionId, MessageEntity.STATUS_DELIVERED)
        lastGatewaySend.remove(questionId)
        setThinking(questionId, false)
        if (inserted) mesh.emitIncoming(PEER_ID, NAME, if (isError) "Cevap alınamadı" else text.take(200))
    }

    /** Sohbetin son birkaç mesajı, yapay zekâ konuyu takip edebilsin diye. */
    private suspend fun history(question: MessageEntity): List<AiClient.Turn> {
        val recent = dao.recent(PEER_ID, HISTORY_MESSAGES + 1)
            .filter { it.id != question.id && it.timestamp <= question.timestamp && it.kind == MessageEntity.KIND_TEXT }
            .filter { !it.text.startsWith(ERROR_PREFIX) }
            .reversed()
            .takeLast(HISTORY_MESSAGES)
        var budget = HISTORY_CHARS
        return recent.asReversed().mapNotNull { m ->
            val content = m.text.take(HISTORY_ITEM_CHARS)
            if (budget - content.length < 0) null else {
                budget -= content.length
                AiClient.Turn(if (m.fromMe) "user" else "assistant", content)
            }
        }.asReversed()
    }

    // ---------------------------------------------------------------- köprü tarafı

    /** Başka bir telefonun sorusu bize (köprüye) geldi. */
    fun onQuestion(packet: Packet) {
        val ref = packet.ref ?: return
        synchronized(answered) { answered[ref] }?.let { (text, isError) ->
            sendAnswer(packet.from, ref, text, if (isError) KIND_ERROR else KIND_OK)
            return
        }
        if (!isGatewayNow()) {
            sendAnswer(packet.from, ref, "Bu telefon şu an köprü değil", KIND_RETRY)
            return
        }
        if (!gatewayWork.add(ref)) return // zaten soruluyor

        scope.launch {
            try {
                if (!allowServe()) {
                    remember(ref, "Köprünün saatlik soru sınırı doldu ($HOURLY_LIMIT soru). Biraz sonra tekrar dene.", true)
                    sendAnswer(packet.from, ref, "Köprünün saatlik soru sınırı doldu ($HOURLY_LIMIT soru). Biraz sonra tekrar dene.", KIND_ERROR)
                    return@launch
                }
                val payload = runCatching { JSONObject(packet.text.orEmpty()) }.getOrNull()
                val question = payload?.optString("q").orEmpty().take(QUESTION_MAX_CHARS)
                if (question.isBlank()) return@launch
                val turns = payload?.optJSONArray("h")?.let { array ->
                    (0 until array.length()).mapNotNull { i ->
                        val o = array.optJSONObject(i) ?: return@mapNotNull null
                        val role = o.optString("r")
                        if (role != "user" && role != "assistant") null
                        else AiClient.Turn(role, o.optString("c").take(HISTORY_ITEM_CHARS))
                    }.takeLast(HISTORY_MESSAGES)
                }.orEmpty()

                when (val result = AiClient.chat(_config.value, turns, question)) {
                    is AiClient.Result.Ok -> {
                        remember(ref, result.text, false)
                        _served.update { it + 1 }
                        sendAnswer(packet.from, ref, result.text, KIND_OK)
                    }
                    is AiClient.Result.Fail ->
                        if (checkInternet()) {
                            remember(ref, result.reason, true)
                            sendAnswer(packet.from, ref, result.reason, KIND_ERROR)
                        } else {
                            sendAnswer(packet.from, ref, "Köprünün interneti koptu", KIND_RETRY)
                        }
                }
            } finally {
                gatewayWork.remove(ref)
            }
        }
    }

    private fun sendAnswer(to: String, ref: String, text: String, kind: String) {
        val packet = mesh.newPacket(Packet.TYPE_AI_A, to = to, ref = ref, text = text).copy(kind = kind)
        mesh.send(packet)
    }

    private fun remember(ref: String, text: String, isError: Boolean) {
        synchronized(answered) { answered[ref] = text to isError }
    }

    /** Başkaları için saatte en fazla HOURLY_LIMIT soru (kredini korumak için). */
    private fun allowServe(): Boolean = synchronized(servedTimes) {
        val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L
        while (servedTimes.isNotEmpty() && servedTimes.first() < cutoff) servedTimes.removeFirst()
        if (servedTimes.size >= HOURLY_LIMIT) false else {
            servedTimes.addLast(System.currentTimeMillis())
            true
        }
    }

    // ---------------------------------------------------------------- soran tarafa cevap gelince

    fun onAnswer(packet: Packet) {
        val ref = packet.ref ?: return
        when (packet.kind) {
            KIND_RETRY -> {
                // Bu köprü artık cevap veremiyor: onu unut, başka köprü bulununca soru yeniden gider.
                _gateways.update { it - packet.from }
                lastGatewaySend.remove(ref)
                setThinking(ref, false)
                flush()
            }
            else -> scope.launch {
                deliverAnswer(ref, packet.text.orEmpty(), isError = packet.kind == KIND_ERROR, hops = packet.hops)
            }
        }
    }

    // ---------------------------------------------------------------- köprü duyuruları

    fun onAnnounce(packet: Packet) {
        if (packet.from == mesh.myId) return
        if (packet.text == GATEWAY_MARK) {
            val isNew = packet.from !in _gateways.value
            _gateways.update { it + (packet.from to System.currentTimeMillis()) }
            if (isNew) flush()
        } else if (packet.from in _gateways.value) {
            _gateways.update { it - packet.from }
        }
    }

    /** MeshManager'ın zamanlayıcısından 30 sn'de bir çağrılır. */
    fun tick() {
        val cutoff = System.currentTimeMillis() - GATEWAY_TIMEOUT_MS
        _gateways.update { map -> map.filterValues { it >= cutoff } }
        flush()
    }

    /** Sırada bekleyen soruları yeniden dener. */
    fun flush() {
        if (!_config.value.isReady && _gateways.value.isEmpty()) return
        scope.launch {
            val since = System.currentTimeMillis() - PENDING_WINDOW_MS
            dao.unconfirmed(since).filter { it.peerId == PEER_ID }.forEach {
                transmit(it)
                delay(100)
            }
        }
    }

    // ---------------------------------------------------------------- yardımcılar

    private fun bestGateway(gateways: Map<String, Long>, peers: Map<String, MeshManager.Peer>): String? {
        val cutoff = System.currentTimeMillis() - GATEWAY_TIMEOUT_MS
        val alive = gateways.filter { (id, seen) -> seen >= cutoff && id != mesh.myId }
        if (alive.isEmpty()) return null
        return alive.keys.minWithOrNull(
            compareBy<String>({ peers[it]?.hops ?: 99 }, { -(alive[it] ?: 0L) })
        )
    }

    private fun setThinking(id: String, on: Boolean) {
        _thinking.update { if (on) it + id else it - id }
    }

    private fun setInternet(value: Boolean) {
        val was = _internet.value
        _internet.value = value
        if (value != was) {
            mesh.announceNow()
            if (value) flush()
        }
    }

    private fun checkInternet(): Boolean {
        val cm = connectivity ?: return false
        return runCatching {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            hasValidated(caps)
        }.getOrDefault(false)
    }

    private fun hasValidated(caps: NetworkCapabilities): Boolean =
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    companion object {
        /** Sohbet listesindeki yapay zekâ "kişisi". */
        const val PEER_ID = "ai"
        const val NAME = "Yapay Zekâ"
        const val GATEWAY_MARK = "gw"
        const val ERROR_PREFIX = "⚠️ "

        private const val KIND_OK = "ok"
        private const val KIND_ERROR = "err"
        private const val KIND_RETRY = "retry"

        private const val KEY_URL = "base_url"
        private const val KEY_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_SHARE = "share"

        const val HOURLY_LIMIT = 30
        private const val HISTORY_MESSAGES = 6
        private const val HISTORY_ITEM_CHARS = 600
        private const val HISTORY_CHARS = 3000
        private const val QUESTION_MAX_CHARS = 2000
        private const val RESEND_GAP_MS = 60_000L
        private const val THINKING_TIMEOUT_MS = 3 * 60_000L
        private const val GATEWAY_TIMEOUT_MS = 100_000L
        private const val PENDING_WINDOW_MS = 24L * 60 * 60 * 1000
    }
}
