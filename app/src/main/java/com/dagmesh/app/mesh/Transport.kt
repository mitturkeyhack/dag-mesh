package com.dagmesh.app.mesh

import kotlinx.coroutines.flow.StateFlow

/** Bir iletişim kanalının durumu (ekrandaki kanal göstergesi için). */
enum class TransportState {
    OFF,
    STARTING,
    ACTIVE,
    /** Telefonun donanımı bu kanalı desteklemiyor. */
    UNSUPPORTED,
    /** Destekliyor ama şu an kullanılamıyor (ör. Wi‑Fi kapalı). */
    UNAVAILABLE,
    ERROR,
}

/**
 * Telefonlar arası bir iletişim kanalı (Bluetooth/Nearby, Wi‑Fi Aware, ...).
 * Her kanal kendi yöntemiyle diğer telefonları bulur, bağlanır ve ham baytları taşır.
 * Hangi kanaldan gelirse gelsin paketler ortak ağ motorunda (MeshManager) işlenir.
 */
interface Transport {
    /** Bağlantı kimliklerinin ön eki, ör. "nb" veya "wa". */
    val key: String
    val label: String
    val state: StateFlow<TransportState>

    fun start()
    fun stop()

    /** Baytları verilen bağlantılara gönderir. Gönderim başarılı olunca [onSent] çağrılır. */
    fun send(linkIds: List<String>, bytes: ByteArray, onSent: (() -> Unit)? = null)

    /** Bir bağlantıyı kapatır (ör. aynı kişiye ikinci bağlantı açıldıysa). */
    fun disconnect(linkId: String)

    /** Ağ motoru 10 sn'de bir çağırır: kopan veya yarım kalan bağlantıları yeniden dener. */
    fun tick() {}
}

/** Kanalların ağ motoruna haber verdiği olaylar. */
interface LinkListener {
    fun isConnected(userId: String): Boolean
    fun onLinkUp(transport: Transport, linkId: String, userId: String, userName: String)
    fun onLinkDown(linkId: String)
    fun onData(linkId: String, bytes: ByteArray)
    fun onTransportError(transport: Transport, message: String)
}

internal fun parseIdentity(raw: String): Pair<String, String>? {
    val i = raw.indexOf('|')
    if (i <= 0) return null
    return raw.substring(0, i) to raw.substring(i + 1).ifBlank { "?" }
}
