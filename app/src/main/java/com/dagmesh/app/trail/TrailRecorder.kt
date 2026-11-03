package com.dagmesh.app.trail

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Eve dönüş izi: yürürken GPS noktalarını kaydeder, dönüşte aynı yoldan geri götürür.
 * İnternet gerekmez. Noktalar telefonda saklanır, uygulama kapanınca da kaybolmaz.
 */
object TrailRecorder {

    data class Pt(val lat: Double, val lon: Double, val time: Long) {
        fun toLocation(): Location = Location("trail").also { it.latitude = lat; it.longitude = lon }
    }

    private val _points = MutableStateFlow<List<Pt>>(emptyList())
    val points: StateFlow<List<Pt>> = _points.asStateFlow()

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    /** "Geri dön" modu açık mı. */
    private val _returning = MutableStateFlow(false)
    val returning: StateFlow<Boolean> = _returning.asStateFlow()

    /** Son GPS konumu (kayıt ya da dönüş açıkken güncellenir). */
    private val _current = MutableStateFlow<Location?>(null)
    val current: StateFlow<Location?> = _current.asStateFlow()

    /** GPS sinyali var mı (ilk konum gelene kadar false). */
    private val _gpsOff = MutableStateFlow(false)
    val gpsOff: StateFlow<Boolean> = _gpsOff.asStateFlow()

    private var app: Context? = null
    private var listening = false
    private var loaded = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onFix(location)
        override fun onProviderEnabled(provider: String) { _gpsOff.value = false }
        override fun onProviderDisabled(provider: String) { _gpsOff.value = true }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
    }

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        app = context.applicationContext
        _points.value = load()
        val prefs = context.getSharedPreferences("trail", Context.MODE_PRIVATE)
        // Uygulama kayıt sırasında kapandıysa kayda kaldığı yerden devam et.
        if (prefs.getBoolean("recording", false)) {
            _recording.value = true
            updateListening()
        }
    }

    fun startRecording(fresh: Boolean) {
        if (fresh) {
            _points.value = emptyList()
            file()?.delete()
        }
        _returning.value = false
        setRecording(true)
    }

    fun stopRecording() = setRecording(false)

    fun setReturning(on: Boolean) {
        _returning.value = on
        updateListening()
    }

    fun clear() {
        setRecording(false)
        _returning.value = false
        _points.value = emptyList()
        file()?.delete()
        updateListening()
    }

    private fun setRecording(on: Boolean) {
        _recording.value = on
        app?.getSharedPreferences("trail", Context.MODE_PRIVATE)?.edit()?.putBoolean("recording", on)?.apply()
        updateListening()
    }

    @SuppressLint("MissingPermission")
    private fun updateListening() {
        val ctx = app ?: return
        val want = _recording.value || _returning.value
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        if (want && !listening) {
            try {
                _gpsOff.value = !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3_000L, 3f, listener, Looper.getMainLooper())
                listening = true
                // İlk konum gelene kadar son bilineni göster.
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                    if (System.currentTimeMillis() - it.time < 2 * 60_000) _current.value = it
                }
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
        } else if (!want && listening) {
            try { lm.removeUpdates(listener) } catch (_: Exception) {}
            listening = false
        }
    }

    private fun onFix(location: Location) {
        _gpsOff.value = false
        _current.value = location
        if (!_recording.value) return
        // Kötü ölçümleri at: 40 m'den kötü hassasiyet ya da gerçek dışı sıçrama.
        if (location.hasAccuracy() && location.accuracy > 40f) return
        val list = _points.value
        val last = list.lastOrNull()
        if (last != null) {
            val d = distance(last.lat, last.lon, location.latitude, location.longitude)
            val dt = (location.time - last.time).coerceAtLeast(1) / 1000f
            val minStep = maxOf(8f, (location.accuracy).coerceAtMost(20f))
            if (d < minStep) return
            if (d / dt > 15f) return // 54 km/s üstü yürüyüşte imkânsız
        }
        val p = Pt(location.latitude, location.longitude, location.time)
        _points.value = list + p
        append(p)
    }

    // ---- hesaplar ----

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val out = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, out)
        return out[0]
    }

    /** İz boyunca yürünen toplam mesafe (m). */
    fun length(pts: List<Pt>): Float {
        var sum = 0f
        for (i in 1 until pts.size) sum += distance(pts[i - 1].lat, pts[i - 1].lon, pts[i].lat, pts[i].lon)
        return sum
    }

    data class Guide(
        /** Şu an yönelinecek iz noktası. */
        val target: Pt,
        /** Hedefe kuşbakışı mesafe (m). */
        val toTarget: Float,
        /** İz boyunca başlangıca kalan yol (m). */
        val remaining: Float,
        /** İzden uzaklık (m). */
        val offTrail: Float,
        val arrived: Boolean,
    )

    /**
     * Geri dönüş rehberi: ize en yakın noktayı bulur, iz boyunca ~30 m geriyi hedef gösterir.
     * Böylece dolambaçlı yolda bile kestirme yerine yürünen yoldan götürür.
     */
    fun guide(pts: List<Pt>, here: Location): Guide? {
        if (pts.isEmpty()) return null
        val start = pts.first()
        val toStart = distance(here.latitude, here.longitude, start.lat, start.lon)
        if (toStart < 20f) return Guide(start, toStart, toStart, 0f, arrived = true)

        var nearest = 0
        var best = Float.MAX_VALUE
        pts.forEachIndexed { i, p ->
            val d = distance(here.latitude, here.longitude, p.lat, p.lon)
            if (d <= best) { best = d; nearest = i } // eşitlikte başa daha yakın değil, sona yakın olanı al
        }
        var idx = nearest
        var walked = 0f
        while (idx > 0 && walked < 30f) {
            walked += distance(pts[idx].lat, pts[idx].lon, pts[idx - 1].lat, pts[idx - 1].lon)
            idx--
        }
        val target = pts[idx]
        var remaining = 0f
        for (i in nearest downTo 1) remaining += distance(pts[i].lat, pts[i].lon, pts[i - 1].lat, pts[i - 1].lon)
        remaining += best
        return Guide(
            target = target,
            toTarget = distance(here.latitude, here.longitude, target.lat, target.lon),
            remaining = remaining,
            offTrail = best,
            arrived = false,
        )
    }

    fun bearing(from: Location, to: Pt): Float = from.bearingTo(to.toLocation())

    // ---- saklama ----

    private fun file(): File? = app?.let { File(it.filesDir, "trail.csv") }

    private fun append(p: Pt) {
        try { file()?.appendText("${p.lat},${p.lon},${p.time}\n") } catch (_: Exception) {}
    }

    private fun load(): List<Pt> = try {
        file()?.takeIf { it.exists() }?.readLines()?.mapNotNull { line ->
            val parts = line.split(',')
            if (parts.size < 3) null
            else Pt(parts[0].toDoubleOrNull() ?: return@mapNotNull null, parts[1].toDoubleOrNull() ?: return@mapNotNull null, parts[2].toLongOrNull() ?: 0L)
        } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }
}
