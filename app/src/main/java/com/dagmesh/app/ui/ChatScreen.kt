package com.dagmesh.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dagmesh.app.App
import com.dagmesh.app.media.MediaFiles
import com.dagmesh.app.media.VoicePlayer
import com.dagmesh.app.media.VoiceRecorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(peerId: String, onBack: () -> Unit) {
    val app = App.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { app.db.dao() }
    val messages by remember(peerId) { dao.messages(peerId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val stored by remember(peerId) { dao.peer(peerId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val peers by app.mesh.peers.collectAsStateWithLifecycle()
    val live = peers[peerId]
    val name = live?.name ?: stored?.name ?: "Bilinmeyen"

    val listState = rememberLazyListState()
    var input by rememberSaveable { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var viewerPath by remember { mutableStateOf<String?>(null) }

    val recorder = remember { VoiceRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    var recordStart by remember { mutableLongStateOf(0L) }
    var recordElapsed by remember { mutableLongStateOf(0L) }

    fun toast(text: String?) {
        if (text != null) Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- seçiciler ve izinler

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { toast(MediaFiles.sendImage(context, peerId, uri)) }
    }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { toast(MediaFiles.sendFile(context, peerId, uri, asVideo = true)) }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { toast(MediaFiles.sendFile(context, peerId, uri, asVideo = false)) }
    }

    fun hasMic() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    fun startRecording() {
        VoicePlayer.stop()
        if (recorder.start()) {
            recording = true
            recordStart = System.currentTimeMillis()
            recordElapsed = 0L
        } else {
            toast("Kayıt başlatılamadı")
        }
    }

    fun startCall() {
        toast(app.mesh.calls.call(peerId, name))
    }

    var pendingMicAction by remember { mutableStateOf<String?>(null) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            toast("Mikrofon izni olmadan sesli mesaj ve arama yapılamaz")
        } else when (pendingMicAction) {
            "record" -> startRecording()
            "call" -> startCall()
        }
        pendingMicAction = null
    }

    fun withMic(action: String, block: () -> Unit) {
        if (hasMic()) block() else {
            pendingMicAction = action
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // ---------------------------------------------------------------- yaşam döngüsü

    DisposableEffect(peerId) {
        app.mesh.activeChatPeerId = peerId
        onDispose {
            if (app.mesh.activeChatPeerId == peerId) app.mesh.activeChatPeerId = null
            recorder.cancel()
            VoicePlayer.stop()
        }
    }
    LaunchedEffect(messages.size) {
        dao.markRead(peerId)
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    LaunchedEffect(recording) {
        while (recording) {
            recordElapsed = System.currentTimeMillis() - recordStart
            if (recordElapsed > MAX_VOICE_MS) {
                recorder.stop()?.let { (file, duration) -> MediaFiles.sendVoice(context, peerId, file, duration) }
                recording = false
            }
            delay(200)
        }
    }

    fun sendText() {
        if (input.isBlank()) return
        app.mesh.sendText(peerId, input)
        input = ""
    }

    viewerPath?.let { path -> ImageViewer(path) { viewerPath = null } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Geri")
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Avatar(peerId, name, size = 38.dp, online = live?.isDirect == true)
                        Column {
                            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                peerStatus(live),
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    live == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                    live.isDirect -> MaterialTheme.colorScheme.secondary
                                    else -> MaterialTheme.colorScheme.primary
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (live?.isDirect == true) withMic("call") { startCall() }
                            else toast("Arama için kişinin doğrudan bağlı olması gerekir")
                        },
                    ) {
                        Icon(
                            Icons.Rounded.Call,
                            contentDescription = "Sesli ara",
                            tint = if (live?.isDirect == true) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    Modifier
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (recording) {
                        RecordingBar(
                            elapsedMs = recordElapsed,
                            onCancel = {
                                recorder.cancel()
                                recording = false
                            },
                            onSend = {
                                val result = recorder.stop()
                                recording = false
                                if (result == null) toast("Kayıt çok kısa")
                                else MediaFiles.sendVoice(context, peerId, result.first, result.second)
                            },
                        )
                    } else {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(
                                    Icons.Rounded.AddCircle,
                                    contentDescription = "Ek gönder",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(30.dp),
                                )
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                AttachItem(Icons.Rounded.Image, "Fotoğraf") {
                                    menuOpen = false
                                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }
                                AttachItem(Icons.Rounded.Videocam, "Video") {
                                    menuOpen = false
                                    pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                                }
                                AttachItem(Icons.Rounded.Description, "Dosya") {
                                    menuOpen = false
                                    pickFile.launch(arrayOf("*/*"))
                                }
                            }
                        }
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            placeholder = { Text("Mesaj yaz") },
                            shape = RoundedCornerShape(24.dp),
                            maxLines = 5,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = Color.Transparent,
                                focusedBorderColor = MaterialTheme.colorScheme.outline,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        val textMode = input.isNotBlank()
                        FilledIconButton(
                            onClick = { if (textMode) sendText() else withMic("record") { startRecording() } },
                            modifier = Modifier.size(52.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) {
                            Icon(
                                if (textMode) Icons.AutoMirrored.Rounded.Send else Icons.Rounded.Mic,
                                contentDescription = if (textMode) "Gönder" else "Sesli mesaj kaydet",
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (messages.isEmpty()) {
                item(key = "hint") {
                    Text(
                        "Mesajlar internetsiz, telefondan telefona gider.\n" +
                            "Fotoğraf, video, dosya ve sesli mesaj gönderebilir; doğrudan bağlıyken sesli arama yapabilirsin.\n" +
                            "Kişi menzil dışındaysa gönderdiklerin sırada bekler, yaklaştığında otomatik gider.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 40.dp),
                    )
                }
            }
            items(messages, key = { it.id }) { message ->
                MessageBubble(message, onOpenImage = { viewerPath = it })
            }
        }
    }
}

@Composable
private fun AttachItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) },
        onClick = onClick,
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.RecordingBar(
    elapsedMs: Long,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "rec")
    val blink by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "blink",
    )
    IconButton(onClick = onCancel) {
        Icon(Icons.Rounded.Delete, contentDescription = "Kaydı iptal et", tint = MaterialTheme.colorScheme.error)
    }
    Row(
        Modifier
            .weight(1f)
            .height(52.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(10.dp)
                .alpha(blink)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error)
        )
        Text(MediaFiles.formatDuration(elapsedMs), style = MaterialTheme.typography.titleMedium)
        Text(
            "Kaydediliyor…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    FilledIconButton(
        onClick = onSend,
        modifier = Modifier.size(52.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Sesli mesajı gönder")
    }
}

/** Sesli mesaj en fazla 5 dakika. */
private const val MAX_VOICE_MS = 5 * 60 * 1000L
