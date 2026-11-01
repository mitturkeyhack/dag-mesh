@file:OptIn(ExperimentalFoundationApi::class)

package com.dagmesh.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.PhoneMissed
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.dagmesh.app.data.MessageEntity
import com.dagmesh.app.media.MediaFiles
import com.dagmesh.app.media.VoicePlayer
import java.io.File

private val ReadBlue = Color(0xFF4FB3FF)

/** Hızlı tepki emojileri. */
private val QuickReactions = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

/**
 * Bir sohbet mesajı: metin, fotoğraf, video, sesli mesaj, dosya veya arama kaydı.
 * Basılı tutunca tepki emojileri ve Yanıtla / Kopyala / Sil menüsü açılır.
 */
@Composable
fun MessageBubble(
    message: MessageEntity,
    peerName: String,
    myId: String,
    onOpenImage: (String) -> Unit,
    onDelete: (MessageEntity) -> Unit,
    onReply: (MessageEntity) -> Unit,
    onReact: (MessageEntity, String) -> Unit,
    onQuoteClick: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val openMenu = { menuOpen = true }

    if (message.kind == MessageEntity.KIND_CALL) {
        Box(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                        onLongClick = openMenu,
                    )
            ) {
                CallLogChip(message)
                BubbleMenu(menuOpen, message, onDismiss = { menuOpen = false }, onCopy = null, onReply = null, onReact = null, onDelete = onDelete)
            }
        }
        return
    }
    val mine = message.fromMe
    val content = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            Box {
                Surface(
                    color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(
                        topStart = 20.dp,
                        topEnd = 20.dp,
                        bottomStart = if (mine) 20.dp else 6.dp,
                        bottomEnd = if (mine) 6.dp else 20.dp,
                    ),
                    modifier = Modifier
                        .widthIn(max = 290.dp)
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                            onLongClick = openMenu,
                        ),
                ) {
                    Column(Modifier.padding(if (message.kind == MessageEntity.KIND_IMAGE || message.kind == MessageEntity.KIND_VIDEO) 4.dp else 0.dp)) {
                        if (message.replyText != null) {
                            QuoteBlock(
                                author = if (message.replyAuthor == myId) "Sen" else peerName,
                                text = message.replyText,
                                content = content,
                                onClick = { message.replyToId?.let(onQuoteClick) },
                            )
                        }
                        when (message.kind) {
                            MessageEntity.KIND_IMAGE -> ImageContent(message, onOpenImage, openMenu)
                            MessageEntity.KIND_VIDEO -> VideoContent(message, openMenu)
                            MessageEntity.KIND_AUDIO -> AudioContent(message, content)
                            MessageEntity.KIND_FILE -> FileContent(message, content, openMenu)
                        }
                        Column(Modifier.padding(start = 12.dp, end = 10.dp, top = if (message.isMedia) 4.dp else 9.dp, bottom = 7.dp)) {
                            if (message.text.isNotBlank()) {
                                Text(message.text, style = MaterialTheme.typography.bodyLarge, color = content)
                            }
                            if (message.isMedia && message.progress < 100) {
                                TransferProgress(message, content)
                            }
                            MetaRow(message, content, Modifier.align(Alignment.End))
                        }
                    }
                }
                BubbleMenu(
                    expanded = menuOpen,
                    message = message,
                    onDismiss = { menuOpen = false },
                    onCopy = if (message.text.isNotBlank()) ({ clipboard.setText(AnnotatedString(message.text)) }) else null,
                    onReply = onReply,
                    onReact = onReact,
                    onDelete = onDelete,
                )
            }
            val reactions = listOfNotNull(message.peerReaction, message.myReaction)
            if (reactions.isNotEmpty()) {
                ReactionPill(
                    reactions = reactions,
                    modifier = Modifier
                        .offset(y = (-8).dp)
                        .padding(horizontal = 10.dp),
                    onClick = openMenu,
                )
            }
        }
    }
}

