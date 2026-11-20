package com.dagmesh.app.enkaz

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import com.dagmesh.app.App
import com.dagmesh.app.mesh.MeshService
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Enkazdayım: telefon düzenli aralıkla düdük çalar ve titreşir; arada dinler.
 * Biri seslenir, vurur ya da bir bulucu "Sesimi duyan var mı?" sorusunu çalarsa hemen cevap verir
 * ve 3 dakika boyunca 8 sn'de bir öter ki arayan yerini daraltabilsin.
 * Pil azaldıkça kendiliğinden öten aralık uzar.
 */
object EnkazMode {
    enum class Mic { OFF, LISTENING, NO_PERMISSION, BLOCKED }

    data class State(
        val active: Boolean = false,
        val auto: Boolean = false,
        val startedAt: Long = 0,
        val nextBeaconAt: Long = 0,
        val lastBeaconAt: Long = 0,
        val beacons: Int = 0,
        val heardAt: Long = 0,
        val responsiveUntil: Long = 0,
        val mic: Mic = Mic.OFF,
        val battery: Int = -1,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private const val RESPONSIVE_MS = 3 * 60_000L
    private const val RESPONSIVE_INTERVAL_MS = 8_000L

    private var job: Job? = null
    private var mic: MicStream? = null
    private var tone: TonePlayer? = null
    private var wake: PowerManager.WakeLock? = null
    private val trigger = Channel<Unit>(Channel.CONFLATED)

    // Kendi düdüğümüzü dışarıdan gelen ses sanmamak için (mikrofon örnek numarası).
    @Volatile private var ownUntil = 0L

    private fun prefs(c: Context) = c.getSharedPreferences("enkaz", Context.MODE_PRIVATE)

    /** Uygulama yeniden başlarsa (ör. sistem kapattıysa) enkaz modunu sürdür. */
    fun resumeIfNeeded(context: Context) {
        if (!_state.value.active && prefs(context).getBoolean("active", false)) {
            start(context, prefs(context).getBoolean("auto", false))
        }
    }

    fun start(context: Context, auto: Boolean) {
        val ctx = context.applicationContext
        if (_state.value.active) return
        prefs(ctx).edit().putBoolean("active", true).putBoolean("auto", auto).apply()
        val now = System.currentTimeMillis()
        _state.value = State(active = true, auto = auto, startedAt = now, nextBeaconAt = now + 1_500)

        val mesh = App.instance.mesh
        MeshService.start(ctx)
        mesh.sos.start()
        mesh.village.set("help")
        mesh.family.sendStatus("help")

        wake = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DagMesh:enkaz").apply {
                setReferenceCounted(false)
                acquire(6 * 3_600_000L)
            }
        tone = TonePlayer(ctx).also { it.boostVolume() }
        startMic(ctx)
        MeshService.refresh(ctx)

        job = App.instance.scope.launch {
            var lastWake = System.currentTimeMillis()
            while (isActive) {
                val s = _state.value
                val wait = (s.nextBeaconAt - System.currentTimeMillis()).coerceAtLeast(0)
                withTimeoutOrNull(wait) { trigger.receive() }
                beacon(ctx)
                // Uzun süren modda uyanık kalma kilidini tazele.
                if (System.currentTimeMillis() - lastWake > 5 * 3_600_000L) {
                    wake?.acquire(6 * 3_600_000L); lastWake = System.currentTimeMillis()
                }
                // Çalma + titreşim bitmeden yenisi başlamasın.
                delay(EnkazSignal.VIB_START_MS + 1_600)
                if (_state.value.mic == Mic.LISTENING && mic?.silent == true) {
                    _state.update { it.copy(mic = Mic.BLOCKED) }
                }
            }
        }
    }

    private fun startMic(ctx: Context) {
        val m = MicStream(ctx) { x, n -> onSamples(x, n) }
        val queryDet = PatternDetector(up = false)
        val voice = VoiceTrigger()
        detectors = queryDet to voice
        mic = m
        val ok = m.start()
        _state.update { it.copy(mic = if (ok) Mic.LISTENING else if (!m.hasPermission()) Mic.NO_PERMISSION else Mic.BLOCKED) }
    }

    @Volatile private var detectors: Pair<PatternDetector, VoiceTrigger>? = null

    private fun onSamples(x: FloatArray, n: Int) {
        val (q, v) = detectors ?: return
        val m = mic ?: return
        val queries = q.feed(x, n)
        val voiced = v.feed(x, n)
        val own = m.samples <= ownUntil
        val heardQuery = queries.any { it.start > ownUntil }
        if (heardQuery || (voiced && !own)) {
            val now = System.currentTimeMillis()
            _state.update { it.copy(heardAt = now, responsiveUntil = now + RESPONSIVE_MS) }
            trigger.trySend(Unit)
        }
    }

    /** "Şimdi öt" düğmesi. */
    fun beaconNow() {
        if (_state.value.active) trigger.trySend(Unit)
    }

    private fun beacon(ctx: Context) {
        val t = tone ?: return
        val m = mic
        val pcm = TonePlayer.beacon
        val durMs = pcm.size * 1000L / TonePlayer.RATE
        if (m != null) ownUntil = m.samples + (durMs + EnkazSignal.VIB_START_MS + 2_500) * EnkazSignal.FS / 1000
        t.boostVolume()
        t.play(pcm)
        Buzz.afterBeacon(ctx)
        val now = System.currentTimeMillis()
        val battery = batteryPercent(ctx)
        val interval = when {
            now < _state.value.responsiveUntil -> RESPONSIVE_INTERVAL_MS
            battery in 0..14 -> 120_000L
            battery in 15..30 -> 60_000L
            else -> 30_000L
        }
        _state.update { it.copy(lastBeaconAt = now, beacons = it.beacons + 1, nextBeaconAt = now + interval, battery = battery) }
    }

    fun stop(context: Context) {
        val ctx = context.applicationContext
        if (!_state.value.active) return
        prefs(ctx).edit().putBoolean("active", false).apply()
        job?.cancel(); job = null
        mic?.stop(); mic = null
        detectors = null
        Buzz.cancel(ctx)
        tone?.restoreVolume(); tone = null
        try { wake?.release() } catch (_: Exception) {}
        wake = null
        _state.value = State()
        MeshService.refresh(ctx)
    }

    /** Mikrofon izni sonradan verildiyse dinlemeyi başlat. */
    fun retryMic(context: Context) {
        if (!_state.value.active || _state.value.mic == Mic.LISTENING) return
        mic?.stop()
        startMic(context.applicationContext)
        MeshService.refresh(context.applicationContext)
    }

    private fun batteryPercent(ctx: Context): Int {
        val i: Intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) level * 100 / scale else -1
    }

    val micWanted: Boolean get() = _state.value.active
}
