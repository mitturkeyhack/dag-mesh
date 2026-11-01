package com.dagmesh.app.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Videoyu göndermeden önce 720p'ye sıkıştırır (WhatsApp'ın yaptığı gibi).
 * Telefonun donanım kodlayıcısını kullanır; 1 dk'lık video genelde birkaç saniyede hazırlanır.
 */
@OptIn(UnstableApi::class)
object VideoCompressor {
    private const val TAG = "DagMesh/Video"
    private const val TARGET_SHORT_SIDE = 720
    private const val TARGET_VIDEO_BITRATE = 1_500_000
    /** Bu değerin altındaki videolar zaten küçük; olduğu gibi gönderilir. */
    private const val SKIP_BELOW_BITRATE = 2_500_000

    /** Hazırlanan videonun yüzdesi (null = hazırlanan yok). Sohbet ekranında gösterilir. */
    private val _progress = MutableStateFlow<Int?>(null)
    val progress: StateFlow<Int?> = _progress.asStateFlow()

    private data class Info(val width: Int, val height: Int, val rotation: Int, val bitrate: Int)

    /**
     * Sıkıştırıp [out] dosyasına yazar.
     * @return true: sıkıştırıldı; false: gerek yok ya da başarısız (asıl dosya gönderilmeli).
     */
    suspend fun compress(context: Context, uri: Uri, out: File): Boolean {
        val info = readInfo(context, uri) ?: return false
        val displayW = if (info.rotation == 90 || info.rotation == 270) info.height else info.width
        val displayH = if (info.rotation == 90 || info.rotation == 270) info.width else info.height
        if (displayW <= 0 || displayH <= 0) return false
        if (min(displayW, displayH) <= TARGET_SHORT_SIDE && info.bitrate in 1 until SKIP_BELOW_BITRATE) return false

        // Kısa kenarı 720 piksele indir, en-boy oranını koru.
        val presentation = if (displayW >= displayH) {
            Presentation.createForHeight(TARGET_SHORT_SIDE)
        } else {
            val height = even(TARGET_SHORT_SIDE.toFloat() * displayH / displayW)
            Presentation.createForWidthAndHeight(TARGET_SHORT_SIDE, height, Presentation.LAYOUT_SCALE_TO_FIT)
        }

        _progress.value = 0
        return try {
            withContext(Dispatchers.Main) {
                val result = CompletableDeferred<Boolean>()
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(
                        DefaultEncoderFactory.Builder(context)
                            .setRequestedVideoEncoderSettings(
                                VideoEncoderSettings.Builder().setBitrate(TARGET_VIDEO_BITRATE).build()
                            )
                            .setEnableFallback(true)
                            .build()
                    )
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            result.complete(true)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            Log.w(TAG, "Video sıkıştırılamadı", exportException)
                            result.complete(false)
                        }
                    })
                    .build()

                val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                    .setEffects(Effects(emptyList(), listOf(presentation)))
                    .build()
                transformer.start(item, out.absolutePath)

                val poller = launch {
                    val holder = ProgressHolder()
                    while (isActive) {
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                            _progress.value = holder.progress
                        }
                        delay(250)
                    }
                }
                try {
                    result.await()
                } finally {
                    poller.cancel()
                    if (!result.isCompleted) transformer.cancel()
                }
            } && out.exists() && out.length() > 0
        } catch (e: Exception) {
            Log.w(TAG, "Video sıkıştırma hatası", e)
            false
        } finally {
            _progress.value = null
        }
    }

    private fun readInfo(context: Context, uri: Uri): Info? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            fun int(key: Int) = retriever.extractMetadata(key)?.toIntOrNull() ?: 0
            Info(
                width = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
                height = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
                rotation = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION),
                bitrate = int(MediaMetadataRetriever.METADATA_KEY_BITRATE),
            )
        } catch (e: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun even(value: Float): Int = ((value / 2f).roundToInt() * 2).coerceAtLeast(2)
}
