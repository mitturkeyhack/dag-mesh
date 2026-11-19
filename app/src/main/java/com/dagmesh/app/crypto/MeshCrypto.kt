package com.dagmesh.app.crypto

import android.content.Context
import android.util.Base64
import com.dagmesh.app.mesh.MediaMeta
import com.dagmesh.app.mesh.Packet
import com.google.crypto.tink.subtle.Ed25519Verify
import com.google.crypto.tink.subtle.Hkdf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Paketlerin mühürlenmesi ve açılması.
 *
 * Kişiye özel paketler (mesaj, dosya, onay, arama sesi, yapay zekâ sorusu...):
 *   İki tarafın X25519 anahtarlarından HKDF ile türetilen ortak anahtarla AES-256-GCM.
 *   Yönlendirme bilgileri (kimden, kime, tür, kimlik, zaman) açık kalır ama "ek doğrulanan veri"
 *   olarak şifreye bağlanır; yolda değiştirilirse paket açılmaz. İçerik sadece alıcıda açılır.
 *
 * Herkese giden paketler (SOS, harita işareti, duyuru):
 *   Gizli olamaz (herkes okumalı) ama Ed25519 ile imzalanır. Başkası adına gönderilemez,
 *   yolda değiştirilemez; imzası tutmayan paket ilerletilmez.
 */
class MeshCrypto(context: Context, private val vault: KeyVault) {

    /** Bir kişinin doğrulanmış açık anahtarları. */
    class PeerKey(val sigPublic: ByteArray, val dhPublic: ByteArray, val certB64: String)

    private val prefs = context.getSharedPreferences("peer_keys", Context.MODE_PRIVATE)
    private val keys = ConcurrentHashMap<String, PeerKey>()
    private val sessionKeys = ConcurrentHashMap<String, ByteArray>()
    private val random = SecureRandom()

    private val _verified = MutableStateFlow(prefs.getStringSet(KEY_VERIFIED, emptySet()).orEmpty().toSet())
    /** Yüz yüze güvenlik numarası karşılaştırılarak doğrulanan kişiler. */
    val verified: StateFlow<Set<String>> = _verified.asStateFlow()

    val myCertB64: String = b64(vault.keyCert)

    init {
        prefs.all.forEach { (id, value) ->
            if (id.startsWith(CERT_PREFIX) && value is String) {
                val peerId = id.removePrefix(CERT_PREFIX)
                parseCert(peerId, value)?.let { keys[peerId] = it }
            }
        }
    }

    fun hasKey(peerId: String): Boolean = keys.containsKey(peerId)

    /**
     * Pakette gelen anahtar belgesini kontrol eder ve saklar.
     * Kimlik anahtardan türetildiği için belge kimlikle uyuşmuyorsa reddedilir.
     * Dönen ikinci değer: bu kişinin anahtarı yeni mi öğrenildi?
     */
    fun accept(peerId: String, certB64: String): Pair<PeerKey, Boolean>? {
        keys[peerId]?.let { if (it.certB64 == certB64) return it to false }
        val key = parseCert(peerId, certB64) ?: return null
        val isNew = !keys.containsKey(peerId)
        keys[peerId] = key
        sessionKeys.remove(peerId)
        prefs.edit().putString(CERT_PREFIX + peerId, certB64).apply()
        return key to isNew
    }

    private fun parseCert(peerId: String, certB64: String): PeerKey? = try {
        val raw = Base64.decode(certB64, Base64.NO_WRAP)
        if (raw.size != KeyVault.KEY_CERT_SIZE) null else {
            val sigPub = raw.copyOfRange(0, 32)
            val dhPub = raw.copyOfRange(32, 64)
            val sig = raw.copyOfRange(64, 128)
            if (KeyVault.idFor(sigPub) != peerId) null
            else {
                Ed25519Verify(sigPub).verify(sig, dhPub + KeyVault.KEY_CERT_CONTEXT)
                PeerKey(sigPub, dhPub, certB64)
            }
        }
    } catch (e: Exception) {
        null
    }

    // ---------------------------------------------------------------- mühürleme (gönderen)

    /**
     * Bu telefonun oluşturduğu paketi mühürler.
     * Kişiye özel pakette alıcının anahtarı henüz bilinmiyorsa null döner (mesaj sırada bekler).
     */
    fun seal(p: Packet): Packet? {
        if (p.isSealed) return p
        return if (p.to == null) sign(p) else encrypt(p)
    }

    private fun sign(p: Packet): Packet {
        val sig = vault.sign(signedBytes(p))
        return p.copy(sig = b64(sig), keyCert = myCertB64)
    }

    private fun encrypt(p: Packet): Packet? {
        val to = p.to ?: return null
        val key = sessionKey(to) ?: return null
        val bundle = JSONObject()
        p.text?.let { bundle.put("x", it) }
        p.kind?.let { bundle.put("kd", it) }
        p.replyTo?.let { bundle.put("rp", it) }
        p.replyText?.let { bundle.put("rt", it) }
        p.replyAuthor?.let { bundle.put("ra", it) }
        p.media?.let { m ->
            bundle.put("k", m.kind).put("fn", m.name).put("mt", m.mime)
                .put("sz", m.size).put("ch", m.chunks).put("du", m.durationMs)
        }
        val aad = header(p)
        val sealedBody = aead(Cipher.ENCRYPT_MODE, key, bundle.toString().toByteArray(Charsets.UTF_8), aad) ?: return null
        val sealedData = p.data?.let { aead(Cipher.ENCRYPT_MODE, key, it, aad + DATA_TAG) ?: return null }
        // Ses kareleri ve dosya parçaları çok sık gider: anahtar belgesi teklif / arama paketinde zaten gitti.
        val withCert = p.type != Packet.TYPE_AUDIO && p.type != Packet.TYPE_CHUNK
        return p.copy(
            text = null, kind = null, replyTo = null, replyText = null, replyAuthor = null, media = null,
            data = sealedData,
            sealed = b64(sealedBody),
            keyCert = if (withCert) myCertB64 else null,
        )
    }

