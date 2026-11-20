package com.dagmesh.app.enkaz

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.content.ContextCompat

/** 16 kHz tek kanal mikrofon akışı. Arka planda sistem sessizleştirirse [silent] true olur. */
class MicStream(
    private val context: Context,
    private val onSamples: (FloatArray, Int) -> Unit,
) {
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile var silent = false
        private set
    @Volatile var samples = 0L
        private set

    fun hasPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        if (!hasPermission()) return false
        val rate = EnkazSignal.FS
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        // Ses işleme (gürültü bastırma, otomatik kazanç) düdüğü bozabilir: mümkünse işlenmemiş kaynak.
        val am = context.getSystemService(AudioManager::class.java)
        val unprocessed = am?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val sources = buildList {
            if (unprocessed) add(MediaRecorder.AudioSource.UNPROCESSED)
            add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            add(MediaRecorder.AudioSource.MIC)
        }
        var rec: AudioRecord? = null
        for (src in sources) {
            val r = try {
                AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 4, rate))
            } catch (e: Exception) { null }
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) { rec = r; break }
            r?.release()
        }
        val record = rec ?: return false
        try {
            record.startRecording()
        } catch (e: Exception) {
            record.release()
            return false
        }
        running = true
        thread = Thread({ loop(record) }, "enkaz-mic").apply { start() }
        return true
    }

    private fun loop(rec: AudioRecord) {
        val sh = ShortArray(1024)
        val fl = FloatArray(1024)
        var zeroRun = 0L
        try {
            while (running) {
                val n = rec.read(sh, 0, sh.size)
                if (n <= 0) { Thread.sleep(20); continue }
                var nonzero = false
                for (i in 0 until n) {
                    fl[i] = sh[i] / 32768f
                    if (sh[i].toInt() != 0) nonzero = true
                }
                zeroRun = if (nonzero) 0 else zeroRun + n
                silent = zeroRun > EnkazSignal.FS * 3L
                samples += n
                try { onSamples(fl, n) } catch (e: Exception) { Log.e("DagMesh", "Enkaz ses işleme", e) }
            }
        } catch (e: Exception) {
            Log.e("DagMesh", "Mikrofon", e)
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
    }

    fun stop() {
        running = false
        thread?.join(800)
        thread = null
    }
}

/** Düdüğü alarm ses seviyesinde çalar; sessiz moddayken de duyulur. */
class TonePlayer(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private var savedVolume = -1

    companion object {
        const val RATE = 48_000
        val beacon: ShortArray by lazy { EnkazSignal.toPcm16(EnkazSignal.pattern(RATE, up = true, amplitude = 0.92f)) }
        val query: ShortArray by lazy { EnkazSignal.toPcm16(EnkazSignal.pattern(RATE, up = false, amplitude = 0.92f)) }
    }

    fun boostVolume() {
        try {
            if (savedVolume < 0) savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (_: Exception) {}
    }

    fun restoreVolume() {
        try { if (savedVolume >= 0) am.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0) } catch (_: Exception) {}
        savedVolume = -1
    }

    /** Çalar ve süresini (ms) döner; çalınamazsa 0. Çalma bitince kendini kapatır. */
    fun play(pcm: ShortArray): Long {
        return try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            track.setVolume(1f)
            track.play()
            val ms = pcm.size * 1000L / RATE
            Thread {
                try { Thread.sleep(ms + 300) } catch (_: InterruptedException) {}
                try { track.stop() } catch (_: Exception) {}
                track.release()
            }.start()
            ms
        } catch (e: Exception) {
            Log.e("DagMesh", "Düdük çalınamadı", e)
            0L
        }
    }
}

/** Düdükten sonra "tık-tık-tık" titreşim; yüzeye değen telefonda enkazın içinden ilerler. */
object Buzz {
    fun vibrator(context: Context): Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
    }

    fun afterBeacon(context: Context) {
        val v = vibrator(context) ?: return
        val on = EnkazSignal.VIB_ON_MS
        val off = EnkazSignal.VIB_OFF_MS
        val timings = longArrayOf(EnkazSignal.VIB_START_MS, on, off, on, off, on)
        val amps = intArrayOf(0, 255, 0, 255, 0, 255)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        try {
            val effect = if (v.hasAmplitudeControl()) VibrationEffect.createWaveform(timings, amps, -1)
            else VibrationEffect.createWaveform(timings, -1)
            @Suppress("DEPRECATION") v.vibrate(effect, attrs)
        } catch (_: Exception) {}
    }

    fun cancel(context: Context) {
        try { vibrator(context)?.cancel() } catch (_: Exception) {}
    }
}
