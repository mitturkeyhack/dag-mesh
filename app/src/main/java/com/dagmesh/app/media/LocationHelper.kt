package com.dagmesh.app.media

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.core.location.LocationManagerCompat
import android.os.CancellationSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * GPS konumu (internet gerekmez: telefon uydu sinyalinden konumunu kendisi hesaplar).
 */
object LocationHelper {

    /** Bu telefonun en son bilinen konumu (uzaklık/yön hesabı için). */
    private val _last = MutableStateFlow<Location?>(null)
    val last: StateFlow<Location?> = _last.asStateFlow()

    private val executor = Executors.newSingleThreadExecutor()

    /**
     * Güncel konumu alır. Önce GPS'i dener (internetsiz ilk konum 30 sn – 2 dk sürebilir),
     * olmazsa son bilinen konuma düşer.
     */
    @SuppressLint("MissingPermission")
    suspend fun current(context: Context, timeoutMs: Long = 90_000): Location? = withContext(Dispatchers.IO) {
        val lm = context.getSystemService(LocationManager::class.java) ?: return@withContext null
        val fresh = try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                withTimeoutOrNull(timeoutMs) { single(lm, LocationManager.GPS_PROVIDER) }
            } else {
                null
            }
        } catch (e: SecurityException) {
            null
        }
        val result = fresh ?: lastKnown(lm)
        result?.let { _last.value = it }
        result
    }

    /** Uzaklık hesabı için hızlı tahmin: son bilinen konum (yoksa arka planda tazeler). */
    @SuppressLint("MissingPermission")
    fun lastKnownQuick(context: Context): Location? {
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        return try {
            lastKnown(lm)?.also { if (_last.value == null) _last.value = it }
        } catch (e: SecurityException) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun single(lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            LocationManagerCompat.getCurrentLocation(lm, provider, signal, executor) { location ->
                if (cont.isActive) cont.resume(location)
            }
        }

    @SuppressLint("MissingPermission")
    private fun lastKnown(lm: LocationManager): Location? =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { provider ->
                try {
                    lm.getLastKnownLocation(provider)
                } catch (e: Exception) {
                    null
                }
            }
            .maxByOrNull { it.time }

    // ---------------------------------------------------------------- biçimlendirme

    fun encode(location: Location): String =
        String.format(Locale.US, "%.6f,%.6f,%d", location.latitude, location.longitude, location.accuracy.roundToInt())

    data class Point(val lat: Double, val lon: Double, val accuracy: Int)

    fun decode(text: String): Point? {
        val parts = text.split(',')
        if (parts.size < 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        return Point(lat, lon, parts.getOrNull(2)?.toIntOrNull() ?: 0)
    }

    /** "380 m · kuzeydoğu" gibi uzaklık ve yön. */
    fun distanceAndDirection(from: Location, to: Point): String {
        val results = FloatArray(2)
        Location.distanceBetween(from.latitude, from.longitude, to.lat, to.lon, results)
        val meters = results[0]
        val bearing = (results[1] + 360f) % 360f
        val distance = if (meters >= 1000f) {
            String.format(Locale("tr"), "%.1f km", meters / 1000f)
        } else {
            "${meters.roundToInt()} m"
        }
        return if (meters < 15f) "Yanında (≈ $distance)" else "$distance · ${direction(bearing)}"
    }

    private fun direction(bearing: Float): String {
        val names = listOf("kuzey", "kuzeydoğu", "doğu", "güneydoğu", "güney", "güneybatı", "batı", "kuzeybatı")
        return names[((bearing + 22.5f) / 45f).toInt() % 8]
    }
}