    // ---------------------------------------------------------------- açma (alıcı)

    /** Herkese giden paketin imzasını doğrular. Tutmazsa false (paket atılır, ilerletilmez). */
    fun verifyBroadcast(p: Packet): Boolean {
        val sig = p.sig ?: return false
        val key = p.keyCert?.let { accept(p.from, it)?.first } ?: keys[p.from] ?: return false
        return try {
            Ed25519Verify(key.sigPublic).verify(Base64.decode(sig, Base64.NO_WRAP), signedBytes(p))
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Bana gelen kişiye özel paketi açar. Açılamazsa (sahte, bozuk, anahtar yok) null. */
    fun open(p: Packet): Packet? {
        val sealed = p.sealed ?: return null
        if (p.keyCert != null && accept(p.from, p.keyCert) == null) return null
        val key = sessionKey(p.from) ?: return null
        val aad = header(p)
        val body = aead(Cipher.DECRYPT_MODE, key, Base64.decode(sealed, Base64.NO_WRAP), aad) ?: return null
        val data = p.data?.let { aead(Cipher.DECRYPT_MODE, key, it, aad + DATA_TAG) ?: return null }
        val o = try { JSONObject(String(body, Charsets.UTF_8)) } catch (e: Exception) { return null }
        val media = if (o.has("k")) MediaMeta(
            kind = o.getString("k"),
            name = o.optString("fn", "dosya"),
            mime = o.optString("mt", "application/octet-stream"),
            size = o.optLong("sz"),
            chunks = o.optInt("ch"),
            durationMs = o.optLong("du"),
        ) else null
        return p.copy(
            text = o.str("x"), kind = o.str("kd"),
            replyTo = o.str("rp"), replyText = o.str("rt"), replyAuthor = o.str("ra"),
            media = media, data = data,
            sealed = null,
        )
    }

    // ---------------------------------------------------------------- doğrulama

    /**
     * İki kişinin güvenlik numarası: iki telefonda da aynı çıkar.
     * Yüz yüze ya da telefonda karşılaştırılır; aynıysa arada kimse yoktur.
     */
    fun safetyNumber(peerId: String): String? {
        val peer = keys[peerId] ?: return null
        val (a, b) = listOf(vault.sigPublic, peer.sigPublic).sortedWith { x, y -> compare(x, y) }
        val digest = MessageDigest.getInstance("SHA-256").digest("dagmesh-sn1".toByteArray() + a + b)
        val number = BigInteger(1, digest.copyOfRange(0, 10)).mod(BigInteger.TEN.pow(20))
        return number.toString().padStart(20, '0').chunked(5).joinToString(" ")
    }

    fun setVerified(peerId: String, value: Boolean) {
        val next = if (value) _verified.value + peerId else _verified.value - peerId
        _verified.value = next
        prefs.edit().putStringSet(KEY_VERIFIED, next).apply()
    }

    // ---------------------------------------------------------------- yardımcılar

    private fun sessionKey(peerId: String): ByteArray? {
        sessionKeys[peerId]?.let { return it }
        val peer = keys[peerId] ?: return null
        val shared = try { vault.sharedSecret(peer.dhPublic) } catch (e: Exception) { return null }
        val (lo, hi) = listOf(vault.userId, peerId).sorted()
        val key = Hkdf.computeHkdf("HMACSHA256", shared, HKDF_SALT, "$lo|$hi".toByteArray(Charsets.UTF_8), 32)
        sessionKeys[peerId] = key
        return key
    }

    private fun aead(mode: Int, key: ByteArray, input: ByteArray, aad: ByteArray): ByteArray? = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        if (mode == Cipher.ENCRYPT_MODE) {
            val iv = ByteArray(12).also { random.nextBytes(it) }
            cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            iv + cipher.doFinal(input)
        } else {
            if (input.size < 12 + 16) null else {
                cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, input, 0, 12))
                cipher.updateAAD(aad)
                cipher.doFinal(input, 12, input.size - 12)
            }
        }
    } catch (e: Exception) {
        null
    }

    /** Yolda değişmeyen başlık alanları (atlama sayısı ve ömür hariç: onları aradaki telefonlar değiştirir). */
    private fun header(p: Packet): ByteArray {
        val out = ByteArrayOutputStream()
        listOf("dm3", p.type, p.pid, p.ref.orEmpty(), p.from, p.fromName, p.to.orEmpty(), p.ts.toString(), p.idx.toString())
            .forEach { out.write(it.toByteArray(Charsets.UTF_8)); out.write(0) }
        return out.toByteArray()
    }

    private fun signedBytes(p: Packet): ByteArray =
        header(p) + p.text.orEmpty().toByteArray(Charsets.UTF_8) + byteArrayOf(0) + p.kind.orEmpty().toByteArray(Charsets.UTF_8)

    private fun compare(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    private fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private companion object {
        const val CERT_PREFIX = "c:"
        const val KEY_VERIFIED = "verified"
        val HKDF_SALT = "dagmesh-e2e-v1".toByteArray(Charsets.UTF_8)
        val DATA_TAG = byteArrayOf(1)
    }
}
