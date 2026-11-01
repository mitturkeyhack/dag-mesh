package com.dagmesh.app.mesh

import org.json.JSONObject
import java.util.UUID

/**
 * Telefonlar arasında giden tek bir paket.
 *
 * pid  : paketin kendi kimliği (aynı paket zincirde iki kez işlenmesin diye)
 * ref  : mesaj kimliği (MSG ve ACK için)
 * ttl  : paketin daha kaç telefon atlayabileceği
 * hops : şimdiye kadar kaç telefon üzerinden aktarıldığı
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
) {
    fun forwarded(): Packet = copy(ttl = ttl - 1, hops = hops + 1)

    fun toBytes(): ByteArray {
        val o = JSONObject()
        o.put("v", 1)
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
        return o.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        const val TYPE_MSG = "msg"
        const val TYPE_ACK = "ack"
        const val TYPE_ANNOUNCE = "ann"

        const val DEFAULT_TTL = 6
        const val ANNOUNCE_TTL = 4

        fun newId(): String = UUID.randomUUID().toString()

        fun parse(bytes: ByteArray): Packet? = try {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            Packet(
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
            )
        } catch (e: Exception) {
            null
        }

        private fun JSONObject.stringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) getString(key) else null
    }
}
