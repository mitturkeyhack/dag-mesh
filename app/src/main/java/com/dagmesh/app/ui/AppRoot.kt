package com.dagmesh.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dagmesh.app.App
import com.dagmesh.app.call.CallState

@Composable
fun AppRoot(openPeerId: String?, onPeerOpened: () -> Unit) {
    val app = App.instance
    val identity = app.identity
    val context = LocalContext.current
    val name by identity.name.collectAsStateWithLifecycle()
    val callState by app.mesh.calls.state.collectAsStateWithLifecycle()
    val sosIncoming by app.mesh.sos.incoming.collectAsStateWithLifecycle()

    // Arama sonuçlarını kısa bilgi olarak göster ("Cevap yok" gibi).
    LaunchedEffect(Unit) {
        app.mesh.calls.notices.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        if (name == null) {
            OnboardingScreen(onDone = { identity.setName(it) })
            return@Surface
        }

        val nav = rememberNavController()

        // Bildirime dokunulunca ilgili sohbeti aç
        LaunchedEffect(openPeerId) {
            if (openPeerId != null) {
                nav.navigate("chat/$openPeerId") { launchSingleTop = true }
                onPeerOpened()
            }
        }

        Box(Modifier.fillMaxSize()) {
            NavHost(navController = nav, startDestination = "home") {
                composable("home") {
                    HomeScreen(onOpenChat = { nav.navigate("chat/$it") { launchSingleTop = true } })
                }
                composable("chat/{peerId}") { entry ->
                    ChatScreen(
                        peerId = entry.arguments?.getString("peerId").orEmpty(),
                        onBack = { nav.popBackStack() },
                    )
                }
            }
            // Arama varsa her şeyin üstünde tam ekran arama ekranı
            if (callState != CallState.Idle) CallScreen(callState)
            // Cevaplanmamış acil durum en üstte
            sosIncoming.values.filter { !it.acknowledged }.minByOrNull { it.startedAt }?.let { SosAlertScreen(it) }
        }
    }
}
