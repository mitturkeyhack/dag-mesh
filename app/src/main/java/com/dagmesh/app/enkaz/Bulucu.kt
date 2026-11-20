package com.dagmesh.app.enkaz

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.dagmesh.app.App
import com.dagmesh.app.mesh.MeshService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Enkazda ara: mikrofon Dağ Mesh düdüğünü arar. Bulunca gücünü (sıcak/soğuk) ve
 * titreşim ritminin de duyulup duyulmadığını gösterir. "Sesimi duyan var mı?" düğmesi
 * sözü söyler ve enkazdaki telefonların hemen cevap vermesini sağlayan iniş sesini çalar.
 */
object Bulucu {
    enum class Trend { UP, DOWN, SAME, FIRST }

    data class Heard(
        val at: Long,
        val db: Double,
        val percent: Int,
        val trend: Trend,
        val vibration: Boolean = false,
    )

    data class State(
        val active: Boolean = false,
        val micOk: Boolean = true,
        val micSilent: Boolean = false,
        val last: Heard? = null,
        val history: List<Heard> = emptyList(),
        /** Son 20 sn, saniye başına seviye (0..1). */
        val levels: List<Float> = emptyList(),
        val callingUntil: Long = 0,
        val listeningUntil: Long = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var mic: MicStream? = null
    private var det: PatternDetector? = null
    private var vib: VibrationProbe? = null
    private var tone: TonePlayer? = null
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile private var ownFrom = -1L
    @Volatile private var ownUntil = -1L
    private val pendingVib = ArrayList<Pair<Long, Long>>() // başlangıç örneği → bulunma zamanı
    private var secMax = 0.0
    private var secStart = 0L

    const val PHRASE = "Sesimi duyan var mı? Duyuyorsan vur, ya da seslen."

    fun percentOf(db: Double): Int = (((db - 15.0) / 50.0) * 100).toInt().coerceIn(3, 100)

    fun start(context: Context): Boolean {
        val ctx = context.applicationContext
        if (_state.value.active) return true
        val d = PatternDetector(up = true)
        val v = VibrationProbe()
        det = d; vib = v
        tone = TonePlayer(ctx)
        val m = MicStream(ctx) { x, n -> onSamples(x, n) }
        mic = m
        val ok = m.start()
        _state.value = State(active = ok, micOk = ok)
        if (!ok) { mic = null; return false }
        MeshService.refresh(ctx)
        tts = TextToSpeech(ctx) { status ->
            val t = tts ?: return@TextToSpeech
            if (status == TextToSpeech.SUCCESS) {
                val r = t.setLanguage(Locale("tr", "TR"))
                ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                t.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                t.setSpeechRate(0.9f)
            }
        }
        return true
    }

    fun stop(context: Context) {
        if (!_state.value.active && mic == null) return
        mic?.stop(); mic = null
        det = null; vib = null
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tts = null; ttsReady = false
        tone?.restoreVolume(); tone = null
        _state.value = State()
        MeshService.refresh(context.applicationContext)
    }

    private fun onSamples(x: FloatArray, n: Int) {
        val d = det ?: return
        val v = vib ?: return
        val m = mic ?: return
        val found = d.feed(x, n)
        v.feed(x, n)
        val nowSamples = m.samples
        val now = System.currentTimeMillis()

        // Saniye başına gösterge çubuğu
        if (d.levelDb > secMax) secMax = d.levelDb
        if (nowSamples - secStart >= EnkazSignal.FS) {
            secStart = nowSamples
            val lv = ((secMax - 8.0) / 50.0).coerceIn(0.03, 1.0).toFloat()
            secMax = 0.0
            _state.update { s -> s.copy(levels = (s.levels + lv).takeLast(20), micSilent = m.silent) }
        }

        for (f in found) {
            // Kendi çaldığımız sesle çakışanı say
            val end = f.start + (EnkazSignal.PATTERN_SEC * EnkazSignal.FS).toLong()
            if (ownFrom >= 0 && end >= ownFrom && f.start <= ownUntil) continue
            val at = now - (nowSamples - f.start) * 1000 / EnkazSignal.FS
            _state.update { s ->
                val prev = s.history.lastOrNull { at - it.at < 120_000 }
                val trend = when {
                    prev == null -> Trend.FIRST
                    f.strengthDb - prev.db > 2.0 -> Trend.UP
                    f.strengthDb - prev.db < -2.0 -> Trend.DOWN
                    else -> Trend.SAME
                }
                val h = Heard(at, f.strengthDb, percentOf(f.strengthDb), trend)
                s.copy(last = h, history = (s.history + h).takeLast(30))
            }
            synchronized(pendingVib) { pendingVib.add(f.start to at) }
        }

        // Titreşim, düdükten ~1,5 sn sonra biter: hazır olanları değerlendir.
        synchronized(pendingVib) {
            val it = pendingVib.iterator()
            while (it.hasNext()) {
                val (start, at) = it.next()
                if (v.ready(start)) {
                    it.remove()
                    if (v.heard(start)) {
                        _state.update { s ->
                            val upd = s.history.map { h -> if (h.at == at) h.copy(vibration = true) else h }
                            s.copy(history = upd, last = s.last?.let { l -> if (l.at == at) l.copy(vibration = true) else l })
                        }
                    }
                }
            }
        }
    }

    /** "Sesimi duyan var mı?": söyle, iniş sesini çal, sonra dinle. */
    fun call(context: Context) {
        val t = tone ?: return
        val m = mic ?: return
        val now = System.currentTimeMillis()
        if (now < _state.value.callingUntil) return
        val queryMs = TonePlayer.query.size * 1000L / TonePlayer.RATE
        val speechMs = if (ttsReady) 3_500L else 0L
        ownFrom = m.samples - EnkazSignal.FS / 2
        ownUntil = m.samples + (speechMs + queryMs + 1_500) * EnkazSignal.FS / 1000
        _state.update { it.copy(callingUntil = now + speechMs + queryMs, listeningUntil = now + speechMs + queryMs + 12_000) }
        t.boostVolume()
        val playQuery = {
            val mm = mic
            if (mm != null) ownUntil = maxOf(ownUntil, mm.samples + (queryMs + 1_500) * EnkazSignal.FS / 1000)
            t.play(TonePlayer.query)
        }
        val engine = tts
        if (ttsReady && engine != null) {
            var played = false
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (!played) { played = true; playQuery() } }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { if (!played) { played = true; playQuery() } }
            })
            engine.speak(PHRASE, TextToSpeech.QUEUE_FLUSH, Bundle(), "dagmesh-call")
            // Konuşma motoru takılırsa sesi yine de çal.
            App.instance.scope.launch {
                delay(speechMs + 2_000)
                if (!played) { played = true; playQuery() }
            }
        } else {
            playQuery()
        }
    }
}
