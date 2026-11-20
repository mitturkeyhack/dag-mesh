package com.dagmesh.app.ui

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dagmesh.app.App
import com.dagmesh.app.family.FamilyManager
import com.dagmesh.app.share.Qr
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ailem: durum kartları + aile sohbeti. Aile yoksa kurma / katılma ekranı. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyScreen(onBack: () -> Unit) {
    val mesh = App.instance.mesh
    val family = mesh.family
    val info by family.info.collectAsStateWithLifecycle()
    val members by family.members.collectAsStateWithLifecycle()
    val messages by family.messages.collectAsStateWithLifecycle()
    var showCode by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        family.screenOpen = true
        family.markRead()
        mesh.activeChatPeerId = FamilyManager.NOTIFY_PEER
        onDispose {
            family.screenOpen = false
            if (mesh.activeChatPeerId == FamilyManager.NOTIFY_PEER) mesh.activeChatPeerId = null
        }
    }

    val current = info
    if (showCode && current != null) CodeDialog(current, onDismiss = { showCode = false }, onLeft = { showCode = false })

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Ailem", fontWeight = FontWeight.SemiBold)
                        if (current != null) {
                            Text(
                                "🔒 Uçtan uca şifreli · ${members.size} kişi",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Geri") }
                },
                actions = {
                    if (current != null) {
                        OutlinedButton(onClick = { showCode = true }, shape = RoundedCornerShape(50), modifier = Modifier.padding(end = 8.dp)) {
                            Text(current.display, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = { if (current != null) FamilyInput { family.sendText(it) } },
    ) { padding ->
        if (current == null) {
            FamilySetup(Modifier.padding(padding))
        } else {
            val listState = rememberLazyListState()
            val shown = messages.filter { it.kind != FamilyManager.KIND_STATUS || it.status != null }
            LaunchedEffect(shown.size) {
                family.markRead()
                if (shown.isNotEmpty()) listState.animateScrollToItem(shown.size + 1)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "members") { MemberGrid(members) }
                item(key = "spacer") { Spacer(Modifier.height(6.dp)) }
                items(shown, key = { it.id }) { FamilyMessageRow(it) }
            }
        }
    }
}

@Composable
private fun MemberGrid(members: List<FamilyManager.Member>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        members.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { m ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatusDot(m.status, 10)
                                Text(if (m.isMe) "Sen" else m.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            }
                            Text(
                                if (m.status != null) "${FamilyManager.statusLabel(m.status)} · ${agoText(m.statusAt)}"
                                else "Haber yok · ${agoText(m.lastSeen)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (m.status == null && !m.isMe) StatusHurt else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                if (m.isMe) "Bu telefon" else viaText(m.via),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale("tr"))

@Composable
private fun FamilyMessageRow(m: FamilyManager.Message) {
    when (m.kind) {
        FamilyManager.KIND_JOIN -> Text(
            "${if (m.mine) "Sen" else m.name} aileye katıldı · ${timeFormat.format(Date(m.ts))}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        FamilyManager.KIND_STATUS -> Text(
            "${if (m.mine) "Sen" else m.name}: \"${FamilyManager.statusLabel(m.status)}\" · ${timeFormat.format(Date(m.ts))}" +
                (if (m.lat != null) " · konum eklendi" else ""),
            style = MaterialTheme.typography.bodyMedium,
            color = statusColor(m.status),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        else -> Box(Modifier.fillMaxWidth(), contentAlignment = if (m.mine) Alignment.CenterEnd else Alignment.CenterStart) {
            Surface(
                color = if (m.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(
                    topStart = 18.dp, topEnd = 18.dp,
                    bottomStart = if (m.mine) 18.dp else 6.dp, bottomEnd = if (m.mine) 6.dp else 18.dp,
                ),
                modifier = Modifier.widthIn(max = 300.dp),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (!m.mine) Text(m.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                    Text(m.text, style = MaterialTheme.typography.bodyLarge)
                    val tail = when {
                        m.mine && m.synced -> "✓✓ sunucuda"
                        m.mine -> "sırada · yakındakilere gitti"
                        else -> viaText(m.via)
                    }
                    Text(
                        "${timeFormat.format(Date(m.ts))} · $tail",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.End),
                    )
                }
            }
        }
    }
}

@Composable
private fun FamilyInput(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Aileye yaz") },
                shape = RoundedCornerShape(26.dp),
                maxLines = 4,
                modifier = Modifier.weight(1f),
            )
            FilledIconButton(
                onClick = { if (text.isNotBlank()) { onSend(text); text = "" } },
                modifier = Modifier.size(52.dp),
            ) { Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Gönder") }
        }
    }
}

@Composable
private fun FamilySetup(modifier: Modifier) {
    val family = App.instance.mesh.family
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(40.dp))
        Text("Aileni bir koda bağla", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Bir kişi aile kurar, çıkan kodu diğerlerine verir. Deprem gibi bir durumda \"Güvendeyim\" ve mesajlar " +
                "yakındaki aileye internetsiz, uzaktaki aileye internet ya da köprü üzerinden gider.\n\n" +
                "Mesajları sadece aile okuyabilir: kod telefondan çıkmaz, sunucu bile içeriği göremez. " +
                "Bunu afetten önce yapın.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            enabled = !busy,
            onClick = {
                busy = true; error = null
                scope.launch { family.create(); busy = false }
            },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Yeni aile kur") }

        Text("ya da aile koduyla katıl", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = code,
            onValueChange = { code = it; error = null },
            label = { Text("Aile kodu") },
            placeholder = { Text("DAĞ-XXXX-XXXX-XXXX") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            isError = error != null,
            supportingText = { error?.let { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            enabled = !busy && code.isNotBlank(),
            onClick = {
                busy = true; error = null
                scope.launch {
                    if (!family.join(code)) error = "Kod geçersiz. 12 harf ve rakamdan oluşur, örnek: DAĞ-7K4M-92QX-ABCD"
                    busy = false
                }
            },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Katıl") }
        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Aile anahtarı hazırlanıyor… (birkaç saniye)", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CodeDialog(info: FamilyManager.Info, onDismiss: () -> Unit, onLeft: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val qr = remember(info.code) { Qr.encode(info.display, 600) }
    var confirmLeave by remember { mutableStateOf(false) }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Aileden ayrıl?") },
            text = { Text("Aile mesajları bu telefondan silinir. Tekrar katılmak için kodu bilmen gerekir.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { App.instance.mesh.family.leave() }
                    confirmLeave = false
                    onLeft()
                }) { Text("Ayrıl", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Vazgeç") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aile kodu") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(qr, contentDescription = "Aile kodu QR", modifier = Modifier.size(200.dp))
                Text(
                    info.display,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    "Bu kodu sadece aile üyelerinle paylaş. Kodu bilen herkes aile mesajlarını okuyabilir.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = { confirmLeave = true }) { Text("Aileden ayrıl", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "Dağ Mesh aile kodumuz: ${info.display}\nUygulamada Afet → Ailem → Katıl kısmına yaz.")
                context.startActivity(Intent.createChooser(send, "Aile kodunu paylaş"))
            }) { Text("Paylaş") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Kapat") } },
    )
}

/** Sohbetler sekmesinde Yapay Zekâ kartının altındaki "Ailem" kartı. */
@Composable
fun FamilyEntryCard(onClick: () -> Unit) {
    val family = App.instance.mesh.family
    val info by family.info.collectAsStateWithLifecycle()
    val members by family.members.collectAsStateWithLifecycle()
    val unread by family.unread.collectAsStateWithLifecycle()
    val others = members.filter { !it.isMe }
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (others.isEmpty()) {
                    Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    }
                } else {
                    others.take(4).forEach { m ->
                        Box(contentAlignment = Alignment.BottomEnd) {
                            Avatar(m.id, m.name, size = 38.dp)
                            StatusDot(m.status, 12)
                        }
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text("Ailem", style = MaterialTheme.typography.titleMedium)
                val ok = others.count { it.status == "ok" }
                val noNews = others.count { it.status == null }
                Text(
                    when {
                        info == null -> "Aileni kur: afette birbirinizden haber alın"
                        others.isEmpty() -> "Aile kodunu ailenle paylaş"
                        else -> "$ok kişi güvende" + if (noNews > 0) " · $noNews kişiden haber yok" else ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (unread > 0) {
                Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(50)) {
                    Text(
                        "$unread",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}
