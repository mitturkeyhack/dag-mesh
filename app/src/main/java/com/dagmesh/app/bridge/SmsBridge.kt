package com.dagmesh.app.bridge

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.dagmesh.app.App
import com.dagmesh.app.mesh.MeshManager
import com.dagmesh.app.mesh.Packet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Şebeke köprüsü.
 *
 * Gruptan şebekesi olan bir telefon "köprü" olur. Diğerlerinin dışarıya yazdığı mesajlar
 * zincirleme ona ulaşır ve normal SMS olarak gönderilir. Dışarıdan gelen SMS cevabı da aynı yoldan
 * mesajı yazan kişiye geri döner. SMS'ler köprü olan kişinin hattından gider; bu yüzden köprüyü
 * sadece o kişi açar ve günlük bir sınır vardır.
 */
class SmsBridge(private val context: Context, private val mesh: MeshManager) {

    data class BridgeInfo(val userId: String, val name: String, val hops: Int, val lastSeen: Long)

    private val prefs = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    /** Bu telefon köprü mü? */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _sentToday = MutableStateFlow(currentCount())
    val sentToday: StateFlow<Int> = _sentToday.asStateFlow()

    private val _bridges = MutableStateFlow<Map<String, BridgeInfo>>(emptyMap())
    /** Menzildeki (ya da zincirle ulaşılabilen) köprüler. */
    val bridges: StateFlow<Map<String, BridgeInfo>> = _bridges.asStateFlow()

    val dailyLimit: Int get() = DAILY_LIMIT

