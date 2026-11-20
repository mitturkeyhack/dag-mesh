package com.dagmesh.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dagmesh.app.App
import com.dagmesh.app.family.FamilyManager
import com.dagmesh.app.net.NetBridge
import com.dagmesh.app.sos.StatusBoard

val StatusOk = Color(0xFF5FD3BE)
val StatusHurt = Color(0xFFF4BE4F)
val StatusHelp = Color(0xFFFF6F6F)
val StatusNone = Color(0xFF8E98AE)

fun statusColor(status: String?): Color = when (status) {
    "ok" -> StatusOk
    "hurt" -> StatusHurt
    "help" -> StatusHelp
    else -> StatusNone
}

/** "şimdi", "3 dk önce", "2 sa önce", "dün", "4 gün önce". */
fun agoText(ts: Long, now: Long = System.currentTimeMillis()): String {
    val min = ((now - ts) / 60_000).coerceAtLeast(0)
    return when {
        min < 1 -> "şimdi"
        min < 60 -> "$min dk önce"
        min < 24 * 60 -> "${min / 60} sa önce"
        min < 48 * 60 -> "dün"
        else -> "${min / (24 * 60)} gün önce"
    }
}

fun viaText(via: String): String = when (via) {
    FamilyManager.VIA_MESH -> "yakında (mesh)"
    FamilyManager.VIA_INTERNET -> "internet"
    FamilyManager.VIA_BRIDGE -> "köprü üzerinden"
    FamilyManager.VIA_ME -> "sen"
    else -> via
}

@Composable
fun StatusDot(status: String?, size: Int = 12) {
    if (status == null) {
        Box(Modifier.size(size.dp).clip(CircleShape).border(2.dp, StatusNone, CircleShape))
    } else {
        Box(Modifier.size(size.dp).clip(CircleShape).background(statusColor(status)))
    }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, top = 14.dp, bottom = 2.dp),
    )
}

