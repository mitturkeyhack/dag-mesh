package com.dagmesh.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.dagmesh.app.ui.AppRoot
import com.dagmesh.app.ui.theme.DagMeshTheme

class MainActivity : ComponentActivity() {

    private val openPeer = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openPeer.value = intent?.getStringExtra(EXTRA_PEER_ID)
        setContent {
            DagMeshTheme {
                AppRoot(openPeerId = openPeer.value, onPeerOpened = { openPeer.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_PEER_ID)?.let { openPeer.value = it }
    }

    companion object {
        const val EXTRA_PEER_ID = "peerId"
    }
}