/** Yanıtlanan mesajın alıntısı (balonun üstünde, renkli çizgili kutu). */
@Composable
private fun QuoteBlock(author: String, text: String, content: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(start = 6.dp, end = 6.dp, top = 6.dp)
            .widthIn(min = 120.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(content.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .height(IntrinsicSize.Min),
    ) {
        Box(
            Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.secondary)
        )
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(
                author,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = content.copy(alpha = 0.8f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Balonun altındaki küçük emoji kapsülü. */
@Composable
private fun ReactionPill(reactions: List<String>, modifier: Modifier, onClick: () -> Unit) {
    val shown = reactions.distinct()
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(1.5.dp, MaterialTheme.colorScheme.background, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        shown.forEach { Text(it, fontSize = 14.sp) }
        if (reactions.size > shown.size) {
            Text("2", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BubbleMenu(
    expanded: Boolean,
    message: MessageEntity,
    onDismiss: () -> Unit,
    onCopy: (() -> Unit)?,
    onReply: ((MessageEntity) -> Unit)?,
    onReact: ((MessageEntity, String) -> Unit)?,
    onDelete: (MessageEntity) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(18.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        if (onReact != null) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                QuickReactions.forEach { emoji ->
                    val selected = message.myReaction == emoji
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.Transparent)
                            .clickable {
                                onDismiss()
                                onReact(message, emoji)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(emoji, fontSize = 22.sp)
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
        if (onReply != null) {
            DropdownMenuItem(
                text = { Text("Yanıtla") },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Reply, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onReply(message)
                },
            )
        }
        if (onCopy != null) {
            DropdownMenuItem(
                text = { Text("Kopyala") },
                leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                onClick = {
                    onCopy()
                    onDismiss()
                },
            )
        }
        DropdownMenuItem(
            text = { Text("Benden sil", color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            onClick = {
                onDismiss()
                onDelete(message)
            },
        )
    }
}

@Composable
private fun MetaRow(message: MessageEntity, content: Color, modifier: Modifier) {
    Row(
        modifier.padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (!message.fromMe && message.hops > 0) {
            Text(
                "${message.hops} aktarma",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        Text(formatTime(message.timestamp), style = MaterialTheme.typography.labelSmall, color = content.copy(alpha = 0.7f))
        if (message.fromMe) StatusIcon(message.status, content)
    }
}

@Composable
private fun TransferProgress(message: MessageEntity, content: Color) {
    Column(Modifier.width(200.dp).padding(top = 6.dp)) {
        LinearProgressIndicator(
            progress = { message.progress / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(50)),
            color = MaterialTheme.colorScheme.secondary,
            trackColor = content.copy(alpha = 0.15f),
        )
        Text(
            when {
                message.fromMe && message.status == MessageEntity.STATUS_PENDING && message.progress == 0 -> "Sırada · kişi menzile girince gidecek"
                message.fromMe -> "Gönderiliyor %${message.progress}"
                else -> "İndiriliyor %${message.progress}"
            },
            style = MaterialTheme.typography.labelSmall,
            color = content.copy(alpha = 0.75f),
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
private fun ImageContent(message: MessageEntity, onOpenImage: (String) -> Unit, onLongPress: () -> Unit) {
    val path = message.localPath
    val ready = message.fromMe || message.progress >= 100
    Box(
        Modifier
            .width(260.dp)
            .heightIn(min = 140.dp, max = 340.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.25f))
            .combinedClickable(enabled = ready && path != null, onLongClick = onLongPress) { path?.let(onOpenImage) },
        contentAlignment = Alignment.Center,
    ) {
        if (ready && path != null) {
            AsyncImage(
                model = File(path),
                contentDescription = "Fotoğraf",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            DownloadSpinner(message.progress)
        }
    }
}

@Composable
private fun VideoContent(message: MessageEntity, onLongPress: () -> Unit) {
    val context = LocalContext.current
    val path = message.localPath
    val ready = message.fromMe || message.progress >= 100
    Box(
        Modifier
            .width(260.dp)
            .heightIn(min = 150.dp, max = 300.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .combinedClickable(enabled = ready, onLongClick = onLongPress) { MediaFiles.open(context, path, message.mime) },
        contentAlignment = Alignment.Center,
    ) {
        if (ready && path != null) {
            AsyncImage(
                model = File(path),
                contentDescription = "Video",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth(),
            )
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Oynat", tint = Color.White, modifier = Modifier.size(34.dp))
            }
        } else {
            DownloadSpinner(message.progress)
        }
        Text(
            MediaFiles.formatSize(message.size),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun DownloadSpinner(progress: Int) {
    Box(contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { (progress / 100f).coerceAtLeast(0.02f) },
            color = MaterialTheme.colorScheme.secondary,
            trackColor = Color.White.copy(alpha = 0.15f),
            modifier = Modifier.size(52.dp),
        )
        Text("%$progress", style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

@Composable
private fun AudioContent(message: MessageEntity, content: Color) {
    val playingId by VoicePlayer.playingId.collectAsStateWithLifecycle()
    val playProgress by VoicePlayer.progress.collectAsStateWithLifecycle()
    val playing = playingId == message.id
    val ready = message.fromMe || message.progress >= 100
    Row(
        Modifier
            .width(240.dp)
            .padding(start = 8.dp, end = 12.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (ready) MaterialTheme.colorScheme.primary else content.copy(alpha = 0.2f))
                .clickable(enabled = ready) { VoicePlayer.toggle(message.id, message.localPath) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (playing) "Durdur" else "Dinle",
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Column(Modifier.weight(1f)) {
            LinearProgressIndicator(
                progress = { if (playing) playProgress else 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50)),
                color = MaterialTheme.colorScheme.secondary,
                trackColor = content.copy(alpha = 0.2f),
            )
            Text(
                MediaFiles.formatDuration(message.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = content.copy(alpha = 0.75f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun FileContent(message: MessageEntity, content: Color, onLongPress: () -> Unit) {
    val context = LocalContext.current
    val ready = message.fromMe || message.progress >= 100
    Row(
        Modifier
            .width(250.dp)
            .combinedClickable(enabled = ready, onLongClick = onLongPress) { MediaFiles.open(context, message.localPath, message.mime) }
            .padding(start = 10.dp, end = 12.dp, top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(content.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Description, contentDescription = null, tint = content)
        }
        Column(Modifier.weight(1f)) {
            Text(
                message.fileName ?: "Dosya",
                style = MaterialTheme.typography.bodyMedium,
                color = content,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                MediaFiles.formatSize(message.size) + (if (ready) " · açmak için dokun" else ""),
                style = MaterialTheme.typography.labelSmall,
                color = content.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun CallLogChip(message: MessageEntity) {
    val missed = message.text.contains("Cevapsız") || message.text.contains("Yanıtlanmayan") || message.text.contains("Reddedilen")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                if (missed) Icons.Rounded.PhoneMissed else Icons.Rounded.Call,
                contentDescription = null,
                tint = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                "${message.text} · ${formatTime(message.timestamp)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusIcon(status: Int, content: Color) {
    val dim = content.copy(alpha = 0.7f)
    val (icon, tint, label) = when (status) {
        MessageEntity.STATUS_READ -> Triple(Icons.Rounded.DoneAll, ReadBlue, "Okundu")
        MessageEntity.STATUS_DELIVERED -> Triple(Icons.Rounded.DoneAll, dim, "Ulaştı")
        MessageEntity.STATUS_SENT -> Triple(Icons.Rounded.Done, dim, "Gönderildi")
        else -> Triple(Icons.Rounded.Schedule, dim, "Sırada bekliyor")
    }
    Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(15.dp))
}

/** Fotoğrafı tam ekran gösterir. */
@Composable
fun ImageViewer(path: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = File(path),
                contentDescription = "Fotoğraf",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
