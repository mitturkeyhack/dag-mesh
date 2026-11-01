package com.dagmesh.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Bu telefonun kalıcı kimliği (rastgele kimlik + görünen ad). */
class Identity(context: Context) {
    private val prefs = context.getSharedPreferences("identity", Context.MODE_PRIVATE)

    val userId: String = prefs.getString(KEY_ID, null)
        ?: UUID.randomUUID().toString().replace("-", "").take(12).also {
            prefs.edit().putString(KEY_ID, it).apply()
        }

    private val _name = MutableStateFlow(prefs.getString(KEY_NAME, null))
    val name: StateFlow<String?> = _name.asStateFlow()

    fun setName(value: String) {
        val clean = value.trim().replace("|", "").take(24)
        if (clean.isEmpty()) return
        prefs.edit().putString(KEY_NAME, clean).apply()
        _name.value = clean
    }

    private companion object {
        const val KEY_ID = "id"
        const val KEY_NAME = "name"
    }
}
