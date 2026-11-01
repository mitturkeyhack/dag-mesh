package com.dagmesh.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dagmesh.app.App

@Composable
fun AppRoot(openPeerId: String?, onPeerOpened: () -> Unit) {
    val identity = App.instance.identity
    val name by identity.name.collectAsStateWithLifecycle()

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
    }
}
