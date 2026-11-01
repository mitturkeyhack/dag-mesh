package com.dagmesh.app.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dagmesh.app.App
import com.dagmesh.app.media.MediaFiles
import com.dagmesh.app.share.ApkShare
import com.dagmesh.app.share.Qr
import com.dagmesh.app.update.Updater
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/** "Uygulamayı paylaş" alt sayfası: QR ile ya da Quick Share/Bluetooth ile. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val share = App.instance.apkShare
    var showQr by remember { mutableStateOf(false) }
    val apkSize = remember { File(context.applicationInfo.sourceDir).length() }
    val version = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center,
                ) {
                    MountainLogo(Modifier.size(36.dp))
                }
                Column {
                    Text("Dağ Mesh'i paylaş", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "İnternet gerekmez · ${MediaFiles.formatSize(apkSize)}" + if (version.isNotBlank()) " · v$version" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            ShareOption(
                icon = Icons.Rounded.QrCode2,
                title = "QR kodla paylaş",
                subtitle = "Karşı telefon kamerayla okutup indirir. Onda uygulama ya da internet gerekmez.",
                badge = "Önerilen",
                highlighted = true,
            ) { showQr = true }
            ShareOption(
                icon = Icons.Rounded.Share,
                title = "Quick Share · Bluetooth",
                subtitle = "Telefonun kendi paylaşım menüsüyle doğrudan gönder.",
            ) {
                if (!share.shareViaSystem()) {
                    Toast.makeText(context, "Paylaşım menüsü açılamadı", Toast.LENGTH_SHORT).show()
                }
            }
            UpdateRow(version)
        }
    }

    if (showQr) {
        QrShareDialog(onClose = {
            showQr = false
            onDismiss()
        })
    }
}

@Composable
private fun ShareOption(
    icon: ImageVector,
    title: String,
    subtitle: String,
    badge: String? = null,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (highlighted) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (highlighted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.secondary,
                )
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (badge != null) {
                        Text(
                            badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.primary)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Tam ekran QR paylaşımı: 1) Wi‑Fi'a bağlan  2) İndir. */
@Composable
private fun QrShareDialog(onClose: () -> Unit) {
    val share = App.instance.apkShare
    val state by share.state.collectAsStateWithLifecycle()
    var step by remember { mutableIntStateOf(1) }

    LaunchedEffect(Unit) { share.startHotspot() }
    DisposableEffect(Unit) { onDispose { share.stopHotspot() } }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("QR ile paylaş", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Kapat") }
                }
                Spacer(Modifier.height(16.dp))

                when (val s = state) {
                    ApkShare.State.Idle, ApkShare.State.Starting -> Column(
                        Modifier.padding(top = 120.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.height(20.dp))
                        Text("Paylaşım için Wi‑Fi noktası açılıyor…", style = MaterialTheme.typography.bodyLarge)
                    }

                    is ApkShare.State.Failed -> Column(
                        Modifier.padding(top = 80.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(
                            Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(48.dp),
                        )
                        Text(s.message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                        Button(onClick = {
                            share.stopHotspot()
                            share.startHotspot()
                        }) { Text("Tekrar dene") }
                        OutlinedButton(onClick = { share.shareViaSystem() }) { Text("Quick Share ile gönder") }
                    }

                    is ApkShare.State.Ready -> {
                        StepTabs(step) { step = it }
                        Spacer(Modifier.height(20.dp))
                        val content = if (step == 1) Qr.wifi(s.ssid, s.password) else s.url
                        val qr = remember(content) { Qr.encode(content) }
                        Box(
                            Modifier
                                .size(280.dp)
                                .clip(RoundedCornerShape(28.dp))
                                .background(Color.White)
                                .padding(16.dp),
                        ) {
                            Image(qr, contentDescription = "QR kod", modifier = Modifier.fillMaxSize())
                        }
                        Spacer(Modifier.height(20.dp))
                        if (step == 1) {
                            Text(
                                "Karşı telefonun kamerasıyla bu kodu okut ve Wi‑Fi ağına bağlan.",
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(14.dp))
                            InfoRow("Ağ", s.ssid)
                            InfoRow("Şifre", s.password.ifBlank { "yok" })
                            Spacer(Modifier.height(20.dp))
                            Button(onClick = { step = 2 }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                                Text("Bağlandı, sonraki adım")
                            }
                        } else {
                            Text(
                                "Şimdi bu kodu okut. Açılan sayfadan uygulamayı indirip kursun.",
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(14.dp))
                            InfoRow("Adres", s.url)
                            Spacer(Modifier.height(14.dp))
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(Icons.Rounded.Download, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                                Text(
                                    if (s.downloads == 0) "Henüz indiren yok" else "${s.downloads} kez indirildi",
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                        Text(
                            "Paylaşım açıkken bu telefonun Wi‑Fi Aware kanalı kısa süre durabilir. Kapatınca kendiliğinden döner.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StepTabs(step: Int, onStep: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(4.dp),
    ) {
        listOf(1 to "Wi‑Fi'a bağlan", 2 to "İndir").forEach { (n, label) ->
            val selected = step == n
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { onStep(n) }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (n == 1) {
                        Icon(
                            Icons.Rounded.Wifi,
                            contentDescription = null,
                            tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp),
                        )
                    } else {
                        Text(
                            "$n",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(64.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
    }
}


/** Sürüm bilgisi + elle güncelleme kontrolü. */
@Composable
private fun UpdateRow(version: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by Updater.state.collectAsStateWithLifecycle()
    val status = when (val s = state) {
        Updater.State.Checking -> "Kontrol ediliyor…"
        Updater.State.UpToDate -> "En güncel sürümdesin"
        is Updater.State.Downloading -> "İndiriliyor %${s.percent}"
        is Updater.State.Installing -> "Kuruluyor…"
        is Updater.State.NeedsConfirm -> "Hazır · onay bekliyor"
        is Updater.State.Failed -> s.message
        Updater.State.Idle -> "İnternet bulunca kendiliğinden güncellenir"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Sürüm ${version.ifBlank { "?" }}", style = MaterialTheme.typography.labelLarge)
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state is Updater.State.NeedsConfirm) {
            TextButton(onClick = { Updater.confirm(context) }) { Text("Yükle") }
        } else {
            TextButton(
                enabled = state !is Updater.State.Checking && state !is Updater.State.Downloading && state !is Updater.State.Installing,
                onClick = { scope.launch { Updater.checkAndInstall(context.applicationContext, userInitiated = true) } },
            ) { Text("Denetle") }
        }
    }
}
