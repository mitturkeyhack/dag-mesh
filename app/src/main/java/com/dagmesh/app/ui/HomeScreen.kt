package com.dagmesh.app.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Hiking
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import com.dagmesh.app.data.PowerMode
import com.dagmesh.app.sos.SosManager
import com.dagmesh.app.trail.TrailRecorder
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
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
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.foundation.clickable
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
import com.dagmesh.app.data.MessageEntity
import com.dagmesh.app.data.previewText
import com.dagmesh.app.mesh.MeshManager
import com.dagmesh.app.mesh.MeshService
import com.dagmesh.app.update.Updater
import com.dagmesh.app.mesh.Transport
import com.dagmesh.app.mesh.TransportState
import com.dagmesh.app.ui.theme.SectionStyle

private enum class Tab(val title: String) { RADAR("Yakındakiler"), CHATS("Sohbetler"), TRAIL("Eve dönüş izi"), PROFILE("Profil") }

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
    val powerMode by app.settings.powerMode.collectAsStateWithLifecycle()
    val updateState by Updater.state.collectAsStateWithLifecycle()
    val sosOutgoing by mesh.sos.outgoing.collectAsStateWithLifecycle()
    val sosIncoming by mesh.sos.incoming.collectAsStateWithLifecycle()
    val trailRecording by TrailRecorder.recording.collectAsStateWithLifecycle()
    val trailReturning by TrailRecorder.returning.collectAsStateWithLifecycle()
    var showShare by remember { mutableStateOf(false) }
    var showPower by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showSosSheet by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(Tab.RADAR) }
    var holdProgress by remember { mutableFloatStateOf(0f) }
    var hintTick by remember { mutableIntStateOf(0) }
    var showHint by remember { mutableStateOf(false) }
    val unread = conversations.sumOf { it.unread }

    // Kısa dokunuşta "basılı tut" ipucunu 2,5 sn göster.
    LaunchedEffect(hintTick) {
        if (hintTick > 0) { showHint = true; delay(2500); showHint = false }
    }
    LaunchedEffect(sosOutgoing == null) { if (sosOutgoing == null) showSosSheet = false }

    BackHandler(enabled = tab != Tab.RADAR) { tab = Tab.RADAR }

    val toggleNetwork = {
        when {
            running -> MeshService.stop(context)
            permsGranted -> MeshService.start(context)
            else -> permissionLauncher.launch(Permissions.required())
        }
    }

    if (showRename) {
        RenameDialog(
            current = myName.orEmpty(),
            onDismiss = { showRename = false },
            onSave = { newName ->
                showRename = false
                if (newName.isNotBlank() && newName.trim() != myName) {
                    app.identity.setName(newName)
                    // Yeni ad diğer telefonlara görünsün diye ağı yeniden kur.
                    if (running) MeshService.restart(context)
                }
            },
        )
    }

    if (showShare) ShareSheet(onDismiss = { showShare = false })
    if (showPower) PowerSheet(onDismiss = { showPower = false })
    val out = sosOutgoing
    if (showSosSheet && out != null) {
        ModalBottomSheet(
            onDismissRequest = { showSosSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) { SosActiveCard(out) }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    if (tab == Tab.RADAR) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MountainLogo(Modifier.size(30.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("Dağ Mesh", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Text(tab.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    }
                },
                actions = {
                    if (tab == Tab.RADAR || tab == Tab.CHATS) {
                        PowerModeChip(powerMode) { showPower = true }
                        Spacer(Modifier.width(8.dp))
                        NetworkToggle(running) { toggleNetwork() }
                        Spacer(Modifier.width(12.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            MeshBottomBar(
                tab = tab,
                onTab = { tab = it },
                unread = unread,
                trailActive = trailRecording || trailReturning,
                sosActive = sosOutgoing != null,
                onSosProgress = { holdProgress = it },
                onSosShortTap = { hintTick++ },
                onSosActiveClick = { showSosSheet = true },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
                if (out != null) {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { SosBanner(out) { showSosSheet = true } }
                }
                val inner = PaddingValues(bottom = padding.calculateBottomPadding() + 16.dp)
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        Tab.RADAR -> RadarTab(
                            contentPadding = inner,
                            peers = peers,
                            running = running,
                            permsGranted = permsGranted,
                            error = error,
                            showUpdate = updateState is Updater.State.Downloading || updateState is Updater.State.Installing ||
                                updateState is Updater.State.NeedsConfirm,
                            acknowledgedSos = sosIncoming.values.filter { it.acknowledged }.sortedByDescending { it.lastSeen },
                            onGrant = { permissionLauncher.launch(Permissions.required()) },
                            onOpenChat = onOpenChat,
                        )
                        Tab.CHATS -> ChatsTab(
                            contentPadding = inner,
                            conversations = conversations,
                            peers = peers,
                            peersMap = peersMap,
                            onOpenChat = onOpenChat,
                            onGoRadar = { tab = Tab.RADAR },
                        )
                        Tab.TRAIL -> TrailTab(inner)
                        Tab.PROFILE -> ProfileTab(
                            contentPadding = inner,
                            name = myName.orEmpty(),
                            id = app.identity.userId,
                            powerMode = powerMode,
                            running = running,
                            onRename = { showRename = true },
                            onPower = { showPower = true },
                            onShare = { showShare = true },
                            onToggleNetwork = { toggleNetwork() },
                        )
                    }
                }
            }

            // SOS ipucu: basılı tutarken geri sayım, kısa dokunuşta açıklama.
            AnimatedVisibility(
                visible = holdProgress > 0f || showHint,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = padding.calculateBottomPadding() + 12.dp),
            ) {
                val secs = ((1f - holdProgress) * 2f + 0.4f).roundToInt()
                Surface(color = SosRed, shape = RoundedCornerShape(50), shadowElevation = 6.dp) {
                    Text(
                        if (holdProgress > 0f) "Bırakma… $secs sn · konumun herkese alarmla gider"
                        else "Acil durum için SOS'u 2 saniye basılı tut",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

/** Alt menü: 4 sekme + ortada yükseltilmiş SOS. */
@Composable
private fun MeshBottomBar(
    tab: Tab,
    onTab: (Tab) -> Unit,
    unread: Int,
    trailActive: Boolean,
    sosActive: Boolean,
    onSosProgress: (Float) -> Unit,
    onSosShortTap: () -> Unit,
    onSosActiveClick: () -> Unit,
) {
    Box(Modifier.fillMaxWidth()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp),
        ) {
            Row(
                Modifier
                    .navigationBarsPadding()
                    .height(70.dp)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NavItem(Icons.Rounded.Radar, "Radar", tab == Tab.RADAR) { onTab(Tab.RADAR) }
                NavItem(Icons.Rounded.Forum, "Sohbet", tab == Tab.CHATS, badge = unread) { onTab(Tab.CHATS) }
                Spacer(Modifier.width(84.dp))
                NavItem(Icons.Rounded.Hiking, "İz", tab == Tab.TRAIL, dot = trailActive) { onTab(Tab.TRAIL) }
                NavItem(Icons.Rounded.Person, "Profil", tab == Tab.PROFILE) { onTab(Tab.PROFILE) }
            }
        }
        SosNavButton(
            active = sosActive,
            onProgress = onSosProgress,
            onShortTap = onSosShortTap,
            onActiveClick = onSosActiveClick,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun RowScope.NavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    badge: Int = 0,
    dot: Boolean = false,
    onClick: () -> Unit,
) {
    val pill by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, label = "pill",
    )
    val tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Box(
                Modifier
                    .size(width = 56.dp, height = 30.dp)
                    .clip(RoundedCornerShape(50))
                    .background(pill),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
            }
            if (badge > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-4).dp, y = (-4).dp)
                        .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (badge > 99) "99+" else "$badge",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            } else if (dot) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-8).dp, y = 2.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(SosRed)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RadarTab(
    contentPadding: PaddingValues,
    peers: List<MeshManager.Peer>,
    running: Boolean,
    permsGranted: Boolean,
    error: String?,
    showUpdate: Boolean,
    acknowledgedSos: List<SosManager.Incoming>,
    onGrant: () -> Unit,
    onOpenChat: (String) -> Unit,
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = contentPadding.calculateBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(acknowledgedSos, key = { "sos-" + it.id }) { SosIncomingCard(it) }

        item(key = "radar") { RadarCard(peers, running) }

        if (showUpdate) item(key = "update") { UpdateBanner() }

        if (!permsGranted) {
            item(key = "perm") {
                PermissionCard(onGrant = onGrant, onSettings = { openAppSettings(context) })
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
    }
}

@Composable
private fun ChatsTab(
    contentPadding: PaddingValues,
    conversations: List<ConversationRow>,
    peers: List<MeshManager.Peer>,
    peersMap: Map<String, MeshManager.Peer>,
    onOpenChat: (String) -> Unit,
    onGoRadar: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = contentPadding.calculateBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (peers.isNotEmpty()) {
            item(key = "online") {
                Column {
                    SectionHeader("ŞU AN ULAŞILABİLİR", peers.size)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(peers, key = { "o-" + it.id }) { peer ->
                            Column(
                                Modifier
                                    .width(64.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { onOpenChat(peer.id) }
                                    .padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Avatar(peer.id, peer.name, size = 52.dp, online = peer.isDirect)
                                Spacer(Modifier.height(4.dp))
                                Text(peer.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        if (conversations.isEmpty()) {
            item(key = "empty") {
                Column(
                    Modifier.fillMaxWidth().padding(top = 48.dp, start = 24.dp, end = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier.size(80.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Forum, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                    }
                    Text("Henüz sohbet yok", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Yakınındaki birine dokunarak yazmaya başla. Mesajlar internetsiz, telefondan telefona gider.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    FilledTonalButton(onClick = onGoRadar, shape = RoundedCornerShape(14.dp)) { Text("Yakındakileri gör") }
                }
            }
        } else {
            item(key = "h-chats") { SectionHeader("SOHBETLER", null) }
            items(conversations, key = { "c-" + it.peerId }) { row ->
                ConversationItem(row, online = peersMap[row.peerId] != null) { onOpenChat(row.peerId) }
            }
        }
    }
}

@Composable
private fun ProfileTab(
    contentPadding: PaddingValues,
    name: String,
    id: String,
    powerMode: PowerMode,
    running: Boolean,
    onRename: () -> Unit,
    onPower: () -> Unit,
    onShare: () -> Unit,
    onToggleNetwork: () -> Unit,
) {
    val context = LocalContext.current
    val version = remember { Updater.currentVersionName(context) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Avatar(id, name, size = 84.dp, online = running)
                Spacer(Modifier.height(4.dp))
                Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    if (running) "Yakındakiler seni bu adla görüyor" else "Ağ kapalı · şu an görünmüyorsun",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                FilledTonalButton(onClick = onRename, shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Adı düzenle")
                }
            }
        }

        SectionHeader("AYARLAR", null)
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
            Column {
                SettingRow(
                    icon = powerIcon(powerMode),
                    iconTint = powerTint(powerMode),
                    title = "Pil modu",
                    subtitle = powerMode.title,
                    onClick = onPower,
                )
                RowDivider()
                SettingRow(
                    icon = Icons.Rounded.CellTower,
                    iconTint = if (running) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                    title = "Ağ",
                    subtitle = if (running) "Açık · mesaj alıp iletiyorsun" else "Kapalı",
                    onClick = onToggleNetwork,
                    trailing = { Switch(checked = running, onCheckedChange = { onToggleNetwork() }) },
                )
                RowDivider()
                SettingRow(
                    icon = Icons.Rounded.Share,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = "Uygulamayı paylaş",
                    subtitle = "İnternetsiz: QR ya da Quick Share",
                    onClick = onShare,
                )
            }
        }

        SectionHeader("UYGULAMA", null)
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.padding(8.dp)) { UpdateRow(version) }
        }
        Text(
            "Dağ Mesh · internetsiz, telefondan telefona haberleşme",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(iconTint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) trailing()
        else Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 70.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
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
                    (if (row.fromMe && row.kind != MessageEntity.KIND_CALL) "Sen: " else "") +
                        previewText(row.kind, row.lastText, row.fileName),
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


@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = { Text("Adını değiştir") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.take(24) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Yakındakiler seni bu adla görür.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(value) }, enabled = value.isNotBlank()) { Text("Kaydet") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Vazgeç") }
        },
    )
}
