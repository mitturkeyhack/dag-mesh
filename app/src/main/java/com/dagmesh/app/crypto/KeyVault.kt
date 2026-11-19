package com.dagmesh.app.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.X25519
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Bu telefonun kalıcı kimlik anahtarları.
 *
 * - İmza anahtarı (Ed25519): yayınları (SOS, harita işareti, duyuru) imzalar ve kimliği kanıtlar.
 * - Anahtar değişim anahtarı (X25519): iki kişi arasındaki ortak şifreleme anahtarını kurar.
 *
 * Özel anahtarlar telefondan çıkmaz. Diskte, Android'in donanım destekli kasasındaki (Keystore)
 * bir AES anahtarıyla şifrelenmiş halde durur.
 *
 * Kullanıcı kimliği imza anahtarından türetilir (SHA-256'nın ilk 80 biti). Böylece kimse
 * başkasının kimliğiyle paket gönderemez: kimlik ile anahtar uyuşmazsa paket reddedilir.
 */
class KeyVault(context: Context) {

    private val prefs = context.getSharedPreferences("keyvault", Context.MODE_PRIVATE)

    private val sigSeed: ByteArray
    private val dhPrivate: ByteArray

    val sigPublic: ByteArray
    val dhPublic: ByteArray

    /** Kendini doğrulayan kullanıcı kimliği (20 onaltılık karakter). */
    val userId: String

    /** Açık anahtar belgesi: imza anahtarı + değişim anahtarı + ikincisinin birinciyle imzası. */
    val keyCert: ByteArray

    private val signer: Ed25519Sign

    init {
        val (seed, dh) = loadOrCreate()
        sigSeed = seed
        dhPrivate = dh
        val pair = Ed25519Sign.KeyPair.newKeyPairFromSeed(sigSeed)
        sigPublic = pair.publicKey
        dhPublic = X25519.publicFromPrivate(dhPrivate)
        signer = Ed25519Sign(sigSeed)
        userId = idFor(sigPublic)
        keyCert = sigPublic + dhPublic + signer.sign(dhPublic + KEY_CERT_CONTEXT)
    }

    fun sign(data: ByteArray): ByteArray = signer.sign(data)

    /** Karşı tarafın değişim anahtarıyla ortak sır (ham X25519 çıktısı). */
    fun sharedSecret(peerDhPublic: ByteArray): ByteArray = X25519.computeSharedSecret(dhPrivate, peerDhPublic)

    // ---------------------------------------------------------------- saklama

    private fun loadOrCreate(): Pair<ByteArray, ByteArray> {
        prefs.getString(KEY_BLOB, null)?.let { stored ->
            val raw = runCatching { unwrap(stored) }.getOrNull()
            if (raw != null && raw.size == 64) return raw.copyOfRange(0, 32) to raw.copyOfRange(32, 64)
            Log.w(TAG, "Kayıtlı anahtarlar açılamadı, yeni kimlik oluşturuluyor")
        }
        val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val dh = X25519.generatePrivateKey()
        prefs.edit().putString(KEY_BLOB, wrap(seed + dh)).commit()
        return seed to dh
    }

    /** Keystore varsa onunla şifreler ("k:" öneki); yoksa (çok nadir) uygulamanın özel alanında düz saklar ("p:"). */
    private fun wrap(raw: ByteArray): String = try {
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val out = cipher.iv + cipher.doFinal(raw)
        "k:" + Base64.encodeToString(out, Base64.NO_WRAP)
    } catch (e: Exception) {
        Log.w(TAG, "Keystore kullanılamadı, anahtarlar uygulama alanında saklanıyor", e)
        "p:" + Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    private fun unwrap(stored: String): ByteArray = when {
        stored.startsWith("k:") -> {
            val bytes = Base64.decode(stored.substring(2), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(AES_MODE)
            cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, bytes, 0, 12))
            cipher.doFinal(bytes, 12, bytes.size - 12)
        }
        stored.startsWith("p:") -> Base64.decode(stored.substring(2), Base64.NO_WRAP)
        else -> throw IllegalStateException("bilinmeyen biçim")
    }

    private fun masterKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(MASTER_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(MASTER_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val TAG = "KeyVault"
        private const val KEY_BLOB = "blob"
        private const val MASTER_ALIAS = "dagmesh_vault_v1"
        private const val AES_MODE = "AES/GCM/NoPadding"
        val KEY_CERT_CONTEXT = "dagmesh-kc1".toByteArray(Charsets.UTF_8)
        const val KEY_CERT_SIZE = 128

        /** İmza anahtarından kullanıcı kimliği. */
        fun idFor(sigPublic: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(sigPublic)
                .joinToString("") { "%02x".format(it) }
                .take(20)
    }
}
