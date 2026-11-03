package com.dagmesh.app.sos

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dagmesh.app.MainActivity
import com.dagmesh.app.R
import com.dagmesh.app.media.LocationHelper
import com.dagmesh.app.mesh.MeshManager
import com.dagmesh.app.mesh.Packet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Acil durum (SOS).
 *
 * - Gönderen: konumuyla birlikte 20 sn'de bir yayın yapar; yayın zincirleme herkese ulaşır.
 * - Alan: telefon sessizde olsa bile alarm çalar, titrer, tam ekran uyarı açılır.
 *   "Yardıma geliyorum" diyen kişiler gönderenin ekranında listelenir.
 */
class SosManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val mesh: MeshManager,
) {
    data class Incoming(
        val id: String,
        val from: String,
        val name: String,
        val point: LocationHelper.Point?,
        val hops: Int,
        val startedAt: Long,
        val lastSeen: Long,
        val acknowledged: Boolean,
        val coming: Boolean,
    )

    data class Outgoing(
        val id: String,
        val startedAt: Long,
        val point: LocationHelper.Point?,
        /** Yardıma geldiğini bildirenler: kimlik → ad. */
        val helpers: Map<String, String>,
        /** Uyarıyı gördüğünü bildirenler. */
        val seenBy: Map<String, String>,
    )

    private val _incoming = MutableStateFlow<Map<String, Incoming>>(emptyMap())
    val incoming: StateFlow<Map<String, Incoming>> = _incoming.asStateFlow()

    private val _outgoing = MutableStateFlow<Outgoing?>(null)
    val outgoing: StateFlow<Outgoing?> = _outgoing.asStateFlow()

    private var broadcastJob: Job? = null
    private var player: MediaPlayer? = null
    private var alarmStopJob: Job? = null

    init {
        // Uzun süre haber alınamayan uyarıları kaldır (gönderen kapatmış ya da menzilden çıkmış).
        scope.launch {
            while (isActive) {
                delay(30_000)
                val cutoff = System.currentTimeMillis() - INCOMING_TIMEOUT_MS
                _incoming.update { map -> map.filterValues { it.lastSeen >= cutoff } }
                if (_incoming.value.values.none { !it.acknowledged }) stopAlarm()
            }
        }
    }

    // ---------------------------------------------------------------- gönderen

    fun start() {
        if (_outgoing.value != null) return
        val id = Packet.newId()
        _outgoing.value = Outgoing(id, System.currentTimeMillis(), null, emptyMap(), emptyMap())
        broadcastJob = scope.launch {
            // Hemen, konumsuz bile olsa yayına başla; konum bulununca güncellenir.
            LocationHelper.lastKnownQuick(context)?.let { loc -> setPoint(LocationHelper.encode(loc)) }
            broadcast()
            launch {
                while (isActive) {
                    LocationHelper.current(context, timeoutMs = 60_000)?.let { loc -> setPoint(LocationHelper.encode(loc)) }
                    delay(LOCATION_REFRESH_MS)
                }
            }
            while (isActive) {
                delay(BROADCAST_INTERVAL_MS)
                broadcast()
            }
        }
    }

    fun stop() {
        val current = _outgoing.value ?: return
        broadcastJob?.cancel()
        broadcastJob = null
        _outgoing.value = null
        // Kapatma haberini birkaç kez gönder ki kaybolmasın.
        scope.launch {
            repeat(3) {
                mesh.broadcast(Packet.TYPE_SOS, current.id, CANCEL)
                delay(1_500)
            }
        }
    }

    private fun setPoint(encoded: String) {
        val point = LocationHelper.decode(encoded) ?: return
        _outgoing.update { it?.copy(point = point) }
    }

    private fun broadcast() {
        val current = _outgoing.value ?: return
        val text = current.point?.let { "${it.lat},${it.lon},${it.accuracy}" }.orEmpty()
        mesh.broadcast(Packet.TYPE_SOS, current.id, text)
    }

    fun onAck(packet: Packet) {
        val id = packet.ref ?: return
        _outgoing.update { out ->
            if (out == null || out.id != id) out
            else if (packet.text == ACK_COMING) out.copy(helpers = out.helpers + (packet.from to packet.fromName))
            else out.copy(seenBy = out.seenBy + (packet.from to packet.fromName))
        }
    }

    // ---------------------------------------------------------------- alan

    fun onSos(packet: Packet) {
        val id = packet.ref ?: return
        if (packet.text == CANCEL) {
            _incoming.update { it - id }
            if (_incoming.value.values.none { !it.acknowledged }) stopAlarm()
            cancelNotification(id)
            return
        }
        val now = System.currentTimeMillis()
        val point = packet.text?.takeIf { it.isNotBlank() }?.let { LocationHelper.decode(it) }
        var isNew = false
        _incoming.update { map ->
            val existing = map[id]
            isNew = existing == null
            map + (id to (existing?.copy(
                point = point ?: existing.point,
                hops = packet.hops,
                lastSeen = now,
                name = packet.fromName,
            ) ?: Incoming(id, packet.from, packet.fromName, point, packet.hops, now, now, acknowledged = false, coming = false)))
        }
        if (isNew) {
            startAlarm()
            showNotification(id, packet.fromName)
            // Uzaklık/yön için kendi konumumuzu tazele.
            scope.launch { LocationHelper.current(context, timeoutMs = 60_000) }
        }
    }

    /** "Gördüm" ya da "Yardıma geliyorum". */
    fun acknowledge(id: String, coming: Boolean) {
        val item = _incoming.value[id] ?: return
        _incoming.update { map -> map + (id to item.copy(acknowledged = true, coming = coming || item.coming)) }
        if (_incoming.value.values.none { !it.acknowledged }) stopAlarm()
        cancelNotification(id)
        mesh.sendTo(Packet.TYPE_SOS_ACK, item.from, id, if (coming) ACK_COMING else ACK_SEEN)
    }

    fun dismiss(id: String) {
        _incoming.update { it - id }
        if (_incoming.value.values.none { !it.acknowledged }) stopAlarm()
        cancelNotification(id)
    }

    fun silence() = stopAlarm()

    // ---------------------------------------------------------------- alarm sesi ve titreşim

    private fun startAlarm() {
        if (player != null) return
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                // Alarm kanalı: telefon sessizdeyken de çalar.
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Alarm çalınamadı", e)
            player?.release()
            player = null
        }
        vibrator()?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400, 800, 400, 1500, 600), 0))
        alarmStopJob?.cancel()
        alarmStopJob = scope.launch {
            delay(ALARM_MAX_MS)
            stopAlarm()
        }
    }

    private fun stopAlarm() {
        alarmStopJob?.cancel()
        alarmStopJob = null
        player?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        player = null
        vibrator()?.cancel()
    }

    private fun vibrator(): Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    // ---------------------------------------------------------------- bildirim

    private fun showNotification(id: String, name: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Acil durum (SOS)", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null) // sesi uygulama alarm kanalından çalıyor
                    enableVibration(false)
                    setBypassDnd(true)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
            )
        }
        val open = PendingIntent.getActivity(
            context, id.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mesh)
            .setContentTitle("🆘 $name acil durumda!")
            .setContentText("Konumunu görmek ve yardım etmek için dokun")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setOngoing(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFY_BASE + (id.hashCode() and 0xFFF), notification)
        } catch (_: SecurityException) {
        }
    }

    private fun cancelNotification(id: String) {
        NotificationManagerCompat.from(context).cancel(NOTIFY_BASE + (id.hashCode() and 0xFFF))
    }

    companion object {
        private const val TAG = "DagMesh/SOS"
        private const val CHANNEL = "sos"
        private const val NOTIFY_BASE = 7000
        const val CANCEL = "cancel"
        const val ACK_COMING = "coming"
        const val ACK_SEEN = "seen"
        private const val BROADCAST_INTERVAL_MS = 20_000L
        private const val LOCATION_REFRESH_MS = 60_000L
        private const val INCOMING_TIMEOUT_MS = 5 * 60_000L
        private const val ALARM_MAX_MS = 3 * 60_000L
    }
}
