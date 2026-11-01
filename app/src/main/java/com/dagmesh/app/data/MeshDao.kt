package com.dagmesh.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MeshDao {

    @Query("SELECT * FROM messages WHERE peerId = :peerId ORDER BY timestamp ASC")
    fun messages(peerId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun message(id: String): MessageEntity?

    /** Aynı mesaj ikinci kez gelirse yok sayılır; dönen -1 bunun işaretidir. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: MessageEntity): Long

    /** Durum sadece ileri gider (bekliyor → gönderildi → ulaştı). */
    @Query("UPDATE messages SET status = :status WHERE id = :id AND status < :status")
    suspend fun updateStatus(id: String, status: Int)

    @Query("UPDATE messages SET progress = :progress WHERE id = :id AND progress < 100")
    suspend fun updateProgress(id: String, progress: Int)

    @Query(
        """
        SELECT * FROM messages
        WHERE fromMe = 1 AND status < 2 AND kind != 'call' AND timestamp > :since
        ORDER BY timestamp ASC
        """
    )
    suspend fun unconfirmed(since: Long): List<MessageEntity>

    @Query("UPDATE messages SET isRead = 1 WHERE peerId = :peerId AND isRead = 0")
    suspend fun markRead(peerId: String)

    /** Okunmamış gelen mesajların kimlikleri (okundu bilgisi göndermek için). */
    @Query("SELECT id FROM messages WHERE peerId = :peerId AND fromMe = 0 AND isRead = 0 AND kind != 'call'")
    suspend fun unreadIncomingIds(peerId: String): List<String>

    /** Sadece kendi gönderdiğimiz mesajı "okundu" yapar. */
    @Query("UPDATE messages SET status = 3 WHERE id = :id AND fromMe = 1 AND status < 3")
    suspend fun markReadByPeer(id: String)

    @Query("UPDATE messages SET myReaction = :emoji WHERE id = :id")
    suspend fun setMyReaction(id: String, emoji: String?)

    @Query("UPDATE messages SET peerReaction = :emoji WHERE id = :id AND peerId = :peerId")
    suspend fun setPeerReaction(id: String, peerId: String, emoji: String?)

    /** SMS köprüsü gönderemediyse mesajı "gönderilemedi" olarak işaretle. */
    @Query("UPDATE messages SET progress = -1 WHERE id = :id")
    suspend fun markFailed(id: String)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: String)

    @Upsert
    suspend fun upsertPeer(peer: PeerEntity)

    @Query("SELECT * FROM peers WHERE id = :id")
    fun peer(id: String): Flow<PeerEntity?>

    @Query("SELECT * FROM peers WHERE id = :id")
    suspend fun peerNow(id: String): PeerEntity?

    @Query(
        """
        SELECT m.peerId AS peerId,
               COALESCE(p.name, m.peerId) AS name,
               m.text AS lastText,
               m.timestamp AS lastTime,
               m.fromMe AS fromMe,
               m.kind AS kind,
               m.fileName AS fileName,
               (SELECT COUNT(*) FROM messages u
                 WHERE u.peerId = m.peerId AND u.fromMe = 0 AND u.isRead = 0) AS unread
        FROM messages m
        LEFT JOIN peers p ON p.id = m.peerId
        WHERE m.timestamp = (SELECT MAX(x.timestamp) FROM messages x WHERE x.peerId = m.peerId)
        GROUP BY m.peerId
        ORDER BY m.timestamp DESC
        """
    )
    fun conversations(): Flow<List<ConversationRow>>
}
