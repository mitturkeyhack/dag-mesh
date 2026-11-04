package com.dagmesh.app.sos

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dagmesh.app.App
import com.dagmesh.app.MainActivity
import com.dagmesh.app.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Düşme algılama: serbest düşüş → sert çarpma → hareketsizlik görülünce
 * 30 sn geri sayımlı uyarı açar. Kullanıcı "İyiyim" demezse SOS kendiliğinden gider.
 * Uygulamanın arka plan hizmeti çalıştığı sürece (ekran kapalıyken de) çalışır.
 */
object FallDetector : SensorEventListener {

    const val COUNTDOWN_SEC = 30

    /** Geri sayım bitiş zamanı; null = uyarı yok. */
    private val _alertUntil = MutableStateFlow<Long?>(null)
    val alertUntil: StateFlow<Long?> = _alertUntil.asStateFlow()

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private var context: Context? = null
    private var listening = false
    private var countdown: Job? = null
    private var player: MediaPlayer? = null

    // Algılama durumu
    private var freeFallStart = 0L
    private var freeFallEnd = 0L
    private var impactAt = 0L
    private var lastTrigger = 0L
    private var stillSamples = 0
    private var movingSamples = 0

    fun init(ctx: Context) {
        context = ctx.applicationContext
        _enabled.value = prefs().getBoolean("enabled", true)
    }

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs().edit().putBoolean("enabled", on).apply()
        if (on) start() else stop()
    }

    private fun prefs() = context!!.getSharedPreferences("fall", Context.MODE_PRIVATE)

    /** Arka plan hizmeti açılınca çağrılır. */
    fun start() {
        val ctx = context ?: return
        if (listening || !_enabled.value) return
        val sm = ctx.getSystemService(SensorManager::class.java) ?: return
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        listening = sm.registerListener(this, acc, 20_000) // ~50 Hz
    }

    fun stop() {
        val ctx = context ?: return
        if (!listening) return
        ctx.getSystemService(SensorManager::class.java)?.unregisterListener(this)
        listening = false
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(e: SensorEvent) {
        val now = System.currentTimeMillis()
        if (_alertUntil.value != null || now - lastTrigger < 60_000) return
        val (x, y, z) = Triple(e.values[0], e.values[1], e.values[2])
        val g = sqrt(x * x + y * y + z * z)

        when {
            // 1) Serbest düşüş: ivme neredeyse sıfır
            impactAt == 0L && g < 3.5f -> {
                if (freeFallStart == 0L) freeFallStart = now
                freeFallEnd = now
            }
            // 2) Çarpma: düşüşten en geç 1 sn sonra 2,7 g üstü
            impactAt == 0L && g > 26f && freeFallStart != 0L &&
                freeFallEnd - freeFallStart >= 120 && now - freeFallEnd < 1000 -> {
                impactAt = now
                stillSamples = 0
                movingSamples = 0
            }
            // 3) Çarpmadan sonra 1,5–5 sn arası hareketsizlik
            impactAt != 0L -> {
                val dt = now - impactAt
                if (dt > 1500) {
                    if (kotlin.math.abs(g - 9.81f) < 1.6f) stillSamples++ else movingSamples++
                }
                if (dt > 5000) {
                    val still = stillSamples > 0 && stillSamples > movingSamples * 4
                    reset()
                    if (still) trigger()
                }
            }
            // Düşüş kısa kaldıysa (atlama, telefonu atma) sıfırla
            freeFallStart != 0L && now - freeFallEnd > 1000 -> reset()
        }
    }

    private fun reset() {
        freeFallStart = 0L
        freeFallEnd = 0L
        impactAt = 0L
    }

    /** Test için de kullanılabilir. */
    fun trigger() {
        val ctx = context ?: return
        val app = App.instance
        if (app.mesh.sos.outgoing.value != null) return // zaten SOS yayında
        lastTrigger = System.currentTimeMillis()
        val until = lastTrigger + COUNTDOWN_SEC * 1000L
        _alertUntil.value = until
        startAlarm(ctx)
        showNotification(ctx)
        countdown?.cancel()
        countdown = app.scope.launch {
            delay(COUNTDOWN_SEC * 1000L)
            if (_alertUntil.value != null) {
                finish()
                app.mesh.sos.start()
            }
        }
    }

    /** "İyiyim" — uyarıyı kapat, SOS gönderme. */
    fun cancel() {
        countdown?.cancel()
        finish()
    }

    /** Beklemeden hemen gönder. */
    fun sendNow() {
        countdown?.cancel()
        finish()
        App.instance.mesh.sos.start()
    }

    private fun finish() {
        _alertUntil.value = null
        stopAlarm()
        context?.let { NotificationManagerCompat.from(it).cancel(NOTIFY_ID) }
    }

    private fun startAlarm(ctx: Context) {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(ctx, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {}
        try {
            val v = ctx.getSystemService(Vibrator::class.java)
            val pattern = longArrayOf(0, 600, 400, 600, 400)
            if (Build.VERSION.SDK_INT >= 26) v?.vibrate(VibrationEffect.createWaveform(pattern, 0)) else @Suppress("DEPRECATION") v?.vibrate(pattern, 0)
        } catch (_: Exception) {}
    }

    private fun stopAlarm() {
        try { player?.stop(); player?.release() } catch (_: Exception) {}
        player = null
        try { context?.getSystemService(Vibrator::class.java)?.cancel() } catch (_: Exception) {}
    }

    private fun showNotification(ctx: Context) {
        val manager = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Düşme algılama", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setBypassDnd(true)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
            )
        }
        val open = PendingIntent.getActivity(
            ctx, NOTIFY_ID,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mesh)
            .setContentTitle("Düşme algılandı")
            .setContentText("$COUNTDOWN_SEC sn içinde iptal etmezsen SOS gönderilecek. İyiysen dokun.")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setOngoing(true)
            .setTimeoutAfter(COUNTDOWN_SEC * 1000L + 2000)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(NOTIFY_ID, n) } catch (_: SecurityException) {}
    }

    private const val CHANNEL = "fall"
    private const val NOTIFY_ID = 7301
}
