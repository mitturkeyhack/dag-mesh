package com.dagmesh.app.mesh

import android.util.Log
import com.dagmesh.app.data.MeshDao
import com.dagmesh.app.data.MessageEntity
import com.dagmesh.app.data.previewText
import com.dagmesh.app.media.MediaFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.io.File
import java.io.RandomAccessFile
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fotoğraf, video, sesli mesaj ve dosya aktarımı.
 *
 * - Dosya 30 KB'lık parçalara bölünür; her parça ikili paket olarak (base64'süz) gider.
 * - Aynı anda [WINDOW] parça yolda olur: hem hızlı hem bellek dostu.
 * - Alıcı eksik parçaları fark ederse sadece onları ister (NACK), dosyanın tamamı tekrar gitmez.
 * - Parçalar da normal paketler gibi zincirleme aktarılır, yani uzaktaki kişiye de ulaşır.
 * - Alıcı menzil dışındaysa dosya sırada bekler, bağlantı gelince tekrar gönderilir.
 */
class MediaTransfer(
    private val mesh: MeshManager,
    private val dao: MeshDao,
    private val scope: CoroutineScope,
) {
    private val sending: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val incoming = ConcurrentHashMap<String, Incoming>()

    /** Bir dosya gidiyor ya da geliyor mu? (Pil tasarrufu bu sırada taramayı kısmaz.) */
    val isBusy: Boolean get() = sending.isNotEmpty() || incoming.isNotEmpty()

    private class Incoming(
        val messageId: String,
        val from: String,
        val fromName: String,
        val meta: MediaMeta,
        file: File,
    ) {
        val received = BitSet(meta.chunks)
        var count = 0
        var lastPercent = 0
        var finished = false
        var nackJob: Job? = null
        val raf = RandomAccessFile(file, "rw")
    }

    // ---------------------------------------------------------------- gönderen taraf

    fun send(message: MessageEntity) {
        val path = message.localPath ?: return
        if (!sending.add(message.id)) return
        scope.launch(Dispatchers.IO) {
            try {
                sendAll(message, File(path))
            } catch (e: Exception) {
                Log.w(TAG, "Dosya gönderilemedi", e)
            } finally {
                sending.remove(message.id)
            }
        }
    }

    private suspend fun sendAll(message: MessageEntity, file: File) {
        if (!file.exists() || file.length() == 0L) return
        val meta = metaOf(message, file)
        if (!mesh.sendAwait(offerPacket(message, meta))) return

        // Alıcı dosyayı zaten almışsa (yeniden gönderim) hemen onay döner; boşuna göndermeyelim.
        delay(1_200)
        if (dao.message(message.id)?.status == MessageEntity.STATUS_DELIVERED) return

        val ok = sendChunks(message, meta, file, 0 until meta.chunks, trackProgress = true)
        if (ok) {
            dao.updateProgress(message.id, 100)
            dao.updateStatus(message.id, MessageEntity.STATUS_SENT)
        }
    }

    /** Parçaları sınırlı pencereyle gönderir. Bağlantı kalmazsa durur ve false döner. */
    private suspend fun sendChunks(
        message: MessageEntity,
        meta: MediaMeta,
        file: File,
        indices: Iterable<Int>,
        trackProgress: Boolean,
    ): Boolean {
        val window = Semaphore(WINDOW)
        val failed = AtomicBoolean(false)
        var lastPercent = -1
        var sent = 0
        RandomAccessFile(file, "r").use { raf ->
            coroutineScope {
                for (idx in indices) {
                    if (failed.get()) break
                    if (idx !in 0 until meta.chunks) continue
                    window.acquire()
                    val packet = chunkPacket(message, meta, idx, readChunk(raf, idx, meta.size))
                    launch {
                        try {
                            if (!mesh.sendAwait(packet)) failed.set(true)
                        } finally {
                            window.release()
                        }
                    }
                    sent++
                    if (trackProgress) {
                        val percent = (sent * 100L / meta.chunks).toInt().coerceAtMost(99)
                        if (percent >= lastPercent + 3) {
                            lastPercent = percent
                            dao.updateProgress(message.id, percent)
                        }
                    }
                }
            }
        }
        return !failed.get()
    }

    /** Alıcı eksik parçaları istedi: sadece onları yeniden gönder. */
    fun onNack(packet: Packet) {
        val messageId = packet.ref ?: return
        val indices = packet.text?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: return
        if (indices.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val message = dao.message(messageId) ?: return@launch
            if (!message.fromMe) return@launch
            val file = message.localPath?.let { File(it) }?.takeIf { it.exists() } ?: return@launch
            try {
                sendChunks(message, metaOf(message, file), file, indices, trackProgress = false)
            } catch (e: Exception) {
                Log.w(TAG, "Eksik parçalar gönderilemedi", e)
            }
        }
    }

    // ---------------------------------------------------------------- alan taraf

    /** Dosya teklifi geldi: mesajı listeye ekle, parçaları beklemeye başla. */
    suspend fun onOffer(packet: Packet) {
        val messageId = packet.ref ?: return
        val meta = packet.media ?: return
        val existing = dao.message(messageId)
        if (existing != null && existing.progress >= 100) {
            mesh.sendAck(packet.from, messageId)
            return
        }
        if (existing == null) dao.insertMessage(incomingMessage(packet, meta))
        stateFor(packet, meta)
    }

    suspend fun onChunk(packet: Packet) {
        val messageId = packet.ref ?: return
        val meta = packet.media ?: return
        val data = packet.data ?: return
        val state = incoming[messageId] ?: run {
            val existing = dao.message(messageId)
            if (existing != null && existing.progress >= 100) return
            if (existing == null) dao.insertMessage(incomingMessage(packet, meta))
            stateFor(packet, meta)
        }

        var finishedNow = false
        var percent = -1
        synchronized(state) {
            if (state.finished || packet.idx !in 0 until meta.chunks || state.received.get(packet.idx)) return
            state.raf.seek(packet.idx.toLong() * CHUNK_SIZE)
            state.raf.write(data)
            state.received.set(packet.idx)
            state.count++
            if (state.count >= meta.chunks) {
                state.finished = true
                finishedNow = true
                state.raf.close()
            } else {
                val p = (state.count * 100L / meta.chunks).toInt()
                if (p >= state.lastPercent + 3) {
                    state.lastPercent = p
                    percent = p
                }
            }
        }

        if (finishedNow) finish(state)
        else {
            if (percent >= 0) dao.updateProgress(messageId, percent)
            scheduleNack(state)
        }
    }

    private suspend fun finish(state: Incoming) {
        incoming.remove(state.messageId)
        state.nackJob?.cancel()
        dao.updateProgress(state.messageId, 100)
        mesh.sendAck(state.from, state.messageId)
        mesh.emitIncoming(state.from, state.fromName, previewText(state.meta.kind, "", state.meta.name))
    }

    /** Parça akışı 4 sn durursa eksikleri iste; birkaç kez dener, sonra yeniden gönderimi bekler. */
    private fun scheduleNack(state: Incoming) {
        state.nackJob?.cancel()
        state.nackJob = scope.launch {
            repeat(NACK_ATTEMPTS) {
                delay(NACK_DELAY_MS)
                val missing = synchronized(state) {
                    if (state.finished) return@launch
                    (0 until state.meta.chunks).filter { !state.received.get(it) }.take(NACK_MAX)
                }
                if (missing.isEmpty()) return@launch
                mesh.sendNack(state.from, state.messageId, missing)
            }
        }
    }

    private fun stateFor(packet: Packet, meta: MediaMeta): Incoming {
        val messageId = packet.ref!!
        return incoming.computeIfAbsent(messageId) {
            Incoming(messageId, packet.from, packet.fromName, meta, File(incomingPath(messageId, meta.name)))
        }
    }

    private fun incomingPath(messageId: String, name: String): String {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(60)
        return File(MediaFiles.dir(mesh.appContext), "${messageId.take(12)}_$safe").absolutePath
    }

    private fun incomingMessage(packet: Packet, meta: MediaMeta) = MessageEntity(
        id = packet.ref!!,
        peerId = packet.from,
        fromMe = false,
        text = if (packet.type == Packet.TYPE_MSG) packet.text.orEmpty() else "",
        timestamp = System.currentTimeMillis(),
        status = MessageEntity.STATUS_DELIVERED,
        hops = packet.hops,
        isRead = mesh.activeChatPeerId == packet.from,
        kind = meta.kind,
        fileName = meta.name,
        mime = meta.mime,
        size = meta.size,
        durationMs = meta.durationMs,
        progress = 0,
        localPath = incomingPath(packet.ref!!, meta.name),
    )

    // ---------------------------------------------------------------- paketler

    private fun metaOf(message: MessageEntity, file: File): MediaMeta {
        val size = file.length()
        return MediaMeta(
            kind = message.kind,
            name = (message.fileName ?: file.name).take(80),
            mime = message.mime ?: "application/octet-stream",
            size = size,
            chunks = ((size + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt(),
            durationMs = message.durationMs,
        )
    }

    private fun offerPacket(message: MessageEntity, meta: MediaMeta) =
        mesh.newPacket(Packet.TYPE_MSG, to = message.peerId, ref = message.id, text = message.text, media = meta)

    private fun chunkPacket(message: MessageEntity, meta: MediaMeta, idx: Int, bytes: ByteArray) =
        mesh.newPacket(Packet.TYPE_CHUNK, to = message.peerId, ref = message.id, media = meta, idx = idx, data = bytes)

    private fun readChunk(raf: RandomAccessFile, idx: Int, size: Long): ByteArray {
        val offset = idx.toLong() * CHUNK_SIZE
        val length = minOf(CHUNK_SIZE.toLong(), size - offset).toInt()
        val bytes = ByteArray(length)
        raf.seek(offset)
        raf.readFully(bytes)
        return bytes
    }

    companion object {
        private const val TAG = "DagMesh/Media"
        /** Nearby'nin 32 KB bayt sınırının altında kalacak şekilde, başlığa yer bırakarak. */
        const val CHUNK_SIZE = 30_000
        private const val WINDOW = 8
        private const val NACK_DELAY_MS = 4_000L
        private const val NACK_ATTEMPTS = 6
        private const val NACK_MAX = 300
    }
}
