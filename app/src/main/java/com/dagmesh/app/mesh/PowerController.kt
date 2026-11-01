package com.dagmesh.app.mesh

import android.content.Context
import android.os.BatteryManager
import com.dagmesh.app.App
import com.dagmesh.app.call.CallState
import com.dagmesh.app.data.PowerMode
import com.dagmesh.app.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Ekrandaki tarama durumu. */
enum class ScanState { CONTINUOUS, CYCLE_ON, CYCLE_OFF, OFF }

/**
 * Pil tasarrufu: taramayı seçilen moda göre aralıklı açıp kapatır.
 *
 * - Yayın (diğerlerinin bizi bulması) ve kurulu bağlantılar hiç kesilmez; sadece yeni telefon arama
 *   işi aralıklı yapılır. En çok pili tarama yediği için kazanç büyüktür.
 * - Uygulama ekrandayken, arama sürerken veya dosya aktarılırken her modda sürekli tarar.
 */
class PowerController(
    private val context: Context,
    private val settings: Settings,
    private val mesh: MeshManager,
    private val scope: CoroutineScope,
) {
    private data class Profile(val onMs: Long, val offMs: Long, val awareInCycle: Boolean) {
        val continuous: Boolean get() = offMs == 0L
    }

    private val _scanState = MutableStateFlow(ScanState.OFF)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    /** Dengeli mod düşük pil yüzünden tasarrufa geçtiyse true. */
    private val _lowBatteryOverride = MutableStateFlow(false)
    val lowBatteryOverride: StateFlow<Boolean> = _lowBatteryOverride.asStateFlow()

    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                val profile = currentProfile()
                if (profile.continuous) {
                    apply(nearby = true, aware = true)
                    _scanState.value = ScanState.CONTINUOUS
                    delay(1_500)
                    continue
                }
                apply(nearby = true, aware = profile.awareInCycle)
                _scanState.value = ScanState.CYCLE_ON
                if (!waitUnchanged(profile, profile.onMs)) continue
                apply(nearby = false, aware = false)
                _scanState.value = ScanState.CYCLE_OFF
                waitUnchanged(profile, profile.offMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _scanState.value = ScanState.OFF
    }

    private fun apply(nearby: Boolean, aware: Boolean) {
        mesh.transports.forEach { it.setDiscovery(if (it.key == "wa") aware else nearby) }
    }

    /** Verilen süre boyunca bekler; bu arada koşullar değişirse erkenden false döner. */
    private suspend fun waitUnchanged(profile: Profile, ms: Long): Boolean {
        var left = ms
        while (left > 0) {
            delay(1_000)
            left -= 1_000
            if (currentProfile() != profile) return false
        }
        return true
    }

    private fun currentProfile(): Profile {
        val busy = App.isInForeground ||
            mesh.calls.state.value != CallState.Idle ||
            mesh.media.isBusy
        val mode = settings.powerMode.value
        val lowBattery = mode == PowerMode.BALANCED && batteryPercent() in 0..LOW_BATTERY
        _lowBatteryOverride.value = lowBattery

        return when {
            busy || mode == PowerMode.PERFORMANCE -> CONTINUOUS
            mode == PowerMode.SAVER || lowBattery -> SAVER
            else -> BALANCED
        }
    }

    private fun batteryPercent(): Int = try {
        context.getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (e: Exception) {
        -1
    }

    private companion object {
        val CONTINUOUS = Profile(0, 0, true)
        val BALANCED = Profile(30_000, 60_000, true)
        val SAVER = Profile(15_000, 120_000, false)
        const val LOW_BATTERY = 20
    }
}
