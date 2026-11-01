package com.dagmesh.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "messages", indices = [Index("peerId")])
data class MessageEntity(
    @PrimaryKey val id: String,
    val peerId: String,
    val fromMe: Boolean,
    /** Metin mesajında metnin kendisi, medyada açıklama (boş olabilir), aramada arama özeti. */
    val text: String,
    val timestamp: Long,
    /** Giden mesajlar için: 0 sırada bekliyor, 1 ağa verildi, 2 karşıya ulaştı. */
    val status: Int,
    /** Gelen mesaj kaç telefon üzerinden aktarılarak geldi (0 = doğrudan). */
    val hops: Int,
    val isRead: Boolean,
    /** text, image, video, audio, file, call */
    val kind: String = KIND_TEXT,
    val fileName: String? = null,
    val mime: String? = null,
    val size: Long = 0,
    val durationMs: Long = 0,
    /** Dosya aktarımı yüzdesi (gönderirken yükleme, alırken indirme). 100 = tamam. */
    val progress: Int = 100,
    val localPath: String? = null,
) {
    val isMedia: Boolean get() = kind != KIND_TEXT && kind != KIND_CALL

    companion object {
        const val STATUS_PENDING = 0
        const val STATUS_SENT = 1
        const val STATUS_DELIVERED = 2

        const val KIND_TEXT = "text"
        const val KIND_IMAGE = "image"
        const val KIND_VIDEO = "video"
        const val KIND_AUDIO = "audio"
        const val KIND_FILE = "file"
        const val KIND_CALL = "call"
    }
}

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val lastSeen: Long,
)

/** Sohbet listesindeki bir satır. */
data class ConversationRow(
    val peerId: String,
    val name: String,
    val lastText: String,
    val lastTime: Long,
    val fromMe: Boolean,
    val unread: Int,
    val kind: String,
    val fileName: String?,
)

/** Listelerde ve bildirimlerde mesajın kısa önizlemesi. */
fun previewText(kind: String, text: String, fileName: String?): String = when (kind) {
    MessageEntity.KIND_IMAGE -> "📷 Fotoğraf" + text.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
    MessageEntity.KIND_VIDEO -> "🎬 Video" + text.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
    MessageEntity.KIND_AUDIO -> "🎤 Sesli mesaj"
    MessageEntity.KIND_FILE -> "📎 " + (fileName ?: "Dosya")
    MessageEntity.KIND_CALL -> "📞 $text"
    else -> text
}
