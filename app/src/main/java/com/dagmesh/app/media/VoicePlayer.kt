package com.dagmesh.app.media

import android.media.MediaPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Sohbetteki sesli mesajları çalar. Aynı anda tek bir mesaj çalar. */
object VoicePlayer {
    private val scope = MainScope()
    private var player: MediaPlayer? = null
    private var ticker: Job? = null

    private val _playingId = MutableStateFlow<String?>(null)
    val playingId: StateFlow<String?> = _playingId.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    fun toggle(messageId: String, path: String?) {
        if (_playingId.value == messageId) {
            stop()
            return
        }
        stop()
        if (path == null) return
        val mp = MediaPlayer()
        try {
            mp.setDataSource(path)
            mp.setOnCompletionListener { stop() }
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            mp.release()
            return
        }
        player = mp
        _playingId.value = messageId
        ticker = scope.launch {
            while (isActive) {
                val p = player ?: break
                val duration = p.duration.coerceAtLeast(1)
                _progress.value = (p.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
                delay(80)
            }
        }
    }

    fun stop() {
        ticker?.cancel()
        ticker = null
        player?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        player = null
        _playingId.value = null
        _progress.value = 0f
    }
}
