package com.dagmesh.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "messages", indices = [Index("peerId")])
data class MessageEntity(
    @PrimaryKey val id: String,
    val peerId: String,
    val fromMe: Boolean,
    val text: String,
    val timestamp: Long,
    /** Giden mesajlar için: 0 sırada bekliyor, 1 ağa verildi, 2 karşıya ulaştı. */
    val status: Int,
    /** Gelen mesaj kaç telefon üzerinden aktarılarak geldi (0 = doğrudan). */
    val hops: Int,
    val isRead: Boolean,
) {
    companion object {
        const val STATUS_PENDING = 0
        const val STATUS_SENT = 1
        const val STATUS_DELIVERED = 2
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
)
