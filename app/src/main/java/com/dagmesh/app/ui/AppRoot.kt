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
    val fallUntil by com.dagmesh.app.sos.FallDetector.alertUntil.collectAsStateWithLifecycle()

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
                val route = when (openPeerId) {
                    com.dagmesh.app.family.FamilyManager.NOTIFY_PEER -> "aile"
                    com.dagmesh.app.MainActivity.ROUTE_AFET -> "afet"
                    else -> "chat/$openPeerId"
                }
                nav.navigate(route) { launchSingleTop = true }
                onPeerOpened()
            }
        }

        // Enkaz modu açıkken uygulama açılırsa doğrudan o ekrana git.
        val enkaz by com.dagmesh.app.enkaz.EnkazMode.state.collectAsStateWithLifecycle()
        LaunchedEffect(enkaz.active) {
            if (enkaz.active && nav.currentDestination?.route != "enkaz") nav.navigate("enkaz") { launchSingleTop = true }
        }

        Box(Modifier.fillMaxSize()) {
            NavHost(navController = nav, startDestination = "home") {
                composable("home") {
                    HomeScreen(
                        onOpenChat = { nav.navigate("chat/$it") { launchSingleTop = true } },
                        onOpenAfet = { nav.navigate("afet") { launchSingleTop = true } },
                        onOpenFamily = { nav.navigate("aile") { launchSingleTop = true } },
                    )
                }
                composable("afet") {
                    AfetScreen(
                        onBack = { nav.popBackStack() },
                        onEnkaz = { nav.navigate("enkaz") { launchSingleTop = true } },
                        onBulucu = { nav.navigate("bulucu") { launchSingleTop = true } },
                    )
                }
                composable("enkaz") { EnkazScreen(onBack = { nav.popBackStack() }) }
                composable("bulucu") { BulucuScreen(onBack = { nav.popBackStack() }) }
                composable("aile") { FamilyScreen(onBack = { nav.popBackStack() }) }
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
            fallUntil?.let { FallAlertScreen(it) }
            sosIncoming.values.filter { !it.acknowledged }.minByOrNull { it.startedAt }?.let { SosAlertScreen(it) }
        }
    }
}
