package com.dagmesh.app

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Uygulamayı açmadan ulaşılan afet kısayolları:
 * ana ekranda simgeye basılı tutunca çıkan menü ve kilit ekranındaki bildirim düğmeleri.
 */
object QuickActions {
    const val ACTION_AFET = "com.dagmesh.app.action.AFET"
    const val ACTION_SAFE = "com.dagmesh.app.action.SAFE"
    const val ACTION_SOS = "com.dagmesh.app.action.SOS"

    /** Ana ekran "SOS gönder" kısayolundan gelindi: Afet Merkezi geri sayımlı onay gösterir. */
    val sosRequest = MutableStateFlow(false)

    fun publishShortcuts(context: Context) {
        try {
            fun shortcut(id: String, short: String, long: String, icon: Int, action: String) =
                ShortcutInfoCompat.Builder(context, id)
                    .setShortLabel(short)
                    .setLongLabel(long)
                    .setIcon(IconCompat.createWithResource(context, icon))
                    .setIntent(
                        Intent(context, MainActivity::class.java)
                            .setAction(action)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    )
                    .build()
            ShortcutManagerCompat.setDynamicShortcuts(
                context,
                listOf(
                    shortcut("afet", "Afet Merkezi", "Afet Merkezi", R.drawable.ic_sc_afet, ACTION_AFET),
                    shortcut("safe", "Güvendeyim", "Güvendeyim de", R.drawable.ic_sc_safe, ACTION_SAFE),
                    shortcut("sos", "SOS gönder", "SOS gönder", R.drawable.ic_sc_sos, ACTION_SOS),
                ),
            )
        } catch (_: Exception) {
            // Bazı başlatıcılar kısayolu desteklemez; uygulama yine çalışır.
        }
    }

    /** "Güvendeyim": köy yoklamasına ve aileye "iyiyim" gider. */
    fun markSafe(context: Context) {
        val mesh = App.instance.mesh
        mesh.village.set("ok")
        mesh.family.sendStatus("ok")
        Toast.makeText(context.applicationContext, "✓ Güvendeyim bildirildi", Toast.LENGTH_SHORT).show()
    }
}
