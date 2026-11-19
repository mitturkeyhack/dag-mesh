package com.dagmesh.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dagmesh.app.App
import com.dagmesh.app.ai.AiBridge
import com.dagmesh.app.ai.AiClient
import com.dagmesh.app.ai.AiConfig
import com.dagmesh.app.data.ConversationRow
import com.dagmesh.app.data.previewText
import kotlinx.coroutines.launch

private val AiStart = Color(0xFF7C5CFF)
private val AiEnd = Color(0xFF2EC4B6)

/** Yapay zekânın yuvarlak simgesi (mor → turkuaz geçişli, yıldız işaretli). */
@Composable
fun AiAvatar(size: Dp = 46.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(AiStart, AiEnd))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
    }
}

/** Yapay zekâ bağlantısının kısa açıklaması. */
fun aiStatusText(mode: AiBridge.Mode): String = when (mode) {
    AiBridge.Mode.Direct -> "İnternet var · doğrudan soruyorsun"
    is AiBridge.Mode.ViaGateway ->
        "${mode.name} köprü oluyor" + if (mode.hops > 1) " · ${mode.hops - 1} telefon arada" else ""
    AiBridge.Mode.Waiting -> "İnternet ve köprü yok · sorular sırada bekler"
    AiBridge.Mode.NotConfigured -> "Ayarlanmadı · yakında köprü de yok"
}

@Composable
fun aiStatusColor(mode: AiBridge.Mode): Color = when (mode) {
    AiBridge.Mode.Direct -> MaterialTheme.colorScheme.secondary
    is AiBridge.Mode.ViaGateway -> MaterialTheme.colorScheme.primary
    AiBridge.Mode.Waiting, AiBridge.Mode.NotConfigured -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Sohbet listesinin en üstündeki yapay zekâ kartı. */
@Composable
fun AiEntryCard(row: ConversationRow?, onClick: () -> Unit) {
    val mode by App.instance.mesh.ai.mode.collectAsStateWithLifecycle()
    val thinking by App.instance.mesh.ai.thinking.collectAsStateWithLifecycle()
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
            AiAvatar()
            Column(Modifier.weight(1f)) {
                Text(AiBridge.NAME, style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        thinking.isNotEmpty() -> "düşünüyor…"
                        row != null -> (if (row.fromMe) "Sen: " else "") + previewText(row.kind, row.lastText, row.fileName)
                        else -> "Sor, internet olmasa da yakındaki köprüden cevap gelsin"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (thinking.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(aiStatusColor(mode)))
                    Text(
                        aiStatusText(mode),
                        style = MaterialTheme.typography.labelSmall,
                        color = aiStatusColor(mode),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (row != null && row.unread > 0) {
                Box(
                    Modifier
                        .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${row.unread}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

/** Yapay zekâ ayarları: adres, anahtar, model ve köprü paylaşımı. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AiSettingsSheet(onDismiss: () -> Unit) {
    val ai = App.instance.mesh.ai
    val saved by ai.config.collectAsStateWithLifecycle()
    val savedShare by ai.share.collectAsStateWithLifecycle()
    val internet by ai.internet.collectAsStateWithLifecycle()
    val served by ai.served.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf(saved.baseUrl) }
    var key by remember { mutableStateOf(saved.apiKey) }
    var model by remember { mutableStateOf(saved.model) }
    var share by remember { mutableStateOf(savedShare) }
    var showKey by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var noteOk by remember { mutableStateOf(false) }

    val draft = AiConfig(url, key, model)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AiAvatar(40.dp)
                Column {
                    Text("Yapay zekâ köprüsü", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (internet) "Bu telefonda internet var" else "Bu telefonda şu an internet yok",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (internet) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                "EVREN'den aldığın bilgileri gir. İnternetin varken sen doğrudan sorarsın; " +
                    "köprü açıksa internetsiz arkadaşların da senin telefonun üzerinden sorar. " +
                    "Ayar yapmadan da kullanabilirsin: yakında bir köprü varsa sorular ona gider.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Adres (OpenAI uyumlu)") },
                placeholder = { Text("https://…/v1") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = { Text("EVREN'in API bölümünde yazan adres") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("API anahtarı") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = if (showKey) "Anahtarı gizle" else "Anahtarı göster",
                        )
                    }
                },
                supportingText = { Text("Sadece bu telefonda saklanır, köprü olunca bile kimseye gitmez") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    enabled = !busy && url.isNotBlank() && key.isNotBlank() && internet,
                    onClick = {
                        busy = true
                        note = null
                        scope.launch {
                            val (list, error) = AiClient.listModels(draft)
                            models = list
                            busy = false
                            note = error ?: "${list.size} model bulundu, birini seç"
                            noteOk = error == null
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                ) { Text("Modelleri getir") }
            }
            if (models.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    models.forEach { id ->
                        FilterChip(selected = id == model, onClick = { model = id }, label = { Text(id, maxLines = 1) })
                    }
                }
            }

            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(18.dp)) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Başkalarına köprü ol", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "İnternetin varken yakındakiler senin üzerinden sorabilir. " +
                                "Kredi senden düşer; saatte en fazla ${AiBridge.HOURLY_LIMIT} soru." +
                                if (served > 0) " Bu açılışta $served soruya köprü oldun." else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = share, onCheckedChange = { share = it })
                }
            }

            note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (noteOk) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    enabled = !busy && draft.isReady && internet,
                    onClick = {
                        busy = true
                        note = null
                        scope.launch {
                            val result = AiClient.chat(draft, emptyList(), "Merhaba! Tek kısa cümleyle kendini tanıt.", maxTokens = 80)
                            busy = false
                            when (result) {
                                is AiClient.Result.Ok -> { note = "Çalışıyor: ${result.text}"; noteOk = true }
                                is AiClient.Result.Fail -> { note = result.reason; noteOk = false }
                            }
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                ) { Text("Dene") }
                Button(
                    enabled = !busy,
                    onClick = {
                        ai.save(draft, share)
                        onDismiss()
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                ) { Text("Kaydet") }
            }
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Bağlanılıyor…", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (saved.isReady) {
                TextButton(onClick = {
                    url = ""; key = ""; model = ""; models = emptyList(); note = null
                }) { Text("Bilgileri temizle", color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