/** Afet Merkezi: deprem anında tek ekran. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AfetScreen(onBack: () -> Unit, onEnkaz: () -> Unit = {}, onBulucu: () -> Unit = {}) {
    val mesh = App.instance.mesh
    val internet by mesh.ai.internet.collectAsStateWithLifecycle()
    val share by mesh.ai.share.collectAsStateWithLifecycle()
    val peers by mesh.peers.collectAsStateWithLifecycle()
    val netMode by mesh.net.mode.collectAsStateWithLifecycle()
    val mine by mesh.village.mine.collectAsStateWithLifecycle()
    val entries by mesh.village.entries.collectAsStateWithLifecycle()
    val familyInfo by mesh.family.info.collectAsStateWithLifecycle()
    var showShare by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val sosRequest by com.dagmesh.app.QuickActions.sosRequest.collectAsStateWithLifecycle()
    val sosOutgoing by mesh.sos.outgoing.collectAsStateWithLifecycle()

    // Kısayoldan doğrudan buraya gelindiyse ağ henüz açılmamış olabilir.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!mesh.running.value && com.dagmesh.app.Permissions.hasCore(context)) {
            com.dagmesh.app.mesh.MeshService.start(context)
        }
    }

    if (showShare) ShareSheet(onDismiss = { showShare = false })
    var confirmEnkaz by remember { mutableStateOf(false) }
    if (confirmEnkaz) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmEnkaz = false },
            title = { Text("Enkaz modu açılsın mı?", fontWeight = FontWeight.Bold) },
            text = { Text("Telefon düdük çalıp titreşecek ve yakındaki herkese konumunla SOS gidecek.") },
            confirmButton = {
                androidx.compose.material3.Button(
                    onClick = { confirmEnkaz = false; onEnkaz() },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = Color(0xFF2B1406)),
                ) { Text("Aç", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirmEnkaz = false }) { Text("Vazgeç") } },
        )
    }
    if (sosRequest) {
        if (sosOutgoing != null) {
            com.dagmesh.app.QuickActions.sosRequest.value = false
        } else {
            SosCountdownDialog(
                onSend = {
                    com.dagmesh.app.QuickActions.sosRequest.value = false
                    mesh.sos.start()
                },
                onCancel = { com.dagmesh.app.QuickActions.sosRequest.value = false },
            )
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Afet Merkezi", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Geri") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "conn") { ConnectionCard(internet, share, peers.size, netMode) }

            item(key = "l-status") { Label("DURUMUN") }
            item(key = "status") {
                StatusButtons(mine?.status) { s ->
                    mesh.village.set(s)
                    mesh.family.sendStatus(s)
                }
            }
            mine?.let { m ->
                item(key = "sent") {
                    Text(
                        "✓ ${FamilyManager.statusLabel(m.status)} · ${agoText(m.at)} gönderildi: " +
                            (if (familyInfo != null) "ailene ve " else "") + "yakındaki herkese.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StatusOk,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }

            item(key = "l-enkaz") {
                Row(Modifier.padding(start = 4.dp, top = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ENKAZ", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(50)) {
                        Text("YENİ", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF2B1406), modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
            }
            item(key = "enkaz") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    EnkazCard(
                        color = MaterialTheme.colorScheme.primary, ink = Color(0xFF2B1406),
                        icon = SeismoIcon, title = "Enkazdayım",
                        sub = "Düdük çalar, titreşir, seslenmeye cevap verir",
                        onClick = { if (com.dagmesh.app.enkaz.EnkazMode.state.value.active) onEnkaz() else confirmEnkaz = true },
                    )
                    EnkazCard(
                        color = StatusOk, ink = Color(0xFF062A24),
                        icon = Icons.Rounded.Hearing, title = "Enkazda ara",
                        sub = "Telefonu enkaza yasla, düdüğü bul",
                        onClick = onBulucu,
                    )
                }
            }

            item(key = "l-village") { Label("KÖY YOKLAMASI") }
            item(key = "village") { VillageCard(peers.keys, entries, mine) }

            item(key = "share") { ShareCard { showShare = true } }

            item(key = "sos") {
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SosHoldButton()
                    Text(
                        "Hayati tehlikede önce 112'yi dene; şebeke yoksa SOS konumunla birlikte herkese ulaşır.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun EnkazCard(
    color: Color,
    ink: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    sub: String,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, color = color, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(28.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                Text(sub, color = ink.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = ink)
        }
    }
}

@Composable
private fun ConnectionCard(internet: Boolean, share: Boolean, nearby: Int, netMode: NetBridge.Mode) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ConnRow(if (internet) "ok" else null, if (internet) "İnternet var" else "Şebeke / internet yok")
            ConnRow(
                if (nearby > 0) "ok" else null,
                if (nearby > 0) "Yakında $nearby telefon · mesh ağı çalışıyor" else "Yakında Dağ Mesh'li telefon yok",
            )
            val bridge = when {
                internet && share -> "ok" to "Bu telefon köprü: internetsiz yakındakiler senin üzerinden haberleşir"
                internet -> null to "İnternetin var ama köprü kapalı (Profil → Yapay zekâ)"
                netMode is NetBridge.Mode.ViaGateway -> "ok" to "Köprü var: ${netMode.name} telefonu internete bağlı"
                else -> null to "Köprü yok · aile mesajları köprü bulununca gider"
            }
            ConnRow(bridge.first, bridge.second)
        }
    }
}

@Composable
private fun ConnRow(status: String?, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusDot(status, 10)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StatusButtons(selected: String?, onPick: (String) -> Unit) {
    val options = listOf(
        Triple("ok", "İyiyim", "Güvendeyim, yardıma ihtiyacım yok"),
        Triple("hurt", "Yaralıyım", "Yaralıyım ama durumum kontrol altında"),
        Triple("help", "Yardım lazım", "Acil yardıma ihtiyacım var"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { (id, label, hint) ->
            val on = selected == id
            val color = statusColor(id)
            Surface(
                onClick = { onPick(id) },
                color = if (on) color else MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(18.dp),
                border = if (on) null else androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    val ink = if (on) Color(0xFF14171F) else MaterialTheme.colorScheme.onSurface
                    Box(Modifier.size(16.dp).clip(CircleShape).background(if (on) Color(0xFF14171F) else color))
                    Column {
                        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ink)
                        Text(hint, style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.85f))
                    }
                }
            }
        }
    }
}

@Composable
private fun VillageCard(nearby: Set<String>, entries: Map<String, StatusBoard.Entry>, mine: StatusBoard.Mine?) {
    val ids = nearby + entries.keys
    val counts = mutableMapOf("ok" to 0, "hurt" to 0, "help" to 0)
    entries.values.forEach { counts[it.status] = (counts[it.status] ?: 0) + 1 }
    mine?.let { counts[it.status] = (counts[it.status] ?: 0) + 1 }
    val total = ids.size + 1
    val noNews = (ids - entries.keys).size + (if (mine == null) 1 else 0)

    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("$total kişi Dağ Mesh'te (sen dahil)", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                listOf(counts["ok"]!! to StatusOk, counts["hurt"]!! to StatusHurt, counts["help"]!! to StatusHelp, noNews to Color(0xFF4A5573))
                    .filter { it.first > 0 }
                    .forEach { (n, c) -> Box(Modifier.weight(n.toFloat()).fillMaxSize().background(c)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("● ${counts["ok"]} iyi", color = StatusOk, style = MaterialTheme.typography.bodySmall)
                Text("● ${counts["hurt"]} yaralı", color = StatusHurt, style = MaterialTheme.typography.bodySmall)
                Text("● ${counts["help"]} yardım", color = StatusHelp, style = MaterialTheme.typography.bodySmall)
                Text("○ $noNews haber yok", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            val needHelp = entries.values.filter { it.status == "help" || it.status == "hurt" }.sortedBy { if (it.status == "help") 0 else 1 }
            needHelp.forEach { e ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusDot(e.status, 10)
                    Text("${e.name} · ${FamilyManager.statusLabel(e.status).lowercase()} · ${agoText(e.at)}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ShareCard(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Share, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary) }
            Column(Modifier.weight(1f)) {
                Text("Uygulamayı yay", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "İnternetsiz yükle · her yeni telefon ağı büyütür",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Ana sayfa üst çubuğundaki turuncu "Afet" düğmesi. */
@Composable
fun AfetChip(onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(50), modifier = Modifier.height(40.dp)) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(SeismoIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
            Text("Afet", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(0.dp))
        }
    }
}
