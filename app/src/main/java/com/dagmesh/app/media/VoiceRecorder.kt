package com.dagmesh.app.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Sesli mesaj kaydedici.
 * AAC, mono, 22 kHz, 32 kbit/sn: konuşma için net ve dakikası yalnızca ~240 KB.
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recorder != null

    fun start(): Boolean {
        if (recorder != null) return true
        val out = File(context.cacheDir, "kayit_${System.currentTimeMillis()}.m4a")
        val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioChannels(1)
            rec.setAudioSamplingRate(22_050)
            rec.setAudioEncodingBitRate(32_000)
            rec.setOutputFile(out.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            file = out
            startedAt = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            rec.release()
            out.delete()
            false
        }
    }

    /** Kaydı bitirir; çok kısa veya hatalıysa null döner. */
    fun stop(): Pair<File, Long>? {
        val rec = recorder ?: return null
        val out = file
        val duration = System.currentTimeMillis() - startedAt
        recorder = null
        file = null
        val ok = try {
            rec.stop()
            true
        } catch (e: Exception) {
            false
        } finally {
            rec.release()
        }
        if (!ok || out == null || duration < 700) {
            out?.delete()
            return null
        }
        return out to duration
    }

    fun cancel() {
        val rec = recorder ?: return
        recorder = null
        try {
            rec.stop()
        } catch (_: Exception) {
        }
        rec.release()
        file?.delete()
        file = null
    }
}
