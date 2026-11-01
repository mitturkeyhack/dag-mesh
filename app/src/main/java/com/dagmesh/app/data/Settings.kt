package com.dagmesh.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PowerMode(val title: String, val description: String) {
    PERFORMANCE(
        "Performans",
        "Sürekli tarama. Yeni gelenler en hızlı bulunur, pil en çok burada harcanır.",
    ),
    BALANCED(
        "Dengeli",
        "Ekran kapalıyken 30 sn tarar, 1 dk dinlenir. Pil %20'nin altına inince kendiliğinden tasarrufa geçer.",
    ),
    SAVER(
        "Tasarruf",
        "Ekran kapalıyken 15 sn tarar, 2 dk dinlenir; Wi‑Fi Aware taraması kapanır. En uzun pil ömrü.",
    ),
}

/** Uygulama ayarları (telefonda kalıcı). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _powerMode = MutableStateFlow(
        runCatching { PowerMode.valueOf(prefs.getString(KEY_POWER, null) ?: "") }.getOrDefault(PowerMode.BALANCED)
    )
    val powerMode: StateFlow<PowerMode> = _powerMode.asStateFlow()

    fun setPowerMode(mode: PowerMode) {
        prefs.edit().putString(KEY_POWER, mode.name).apply()
        _powerMode.value = mode
    }

    private companion object {
        const val KEY_POWER = "power_mode"
    }
}
