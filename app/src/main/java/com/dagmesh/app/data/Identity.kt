package com.dagmesh.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.dagmesh.app.crypto.KeyVault

/**
 * Bu telefonun kalıcı kimliği: anahtarlar + görünen ad.
 * Kimlik, imza anahtarından türetilir (bkz. [KeyVault]); başkası bu kimlikle paket gönderemez.
 */
class Identity(context: Context) {
    private val prefs = context.getSharedPreferences("identity", Context.MODE_PRIVATE)

    val vault = KeyVault(context)

    val userId: String = vault.userId

    private val _name = MutableStateFlow(prefs.getString(KEY_NAME, null))
    val name: StateFlow<String?> = _name.asStateFlow()

    fun setName(value: String) {
        val clean = value.trim().replace("|", "").take(24)
        if (clean.isEmpty()) return
        prefs.edit().putString(KEY_NAME, clean).apply()
        _name.value = clean
    }

    private companion object {
        const val KEY_NAME = "name"
    }
}
