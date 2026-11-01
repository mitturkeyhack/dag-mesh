package com.dagmesh.app.call

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Gerçek zamanlı arama sesi.
 * - 16 kHz mono ses, 40 ms'lik kareler (saniyede 25 kare).
 * - Yankı engelleme, gürültü azaltma ve otomatik ses seviyesi (telefon destekliyorsa).
 * - Gelen kareler küçük bir tamponda tutulur; tampon dolarsa en eski kare atılır,
 *   böylece gecikme birikmez.
 */
class CallAudio(
    private val context: Context,
    private val onFrame: (ByteArray) -> Unit,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val playQueue = LinkedBlockingQueue<ShortArray>(MAX_QUEUE)

    @Volatile
    private var running = false

    @Volatile
    var muted = false

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var captureThread: Thread? = null
    private var playThread: Thread? = null
    private val effects = mutableListOf<android.media.audiofx.AudioEffect>()

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        val minRecord = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minRecord, FRAME_SAMPLES * 2 * 4),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Mikrofon açılamadı", e)
            audioManager.mode = AudioManager.MODE_NORMAL
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            audioManager.mode = AudioManager.MODE_NORMAL
            return false
        }
        enableEffects(rec.audioSessionId)

        val minPlay = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val player = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minPlay, FRAME_SAMPLES * 2 * 3))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        record = rec
        track = player
        running = true
        player.play()
        rec.startRecording()

        captureThread = Thread({ captureLoop(rec) }, "arama-mikrofon").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        playThread = Thread({ playLoop(player) }, "arama-hoparlor").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return true
    }

    private fun captureLoop(rec: AudioRecord) {
        val buffer = ShortArray(FRAME_SAMPLES)
        val state = ImaAdpcm.State()
        while (running) {
            val n = rec.read(buffer, 0, FRAME_SAMPLES)
            if (n <= 0) continue
            if (muted) buffer.fill(0, 0, n)
            try {
                onFrame(ImaAdpcm.encode(buffer, n, state))
            } catch (e: Exception) {
                Log.w(TAG, "Ses karesi gönderilemedi", e)
            }
        }
    }

    private fun playLoop(player: AudioTrack) {
        while (running) {
            val frame = try {
                playQueue.poll(200, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                null
            } ?: continue
            player.write(frame, 0, frame.size)
        }
    }

    /** Karşı taraftan gelen ses karesi. */
    fun play(encoded: ByteArray) {
        if (!running) return
        val pcm = ImaAdpcm.decode(encoded)
        if (pcm.isEmpty()) return
        if (!playQueue.offer(pcm)) {
            playQueue.poll()
            playQueue.offer(pcm)
        }
    }

    fun setSpeaker(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 31) {
            if (on) {
                audioManager.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { audioManager.setCommunicationDevice(it) }
            } else {
                audioManager.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = on
        }
    }

    fun stop() {
        if (!running) return
        running = false
        captureThread?.join(500)
        playThread?.join(500)
        captureThread = null
        playThread = null
        record?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        track?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        record = null
        track = null
        effects.forEach { it.release() }
        effects.clear()
        playQueue.clear()
        setSpeaker(false)
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    private fun enableEffects(sessionId: Int) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(sessionId)?.let { it.enabled = true; effects.add(it) }
            }
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(sessionId)?.let { it.enabled = true; effects.add(it) }
            }
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(sessionId)?.let { it.enabled = true; effects.add(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ses efektleri açılamadı", e)
        }
    }

    companion object {
        private const val TAG = "DagMesh/CallAudio"
        const val RATE = 16_000
        /** 40 ms */
        const val FRAME_SAMPLES = 640
        /** En fazla ~400 ms ses tamponlanır. */
        private const val MAX_QUEUE = 10
    }
}
