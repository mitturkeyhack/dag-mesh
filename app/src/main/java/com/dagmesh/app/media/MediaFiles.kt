package com.dagmesh.app.media

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.dagmesh.app.App
import com.dagmesh.app.data.MessageEntity
import com.dagmesh.app.mesh.Packet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Gönderilecek dosyaları hazırlar (kopyalama, fotoğraf sıkıştırma) ve alınanları açar. */
object MediaFiles {

    /** Tek dosya sınırı: dağda zincirleme aktarımda daha büyükleri pratik değil. */
    const val MAX_FILE_BYTES = 64L * 1024 * 1024
    private const val MAX_IMAGE_SIDE = 1600
    private const val JPEG_QUALITY = 82

    fun dir(context: Context): File = File(context.filesDir, "media").apply { mkdirs() }

    /** Fotoğrafı küçültüp JPEG olarak gönderir. Başarısızsa hata mesajı döner. */
    suspend fun sendImage(context: Context, peerId: String, uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val id = Packet.newId()
            val out = File(dir(context), "${id.take(12)}.jpg")
            compressImage(context, uri, out)
            App.instance.mesh.sendMedia(id, peerId, MessageEntity.KIND_IMAGE, out, "Foto_${stamp()}.jpg", "image/jpeg")
            null
        } catch (e: Exception) {
            "Fotoğraf hazırlanamadı"
        }
    }

    /** Video veya herhangi bir dosyayı olduğu gibi kopyalayıp gönderir. */
    suspend fun sendFile(context: Context, peerId: String, uri: Uri, asVideo: Boolean): String? = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val (name, size) = queryNameAndSize(context, uri)
            val mime = resolver.getType(uri)
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', ""))
                ?: "application/octet-stream"
            val kind = when {
                asVideo || mime.startsWith("video/") -> MessageEntity.KIND_VIDEO
                mime.startsWith("image/") -> MessageEntity.KIND_IMAGE
                mime.startsWith("audio/") -> MessageEntity.KIND_FILE
                else -> MessageEntity.KIND_FILE
            }

            // Videoyu önce 720p'ye sıkıştır: 10 kata kadar daha küçük, o kadar hızlı gider.
            if (kind == MessageEntity.KIND_VIDEO) {
                val id = Packet.newId()
                val compressed = File(dir(context), "${id.take(12)}.mp4")
                if (VideoCompressor.compress(context, uri, compressed) && compressed.length() <= MAX_FILE_BYTES) {
                    App.instance.mesh.sendMedia(
                        id, peerId, MessageEntity.KIND_VIDEO, compressed,
                        name.substringBeforeLast('.') + ".mp4", "video/mp4",
                    )
                    return@withContext null
                }
                compressed.delete()
            }

            if (size > MAX_FILE_BYTES) return@withContext "Dosya çok büyük (en fazla 64 MB)"
            if (kind == MessageEntity.KIND_IMAGE && !mime.contains("gif")) {
                return@withContext sendImageInternal(context, peerId, uri)
            }
            val id = Packet.newId()
            val ext = name.substringAfterLast('.', "").takeIf { it.isNotBlank() && it.length <= 5 }?.let { ".$it" } ?: ""
            val out = File(dir(context), "${id.take(12)}$ext")
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output, 64 * 1024) }
            } ?: return@withContext "Dosya okunamadı"
            if (out.length() == 0L) return@withContext "Dosya boş"
            if (out.length() > MAX_FILE_BYTES) {
                out.delete()
                return@withContext "Dosya çok büyük (en fazla 64 MB)"
            }
            App.instance.mesh.sendMedia(id, peerId, kind, out, name, mime)
            null
        } catch (e: Exception) {
            "Dosya hazırlanamadı"
        }
    }

    private suspend fun sendImageInternal(context: Context, peerId: String, uri: Uri): String? = sendImage(context, peerId, uri)

    /** Kaydedilen sesli mesajı medya klasörüne taşıyıp gönderir. */
    fun sendVoice(context: Context, peerId: String, recorded: File, durationMs: Long) {
        val id = Packet.newId()
        val out = File(dir(context), "${id.take(12)}.m4a")
        if (!recorded.renameTo(out)) {
            recorded.copyTo(out, overwrite = true)
            recorded.delete()
        }
        App.instance.mesh.sendMedia(
            id, peerId, MessageEntity.KIND_AUDIO, out, "Ses_${stamp()}.m4a", "audio/mp4", durationMs,
        )
    }

    /** Alınan/gönderilen dosyayı telefondaki uygun uygulamayla açar. */
    fun open(context: Context, path: String?, mime: String?) {
        val file = path?.let { File(it) }?.takeIf { it.exists() } ?: run {
            Toast.makeText(context, "Dosya henüz hazır değil", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "Bu dosyayı açacak uygulama yok", Toast.LENGTH_SHORT).show()
        }
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    fun formatDuration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(total / 60, total % 60)
    }

    // ---------------------------------------------------------------- yardımcılar

    private fun compressImage(context: Context, uri: Uri, out: File) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_IMAGE_SIDE || bounds.outHeight / (sample * 2) >= MAX_IMAGE_SIDE) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        var bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalStateException("Fotoğraf çözülemedi")

        // Telefonla çekilen fotoğrafların yönünü düzelt.
        val rotation = try {
            resolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (e: Exception) {
            0f
        }

        val scale = MAX_IMAGE_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height)
        if (scale < 1f || rotation != 0f) {
            val matrix = Matrix().apply {
                if (scale < 1f) postScale(scale, scale)
                if (rotation != 0f) postRotate(rotation)
            }
            val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (transformed !== bitmap) bitmap.recycle()
            bitmap = transformed
        }
        FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        bitmap.recycle()
    }

    private fun queryNameAndSize(context: Context, uri: Uri): Pair<String, Long> {
        var name = "dosya"
        var size = 0L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                }
            }
        return name to size
    }

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
}
