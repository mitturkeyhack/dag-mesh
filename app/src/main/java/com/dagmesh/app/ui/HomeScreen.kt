package com.dagmesh.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dagmesh.app.App
import com.dagmesh.app.Permissions
import com.dagmesh.app.data.ConversationRow
import com.dagmesh.app.mesh.MeshManager
import com.dagmesh.app.mesh.MeshService
import com.dagmesh.app.mesh.Transport
import com.dagmesh.app.mesh.TransportState
import com.dagmesh.app.ui.theme.SectionStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenChat: (String) -> Unit) {
    val app = App.instance
    val mesh = app.mesh
    val context = LocalContext.current

    val peersMap by mesh.peers.collectAsStateWithLifecycle()
    val running by mesh.running.collectAsStateWithLifecycle()
    val error by mesh.error.collectAsStateWithLifecycle()
    val myName by app.identity.name.collectAsStateWithLifecycle()
    val conversations by remember { app.db.dao().conversations() }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var permsGranted by remember { mutableStateOf(Permissions.hasCore(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permsGranted = Permissions.hasCore(context)
        if (permsGranted) MeshService.start(context)
    }

    // Ayarlardan izin verip geri dönünce ağı otomatik toparla.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val nowGranted = Permissions.hasCore(context)
        val wasGranted = permsGranted
        permsGranted = nowGranted
        if (nowGranted) {
            when {
                !mesh.running.value -> MeshService.start(context)
                !wasGranted || mesh.error.value != null -> MeshService.restart(context)
            }
        }
    }

    LaunchedEffect(Unit) {
        if (permsGranted) {
            if (!mesh.running.value) MeshService.start(context)
        } else {
            permissionLauncher.launch(Permissions.required())
        }
    }

    val peers = peersMap.values.sortedWith(compareBy({ it.hops }, { it.name.lowercase() }))

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Dağ Mesh", style = MaterialTheme.typography.titleLarge)
                        Text(
                            myName.orEmpty(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        when {
                            running -> MeshService.stop(context)
                            permsGranted -> MeshService.start(context)
                            else -> permissionLauncher.launch(Permissions.required())
                        }
                    }) {
                        Icon(
                            Icons.Rounded.PowerSettingsNew,
                            contentDescription = if (running) "Ağı kapat" else "Ağı aç",
                            tint = if (running) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "radar") { RadarCard(peers, running) }

            if (!permsGranted) {
                item(key = "perm") {
                    PermissionCard(
                        onGrant = { permissionLauncher.launch(Permissions.required()) },
                        onSettings = { openAppSettings(context) },
                    )
                }
            }
            error?.let { message ->
                item(key = "error") {
                    ErrorCard(
                        message = message,
                        onRetry = { MeshService.restart(context) },
                        onSettings = { openAppSettings(context) },
                    )
                }
            }

            item(key = "h-near") { SectionHeader("YAKINDAKİLER", peers.size) }
            if (peers.isEmpty()) {
                item(key = "empty") {
                    EmptyHint(
                        if (running) "Etrafta Dağ Mesh açık bir telefon aranıyor…\nBluetooth, Wi‑Fi ve Konum açık olmalı."
                        else "Ağ kapalı. Açmak için sağ üstteki düğmeye dokun."
                    )
                }
            }
            items(peers, key = { "p-" + it.id }) { peer ->
                PeerRow(peer) { onOpenChat(peer.id) }
            }

            if (conversations.isNotEmpty()) {
                item(key = "h-chats") { SectionHeader("SOHBETLER", null) }
                items(conversations, key = { "c-" + it.peerId }) { row ->
                    ConversationItem(row, online = peersMap[row.peerId] != null) { onOpenChat(row.peerId) }
                }
            }
        }
    }
}

@Composable
private fun RadarCard(peers: List<MeshManager.Peer>, running: Boolean) {
    val direct = peers.count { it.isDirect }
    val relayed = peers.size - direct
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Radar(peers, running, Modifier.fillMaxWidth(0.82f))
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (running) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                )
                Text(
                    when {
                        !running -> "Ağ kapalı"
                        peers.isEmpty() -> "Taranıyor"
                        relayed == 0 -> "$direct doğrudan bağlı"
                        else -> "$direct doğrudan · $relayed zincirle"
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            if (running) {
                Spacer(Modifier.height(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    App.instance.mesh.transports.forEach { ChannelRow(it) }
                }
            }
        }
    }
}

/** Bir arama kanalının durum satırı: renkli nokta + ad + durum. */
@Composable
private fun ChannelRow(transport: Transport) {
    val state by transport.state.collectAsStateWithLifecycle()
    val (color, text) = when (state) {
        TransportState.ACTIVE -> MaterialTheme.colorScheme.secondary to "açık"
        TransportState.STARTING -> MaterialTheme.colorScheme.primary to "başlıyor"
        TransportState.UNAVAILABLE -> MaterialTheme.colorScheme.primary to "Wi‑Fi kapalı"
        TransportState.UNSUPPORTED -> MaterialTheme.colorScheme.outline to "bu telefonda yok"
        TransportState.ERROR -> MaterialTheme.colorScheme.error to "hata"
        TransportState.OFF -> MaterialTheme.colorScheme.outline to "kapalı"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            "${transport.label}: $text",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionHeader(title: String, count: Int?) {
    Row(
        Modifier.padding(start = 4.dp, top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = SectionStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (count != null && count > 0) {
            Spacer(Modifier.width(8.dp))
            Text("$count", style = SectionStyle, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun PeerRow(peer: MeshManager.Peer, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Avatar(peer.id, peer.name, online = peer.isDirect)
            Column(Modifier.weight(1f)) {
                Text(peer.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    peerStatus(peer),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (peer.isDirect) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SignalBars(peer.hops)
        }
    }
}

@Composable
private fun ConversationItem(row: ConversationRow, online: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Avatar(row.peerId, row.name, online = online)
            Column(Modifier.weight(1f)) {
                Text(row.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (if (row.fromMe) "Sen: " else "") + row.lastText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    formatTime(row.lastTime),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (row.unread > 0) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (row.unread > 0) {
                    Box(
                        Modifier
                            .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${row.unread}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(onGrant: () -> Unit, onSettings: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("İzin gerekiyor", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "Yakındaki telefonları bulmak için Bluetooth, yakındaki cihazlar ve konum izni lazım. Konumun hiçbir yere gönderilmez.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onGrant, shape = RoundedCornerShape(14.dp)) { Text("İzin ver") }
                OutlinedButton(onClick = onSettings, shape = RoundedCornerShape(14.dp)) { Text("Ayarları aç") }
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit, onSettings: () -> Unit) {
    val permissionProblem = message.contains("PERMISSION", ignoreCase = true)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text(
                    if (permissionProblem) "Bir izin eksik. Ayarlar → İzinler bölümünde Konum ve Yakındaki cihazlar iznini \"İzin ver\" yap."
                    else message,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onRetry, shape = RoundedCornerShape(14.dp)) { Text("Tekrar dene") }
                if (permissionProblem) {
                    OutlinedButton(onClick = onSettings, shape = RoundedCornerShape(14.dp)) { Text("Ayarları aç") }
                }
            }
            if (permissionProblem) {
                Text(
                    message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp, horizontal = 12.dp),
    )
}
