package com.dagmesh.app.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.SettingsInputAntenna
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dagmesh.app.App
import com.dagmesh.app.bridge.SmsBridge
import kotlinx.coroutines.launch

/** Ana ekrandaki küçük köprü satırı: durum + dokununca köprü sayfası. */
@Composable
fun BridgeChip(onClick: () -> Unit) {
    val bridge = App.instance.mesh.bridge
    val enabled by bridge.enabled.collectAsStateWithLifecycle()
    val bridges by bridge.bridges.collectAsStateWithLifecycle()
    val best = bridges.values.minByOrNull { it.hops }
    val (text, active) = when {
        enabled -> "Şebeke köprüsü: bu telefon" to true
        best != null -> "Şebeke köprüsü: ${best.name} üzerinden" to true
        else -> "Şebeke köprüsü: yakında yok" to false
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Rounded.SettingsInputAntenna,
            contentDescription = null,
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Şebeke köprüsü sayfası: köprü ol / dışarıya SMS yaz. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BridgeSheet(onDismiss: () -> Unit, onOpenChat: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mesh = App.instance.mesh
    val bridge = mesh.bridge
    val enabled by bridge.enabled.collectAsStateWithLifecycle()
    val sentToday by bridge.sentToday.collectAsStateWithLifecycle()
    val bridges by bridge.bridges.collectAsStateWithLifecycle()
    var askNumber by remember { mutableStateOf(false) }

    val smsPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (SmsBridge.hasSmsPermissions(context)) {
            bridge.setEnabled(true)
        } else {
            Toast.makeText(
                context,
                "SMS izni verilmedi. İzin ekranı açılmıyorsa: Ayarlar → Uygulamalar → Dağ Mesh → ⋮ → Kısıtlanmış ayarlara izin ver",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.SettingsInputAntenna, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                Column {
                    Text("Şebeke köprüsü", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Şebekesi olan tek bir telefon, herkesin mesajını dışarıya SMS olarak iletir.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Bu telefon köprü olsun
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Bu telefon köprü olsun", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (enabled) "Bugün gönderilen: $sentToday / ${bridge.dailyLimit} SMS · SMS'ler senin hattından gider"
                            else "Şebeken varken aç. SMS'ler senin hattından gider, günde en fazla ${bridge.dailyLimit}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { on ->
                            if (!on) bridge.setEnabled(false)
                            else if (SmsBridge.hasSmsPermissions(context)) bridge.setEnabled(true)
                            else smsPermissions.launch(SmsBridge.PERMISSIONS)
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
                    )
                }
            }

            // Ulaşılabilir köprüler
            Text(
                "ULAŞILABİLİR KÖPRÜLER",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (bridges.isEmpty()) {
                Text(
                    if (enabled) "Köprü bu telefon. Diğerleri senin üzerinden SMS gönderebilir."
                    else "Şu an yakında köprü yok. Yazdığın SMS'ler sırada bekler, bir köprü menzile girince gider.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                bridges.values.sortedBy { it.hops }.forEach { b ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Avatar(b.userId, b.name, size = 36.dp)
                        Column(Modifier.weight(1f)) {
                            Text(b.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (b.hops <= 1) "Doğrudan bağlı" else "${b.hops - 1} telefon aracılığıyla",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondary)
                        )
                    }
                }
            }

            Button(
                onClick = { askNumber = true },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(top = 4.dp),
            ) {
                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Dışarıya SMS yaz")
            }
        }
    }

    if (askNumber) {
        var number by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askNumber = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = { Text("Kime yazacaksın?") },
            text = {
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it.filter { c -> c.isDigit() || c == '+' || c == ' ' }.take(20) },
                    label = { Text("Telefon numarası") },
                    placeholder = { Text("05xx xxx xx xx") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    shape = RoundedCornerShape(14.dp),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = number.count { it.isDigit() } >= 7,
                    onClick = {
                        askNumber = false
                        scope.launch {
                            val peerId = mesh.prepareSmsContact(number)
                            onDismiss()
                            onOpenChat(peerId)
                        }
                    },
                ) { Text("Sohbeti aç") }
            },
            dismissButton = { TextButton(onClick = { askNumber = false }) { Text("Vazgeç") } },
        )
    }
}
