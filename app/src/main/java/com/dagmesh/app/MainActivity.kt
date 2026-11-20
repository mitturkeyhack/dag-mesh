package com.dagmesh.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.dagmesh.app.ui.AppRoot
import com.dagmesh.app.ui.theme.DagMeshTheme
import com.dagmesh.app.update.Updater

class MainActivity : ComponentActivity() {

    private val openPeer = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openPeer.value = intent?.getStringExtra(EXTRA_PEER_ID)
        // Döndürme gibi yeniden oluşturmalarda kısayolu ikinci kez çalıştırma.
        if (savedInstanceState == null) handleQuickAction(intent)

        // Kilit ekranının üstünde sadece cevaplanmamış bir acil durum varken açıl; mesajlar kilitte görünmesin.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                kotlinx.coroutines.flow.combine(
                    App.instance.mesh.sos.incoming,
                    com.dagmesh.app.sos.FallDetector.alertUntil,
                    com.dagmesh.app.enkaz.EnkazMode.state,
                ) { map, fall, enkaz -> map.values.any { !it.acknowledged } || fall != null || enkaz.active }.collect { alarm ->
                    if (Build.VERSION.SDK_INT >= 27) {
                        setShowWhenLocked(alarm)
                        setTurnScreenOn(alarm)
                    }
                }
            }
        }
        setContent {
            DagMeshTheme {
                AppRoot(openPeerId = openPeer.value, onPeerOpened = { openPeer.value = null })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Uygulama her açıldığında (en fazla 15 dk'da bir) güncelleme kontrolü.
        Updater.checkIfDue(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_PEER_ID)?.let { openPeer.value = it }
        handleQuickAction(intent)
    }

    /** Ana ekran kısayolları ve bildirimdeki "Afet Merkezi" düğmesi. */
    private fun handleQuickAction(intent: Intent?) {
        when (intent?.action) {
            QuickActions.ACTION_AFET -> openPeer.value = ROUTE_AFET
            QuickActions.ACTION_SAFE -> {
                QuickActions.markSafe(this)
                openPeer.value = ROUTE_AFET
            }
            QuickActions.ACTION_SOS -> {
                QuickActions.sosRequest.value = true
                openPeer.value = ROUTE_AFET
            }
            else -> return
        }
        // Aynı niyet tekrar işlenmesin.
        setIntent(Intent(intent).setAction(Intent.ACTION_MAIN))
    }

    companion object {
        const val EXTRA_PEER_ID = "peerId"
        const val ROUTE_AFET = "#afet"
    }
}
