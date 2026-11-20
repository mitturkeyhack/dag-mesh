package com.dagmesh.app.family

import android.util.Base64
import com.google.crypto.tink.subtle.Hkdf
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Aile şifrelemesi.
 *
 * Aile kodu (12 karakter, 60 bit rastgelelik) telefondan çıkmaz. Koddan yavaş bir türetmeyle (PBKDF2, 210 bin tur)
 * ana anahtar, ondan da birbirinden bağımsız iki anahtar çıkar:
 *  - şifreleme anahtarı: mesajları AES-256-GCM ile kilitler. Sunucuya hiç gitmez.
 *  - üyelik anahtarı: sunucuya "bu ailenin üyesiyim" kanıtı (HMAC). Sunucu bunu bilir ama mesaj açamaz.
 * Aile kimliği (fid) üyelik anahtarının özetidir; koddan geriye gidilemez.
 *
 * Mesajlar sabit boyutlara dolgulanır: sunucu mesajın uzunluğunu da göremez.
 */
object FamilyCrypto {

    /** Karışabilecek harfler (I, O, 0, 1) yok. */
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val CODE_LEN = 12
    private const val PBKDF2_ROUNDS = 210_000
    private const val PAD_BLOCK = 512
    private val SALT = "dagmesh-aile-v1".toByteArray(Charsets.UTF_8)
    private val random = SecureRandom()

    class Keys(val code: String, val encKey: ByteArray, val authKey: ByteArray) {
        val fid: String = sha256Hex("dagmesh-fid1".toByteArray(Charsets.UTF_8) + authKey).take(32)
    }

    fun newCode(): String = buildString { repeat(CODE_LEN) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    /** Kullanıcının yazdığını koda çevirir ("dağ-7k4m 92qx-abcd" gibi). Geçersizse null. */
    fun normalize(input: String): String? {
        var s = input.uppercase(Locale.ROOT)
            .replace('Ğ', 'G').replace('İ', 'I').replace('ı', 'I')
            .filter { it in 'A'..'Z' || it in '0'..'9' }
        if (s.length == CODE_LEN + 3 && s.startsWith("DAG")) s = s.drop(3)
        return s.takeIf { it.length == CODE_LEN && it.all { c -> c in ALPHABET } }
    }

    fun display(code: String): String = "DAĞ-" + code.chunked(4).joinToString("-")

    /** Yavaş türetme: eski telefonlarda birkaç saniye sürebilir, arka planda çağrılmalı. */
    fun derive(code: String): Keys {
        val spec = PBEKeySpec(code.toCharArray(), SALT, PBKDF2_ROUNDS, 256)
        val master = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        val enc = Hkdf.computeHkdf("HMACSHA256", master, null, "enc".toByteArray(), 32)
        val auth = Hkdf.computeHkdf("HMACSHA256", master, null, "auth".toByteArray(), 32)
        return Keys(code, enc, auth)
    }

    /** Mesajı kilitler. Kimlik (msgId) ve aile kimliği şifreye bağlanır: kutu başka yere taşınamaz. */
    fun seal(keys: Keys, msgId: String, plain: String): String {
        val raw = plain.toByteArray(Charsets.UTF_8)
        val padded = ByteArray(((raw.size / PAD_BLOCK) + 1) * PAD_BLOCK) { ' '.code.toByte() }
        System.arraycopy(raw, 0, padded, 0, raw.size)
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.encKey, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD("${keys.fid}|$msgId".toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + cipher.doFinal(padded), Base64.NO_WRAP)
    }

    fun open(keys: Keys, msgId: String, ct: String): String? = try {
        val bytes = Base64.decode(ct, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.encKey, "AES"), GCMParameterSpec(128, bytes, 0, 12))
        cipher.updateAAD("${keys.fid}|$msgId".toByteArray(Charsets.UTF_8))
        String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8).trimEnd()
    } catch (e: Exception) {
        null
    }

    /** Sunucu isteğinin üyelik kanıtı: HMAC(üyelik anahtarı, op \n fid \n ts \n sha256(yük)). */
    fun mac(keys: Keys, op: String, ts: Long, payload: String): String {
        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(keys.authKey, "HmacSHA256"))
        val msg = "$op\n${keys.fid}\n$ts\n${sha256Hex(payload.toByteArray(Charsets.UTF_8))}"
        return hmac.doFinal(msg.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun authB64(keys: Keys): String = Base64.encodeToString(keys.authKey, Base64.NO_WRAP)

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}
