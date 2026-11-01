package com.dagmesh.app.call

import android.content.Context
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.util.Log
import com.dagmesh.app.data.Identity
import com.dagmesh.app.data.MeshDao
import com.dagmesh.app.data.MessageEntity
import com.dagmesh.app.media.MediaFiles
import com.dagmesh.app.mesh.MeshManager
import com.dagmesh.app.mesh.Packet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

sealed interface CallState {
    data object Idle : CallState

    data class Outgoing(val peerId: String, val name: String, val callId: String) : CallState

    data class Incoming(val peerId: String, val name: String, val callId: String) : CallState

    data class Active(
        val peerId: String,
        val name: String,
        val callId: String,
        val startedAt: Long,
        val outgoing: Boolean,
    ) : CallState
}

/**
 * İnternetsiz sesli arama.
 *
 * Arama sinyalleri (davet, kabul, ret, kapat) ve ses kareleri doğrudan bağlı telefona gider.
 * Davet cevap gelene kadar 3 sn'de bir tekrarlanır; ses akışı 15 sn kesilirse arama kapanır.
 */
class CallManager(
    private val context: Context,
    @Suppress("unused") private val identity: Identity,
    private val dao: MeshDao,
    private val scope: CoroutineScope,
    private val mesh: MeshManager,
) {
    private val _state = MutableStateFlow<CallState>(CallState.Idle)
    val state: StateFlow<CallState> = _state.asStateFlow()

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    private val _speaker = MutableStateFlow(false)
    val speaker: StateFlow<Boolean> = _speaker.asStateFlow()

    /** Kullanıcıya kısa bilgi (ör. "Cevap yok"). */
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    private val lock = Any()
    private var audio: CallAudio? = null
    private var timeoutJob: Job? = null
    private var watchdogJob: Job? = null
    private var ringtone: Ringtone? = null
    private var ringback: ToneGenerator? = null
    private val frameCounter = AtomicLong()

    @Volatile
    private var lastAudioAt = 0L

    // ---------------------------------------------------------------- kullanıcı eylemleri

    /** Arama başlatır. Başlatılamazsa nedenini döner. */
    fun call(peerId: String, name: String): String? {
        synchronized(lock) {
            if (_state.value != CallState.Idle) return "Zaten bir arama sürüyor"
            if (!mesh.isDirect(peerId)) return "Arama için kişinin doğrudan bağlı olması gerekir"
            val callId = Packet.newId()
            _state.value = CallState.Outgoing(peerId, name, callId)
            signal(peerId, INVITE, callId)
            startRingback()
            timeoutJob = scope.launch {
                repeat(INVITE_RETRIES) {
                    delay(INVITE_INTERVAL_MS)
                    val s = _state.value
                    if (s !is CallState.Outgoing || s.callId != callId) return@launch
                    signal(peerId, INVITE, callId)
                }
                val s = _state.value
                if (s is CallState.Outgoing && s.callId == callId) {
                    signal(peerId, END, callId)
                    finish("Cevap yok", "Yanıtlanmayan arama", outgoing = true)
                }
            }
            return null
        }
    }

    fun accept() {
        val s = _state.value as? CallState.Incoming ?: return
        stopRinging()
        if (!startAudio(s.peerId, s.callId)) {
            signal(s.peerId, END, s.callId)
            finish("Mikrofon açılamadı", null, outgoing = false)
            return
        }
        synchronized(lock) {
            _state.value = CallState.Active(s.peerId, s.name, s.callId, System.currentTimeMillis(), outgoing = false)
        }
        // Kabul sinyali kaybolmasın diye birkaç kez gönder.
        scope.launch {
            repeat(3) {
                signal(s.peerId, ACCEPT, s.callId)
                delay(300)
            }
        }
    }

    fun reject() {
        val s = _state.value as? CallState.Incoming ?: return
        signal(s.peerId, REJECT, s.callId)
        finish(null, "Reddedilen arama", outgoing = false)
    }

    fun hangup() {
        when (val s = _state.value) {
            is CallState.Incoming -> reject()
            is CallState.Outgoing -> {
                signal(s.peerId, END, s.callId)
                finish(null, "Yanıtlanmayan arama", outgoing = true)
            }
            is CallState.Active -> {
                signal(s.peerId, END, s.callId)
                finish(null, summary(s), outgoing = s.outgoing)
            }
            CallState.Idle -> Unit
        }
    }

    fun toggleMute() {
        _muted.value = !_muted.value
        audio?.muted = _muted.value
    }

    fun toggleSpeaker() {
        _speaker.value = !_speaker.value
        audio?.setSpeaker(_speaker.value)
    }

    // ---------------------------------------------------------------- gelen sinyaller

    fun onSignal(packet: Packet) {
        val callId = packet.ref ?: return
        val action = packet.text ?: return
        val current = _state.value
        when (action) {
            INVITE -> synchronized(lock) {
                when {
                    current == CallState.Idle -> {
                        _state.value = CallState.Incoming(packet.from, packet.fromName, callId)
                        startRinging()
                        timeoutJob?.cancel()
                        timeoutJob = scope.launch {
                            delay(RING_TIMEOUT_MS)
                            val s = _state.value
                            if (s is CallState.Incoming && s.callId == callId) {
                                finish(null, "Cevapsız arama", outgoing = false, missed = true)
                            }
                        }
                    }
                    callIdOf(current) == callId -> Unit // tekrarlanan davet
                    else -> signal(packet.from, BUSY, callId)
                }
            }

            ACCEPT -> if (current is CallState.Outgoing && current.callId == callId) becomeActive(current)

            REJECT -> if (current is CallState.Outgoing && current.callId == callId) {
                finish("Arama reddedildi", "Yanıtlanmayan arama", outgoing = true)
            }

            BUSY -> if (current is CallState.Outgoing && current.callId == callId) {
                finish("Kişi başka bir aramada", "Yanıtlanmayan arama", outgoing = true)
            }

            END -> if (callIdOf(current) == callId) {
                when (current) {
                    is CallState.Active -> finish("Arama sonlandı", summary(current), outgoing = current.outgoing)
                    is CallState.Incoming -> finish(null, "Cevapsız arama", outgoing = false, missed = true)
                    else -> finish(null, null, outgoing = true)
                }
            }
        }
    }

    /** Karşıdan gelen ses karesi (gecikmesin diye doğrudan ağ iş parçacığında çağrılır). */
    fun onAudio(packet: Packet) {
        val data = packet.data ?: return
        val current = _state.value
        if (current is CallState.Outgoing && current.callId == packet.ref) {
            // Kabul sinyali kaybolduysa bile ses geliyorsa arama başlamıştır.
            becomeActive(current)
        }
        val active = _state.value as? CallState.Active ?: return
        if (active.callId != packet.ref) return
        lastAudioAt = System.currentTimeMillis()
        audio?.play(data)
    }

    // ---------------------------------------------------------------- iç işleyiş

    private fun becomeActive(outgoing: CallState.Outgoing) {
        synchronized(lock) {
            val s = _state.value
            if (s !is CallState.Outgoing || s.callId != outgoing.callId) return
            timeoutJob?.cancel()
            stopRingback()
            if (!startAudio(s.peerId, s.callId)) {
                signal(s.peerId, END, s.callId)
                finish("Mikrofon açılamadı", null, outgoing = true)
                return
            }
            _state.value = CallState.Active(s.peerId, s.name, s.callId, System.currentTimeMillis(), outgoing = true)
        }
    }

    private fun startAudio(peerId: String, callId: String): Boolean {
        val engine = CallAudio(context) { frame ->
            val packet = mesh.newPacket(
                Packet.TYPE_AUDIO,
                to = peerId,
                ref = callId,
                ttl = 0,
                data = frame,
                pid = "a" + frameCounter.incrementAndGet(),
            )
            mesh.sendDirect(packet)
        }
        engine.muted = _muted.value
        if (!engine.start()) return false
        engine.setSpeaker(_speaker.value)
        audio = engine
        lastAudioAt = System.currentTimeMillis()
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (true) {
                delay(2_000)
                val s = _state.value as? CallState.Active ?: return@launch
                if (s.callId != callId) return@launch
                if (System.currentTimeMillis() - lastAudioAt > AUDIO_TIMEOUT_MS) {
                    finish("Bağlantı koptu", summary(s), outgoing = s.outgoing)
                    return@launch
                }
            }
        }
        return true
    }

    private fun finish(notice: String?, log: String?, outgoing: Boolean, missed: Boolean = false) {
        val previous = synchronized(lock) {
            val current = _state.value
            if (current == CallState.Idle) null else {
                _state.value = CallState.Idle
                current
            }
        } ?: return
        timeoutJob?.cancel()
        watchdogJob?.cancel()
        stopRinging()
        stopRingback()
        audio?.stop()
        audio = null
        _muted.value = false
        _speaker.value = false
        notice?.let { _notices.tryEmit(it) }

        val peerId = when (previous) {
            is CallState.Outgoing -> previous.peerId
            is CallState.Incoming -> previous.peerId
            is CallState.Active -> previous.peerId
            CallState.Idle -> return
        }
        val peerName = when (previous) {
            is CallState.Outgoing -> previous.name
            is CallState.Incoming -> previous.name
            is CallState.Active -> previous.name
            CallState.Idle -> ""
        }
        if (log != null) {
            scope.launch {
                dao.insertMessage(
                    MessageEntity(
                        id = Packet.newId(),
                        peerId = peerId,
                        fromMe = outgoing,
                        text = log,
                        timestamp = System.currentTimeMillis(),
                        status = MessageEntity.STATUS_DELIVERED,
                        hops = 0,
                        isRead = !missed,
                        kind = MessageEntity.KIND_CALL,
                    )
                )
            }
            if (missed) mesh.emitIncoming(peerId, peerName, "📞 Cevapsız arama")
        }
    }

    private fun summary(active: CallState.Active): String {
        val duration = MediaFiles.formatDuration(System.currentTimeMillis() - active.startedAt)
        return (if (active.outgoing) "Giden arama · " else "Gelen arama · ") + duration
    }

    private fun callIdOf(state: CallState): String? = when (state) {
        is CallState.Outgoing -> state.callId
        is CallState.Incoming -> state.callId
        is CallState.Active -> state.callId
        CallState.Idle -> null
    }

    private fun signal(peerId: String, action: String, callId: String) {
        mesh.sendDirect(mesh.newPacket(Packet.TYPE_CALL, to = peerId, ref = callId, text = action, ttl = 0))
    }

    private fun startRinging() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Zil çalınamadı", e)
        }
    }

    private fun stopRinging() {
        ringtone?.stop()
        ringtone = null
    }

    private fun startRingback() {
        try {
            ringback = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70).apply {
                startTone(ToneGenerator.TONE_SUP_RINGTONE)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Çalma sesi başlatılamadı", e)
        }
    }

    private fun stopRingback() {
        ringback?.let {
            it.stopTone()
            it.release()
        }
        ringback = null
    }

    companion object {
        private const val TAG = "DagMesh/Call"
        private const val INVITE = "invite"
        private const val ACCEPT = "accept"
        private const val REJECT = "reject"
        private const val BUSY = "busy"
        private const val END = "end"

        private const val INVITE_INTERVAL_MS = 3_000L
        private const val INVITE_RETRIES = 13
        private const val RING_TIMEOUT_MS = 45_000L
        private const val AUDIO_TIMEOUT_MS = 15_000L
    }
}