    /** Bu telefon şu an köprü olarak hizmet verebilir mi? */
    val isActive: Boolean get() = _enabled.value && hasSmsPermissions(context)

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        _enabled.value = on
        mesh.announceNow()
    }

    // ---------------------------------------------------------------- köprü bulma

    /** Duyuru paketlerinden köprüleri takip eder. Yeni köprü bulununca bekleyen SMS'ler gönderilir. */
    fun noteAnnounce(userId: String, name: String, hops: Int, isBridge: Boolean) {
        val now = System.currentTimeMillis()
        var isNew = false
        _bridges.update { map ->
            if (isBridge) {
                isNew = !map.containsKey(userId)
                map + (userId to BridgeInfo(userId, name, hops, now))
            } else {
                map - userId
            }
        }
        if (isNew) mesh.onBridgeAppeared()
    }

    fun expire() {
        val cutoff = System.currentTimeMillis() - BRIDGE_TIMEOUT_MS
        _bridges.update { map -> map.filterValues { it.lastSeen >= cutoff } }
    }

    /** En yakın (en az atlamalı) köprü. Kendimiz köprüysek kendimiz. */
    fun best(): BridgeInfo? = _bridges.value.values.minByOrNull { it.hops }

    // ---------------------------------------------------------------- köprü tarafı: SMS gönder

    /** Biri bizden dışarıya SMS göndermemizi istedi. */
    fun onSmsRequest(packet: Packet) {
        val ref = packet.ref ?: return
        val (number, body) = splitPayload(packet.text) ?: return
        if (!isActive) {
            reply(packet.from, ref, "fail:Köprü kapalı")
            return
        }
        if (alreadySent(ref)) {
            reply(packet.from, ref, "ok")
            return
        }
        if (currentCount() >= DAILY_LIMIT) {
            reply(packet.from, ref, "fail:Köprünün günlük SMS sınırı doldu")
            return
        }
        sendSms(number, "[${packet.fromName} · Dağ Mesh] $body", ref, packet.from)
        rememberRoute(number, packet.from)
    }

    private fun sendSms(number: String, text: String, ref: String, requester: String) {
        try {
            val sms = if (Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val parts = sms.divideMessage(text)
            val sent = PendingIntent.getBroadcast(
                context,
                ref.hashCode(),
                Intent(context, SmsSentReceiver::class.java)
                    .putExtra(EXTRA_REF, ref)
                    .putExtra(EXTRA_REQUESTER, requester),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val intents = ArrayList<PendingIntent?>(parts.size).apply {
                repeat(parts.size - 1) { add(null) }
                add(sent)
            }
            sms.sendMultipartTextMessage(number, null, parts, intents, null)
            markSent(ref)
            incrementCount()
        } catch (e: Exception) {
            Log.w(TAG, "SMS gönderilemedi", e)
            reply(requester, ref, "fail:SMS gönderilemedi")
        }
    }

    /** Android SMS'in gönderilip gönderilmediğini bildirdi. */
    internal fun onSmsSentResult(ref: String, requester: String, ok: Boolean) {
        reply(requester, ref, if (ok) "ok" else "fail:Şebeke yok ya da SMS gönderilemedi")
    }

    private fun reply(to: String, ref: String, result: String) {
        mesh.sendBridgePacket(Packet.TYPE_SMS_STATUS, to, ref, result)
    }

    // ---------------------------------------------------------------- köprü tarafı: gelen SMS

    /** Dışarıdan SMS geldi: bu numaraya en son kim yazdıysa ona ilet. */
    internal fun onSmsReceived(number: String, body: String) {
        if (!_enabled.value) return
        val requester = routeFor(number) ?: return
        mesh.sendBridgePacket(Packet.TYPE_SMS_IN, requester, Packet.newId(), "$number\n$body")
    }

    // ---------------------------------------------------------------- yardımcılar

    private fun rememberRoute(number: String, requester: String) {
        prefs.edit()
            .putString("route_${key(number)}", requester)
            .putLong("route_time_${key(number)}", System.currentTimeMillis())
            .apply()
    }

    private fun routeFor(number: String): String? {
        val k = key(number)
        val time = prefs.getLong("route_time_$k", 0L)
        if (System.currentTimeMillis() - time > ROUTE_TTL_MS) return null
        return prefs.getString("route_$k", null)
    }

    private fun alreadySent(ref: String): Boolean = prefs.getStringSet(KEY_SENT, emptySet())!!.contains(ref)

    private fun markSent(ref: String) {
        val set = prefs.getStringSet(KEY_SENT, emptySet())!!.toMutableList()
        set.add(ref)
        prefs.edit().putStringSet(KEY_SENT, set.takeLast(200).toSet()).apply()
    }

    private fun today(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun currentCount(): Int =
        if (prefs.getString(KEY_DAY, "") == today()) prefs.getInt(KEY_COUNT, 0) else 0

    private fun incrementCount() {
        val count = currentCount() + 1
        prefs.edit().putString(KEY_DAY, today()).putInt(KEY_COUNT, count).apply()
        _sentToday.value = count
    }

    companion object {
        private const val TAG = "DagMesh/Bridge"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SENT = "sent_refs"
        private const val KEY_DAY = "day"
        private const val KEY_COUNT = "count"
        const val EXTRA_REF = "ref"
        const val EXTRA_REQUESTER = "requester"

        private const val DAILY_LIMIT = 50
        private const val BRIDGE_TIMEOUT_MS = 100_000L
        /** Dışarıdaki kişinin cevabı 48 saat içinde gelirse yazana iletilir. */
        private const val ROUTE_TTL_MS = 48L * 60 * 60 * 1000

        val PERMISSIONS = arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS)

        fun hasSmsPermissions(context: Context): Boolean = PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        /** Telefon numarasını karşılaştırmak için son 10 rakam. */
        fun key(number: String): String = number.filter { it.isDigit() }.takeLast(10)

        /** "+90 532 …" gibi yazımları sadeleştirir. */
        fun normalize(number: String): String {
            val trimmed = number.trim()
            val plus = trimmed.startsWith("+")
            val digits = trimmed.filter { it.isDigit() }
            return if (plus) "+$digits" else digits
        }

        fun splitPayload(text: String?): Pair<String, String>? {
            if (text == null) return null
            val i = text.indexOf('\n')
            if (i <= 0) return null
            return text.substring(0, i) to text.substring(i + 1)
        }
    }
}

/** SMS'in gönderim sonucunu alır. */
class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ref = intent.getStringExtra(SmsBridge.EXTRA_REF) ?: return
        val requester = intent.getStringExtra(SmsBridge.EXTRA_REQUESTER) ?: return
        val app = context.applicationContext as? App ?: return
        app.mesh.bridge.onSmsSentResult(ref, requester, resultCode == Activity.RESULT_OK)
    }
}

/** Gelen SMS'leri dinler; köprü açıksa cevapları ilgili kişiye iletir. */
class SmsInReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val app = context.applicationContext as? App ?: return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val number = messages.firstOrNull()?.originatingAddress ?: return
        val body = messages.joinToString("") { it.messageBody.orEmpty() }
        app.mesh.bridge.onSmsReceived(number, body)
    }
}
