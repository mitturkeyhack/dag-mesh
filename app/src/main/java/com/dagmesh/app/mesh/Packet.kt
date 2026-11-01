package com.dagmesh.app.mesh

import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.UUID

/** Dosya ekinin bilgileri (teklif paketinde ve her parçada taşınır). */
data class MediaMeta(
    val kind: String,
    val name: String,
    val mime: String,
    val size: Long,
    val chunks: Int,
    val durationMs: Long,
)

/**
 * Telefonlar arasında giden tek bir paket.
 *
 * pid  : paketin kendi kimliği (aynı paket zincirde iki kez işlenmesin diye)
 * ref  : mesaj / arama kimliği
 * ttl  : paketin daha kaç telefon atlayabileceği
 * hops : şimdiye kadar kaç telefon üzerinden aktarıldığı
 * data : ham bayt yükü (dosya parçası, ses karesi). Varsa paket ikili biçimde kodlanır,
 *        böylece base64 ile %33 şişirmeden doğrudan gider.
 */
data class Packet(
    val type: String,
    val pid: String,
    val ref: String?,
    val from: String,
    val fromName: String,
    val to: String?,
    val text: String?,
    val ts: Long,
    val ttl: Int,
    val hops: Int,
    val media: MediaMeta? = null,
    val idx: Int = -1,
    val data: ByteArray? = null,
) {
    fun forwarded(): Packet = copy(ttl = ttl - 1, hops = hops + 1)

    fun toBytes(): ByteArray {
        val o = JSONObject()
        o.put("v", 2)
        o.put("t", type)
        o.put("p", pid)
        ref?.let { o.put("r", it) }
        o.put("f", from)
        o.put("n", fromName)
        to?.let { o.put("to", it) }
        text?.let { o.put("x", it) }
        o.put("ts", ts)
        o.put("ttl", ttl)
        o.put("h", hops)
        if (idx >= 0) o.put("i", idx)
        media?.let { m ->
            o.put("k", m.kind)
            o.put("fn", m.name)
            o.put("mt", m.mime)
            o.put("sz", m.size)
            o.put("ch", m.chunks)
            o.put("du", m.durationMs)
        }
        val header = o.toString().toByteArray(Charsets.UTF_8)
        val payload = data ?: return header

        // İkili biçim: [0x01][başlık uzunluğu (4 bayt)][başlık JSON][ham veri]
        return ByteBuffer.allocate(1 + 4 + header.size + payload.size)
            .put(BINARY_MAGIC)
            .putInt(header.size)
            .put(header)
            .put(payload)
            .array()
    }

    override fun equals(other: Any?): Boolean = other is Packet && other.pid == pid
    override fun hashCode(): Int = pid.hashCode()

    companion object {
        const val TYPE_MSG = "msg"
        const val TYPE_ACK = "ack"
        const val TYPE_ANNOUNCE = "ann"
        const val TYPE_CHUNK = "chk"
        const val TYPE_NACK = "nak"
        const val TYPE_CALL = "call"
        const val TYPE_AUDIO = "aud"

        const val DEFAULT_TTL = 6
        const val ANNOUNCE_TTL = 4

        private const val BINARY_MAGIC: Byte = 0x01

        fun newId(): String = UUID.randomUUID().toString()

        fun parse(bytes: ByteArray): Packet? = try {
            if (bytes.isNotEmpty() && bytes[0] == BINARY_MAGIC) {
                val buffer = ByteBuffer.wrap(bytes)
                buffer.get()
                val headerSize = buffer.int
                if (headerSize <= 0 || headerSize > bytes.size - 5) null
                else {
                    val header = ByteArray(headerSize).also { buffer.get(it) }
                    val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
                    fromJson(JSONObject(String(header, Charsets.UTF_8)), data)
                }
            } else {
                fromJson(JSONObject(String(bytes, Charsets.UTF_8)), null)
            }
        } catch (e: Exception) {
            null
        }

        private fun fromJson(o: JSONObject, data: ByteArray?): Packet {
            val media = if (o.has("k")) {
                MediaMeta(
                    kind = o.getString("k"),
                    name = o.optString("fn", "dosya"),
                    mime = o.optString("mt", "application/octet-stream"),
                    size = o.optLong("sz"),
                    chunks = o.optInt("ch"),
                    durationMs = o.optLong("du"),
                )
            } else {
                null
            }
            return Packet(
                type = o.getString("t"),
                pid = o.getString("p"),
                ref = o.stringOrNull("r"),
                from = o.getString("f"),
                fromName = o.optString("n", "?"),
                to = o.stringOrNull("to"),
                text = o.stringOrNull("x"),
                ts = o.optLong("ts"),
                ttl = o.optInt("ttl", 0),
                hops = o.optInt("h", 0),
                media = media,
                idx = o.optInt("i", -1),
                data = data,
            )
        }

        private fun JSONObject.stringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) getString(key) else null
    }
}
