package com.dagmesh.app.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dagmesh.app.MainActivity
import com.dagmesh.app.R

object Notifications {
    private const val CHANNEL_STATUS = "mesh_status"
    private const val CHANNEL_MESSAGES = "messages"
    private const val CHANNEL_CALLS = "calls"
    const val STATUS_ID = 1
    private const val CALL_ID = 2

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Ağ durumu", NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "Mesajlar", NotificationManager.IMPORTANCE_HIGH)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CALLS, "Aramalar", NotificationManager.IMPORTANCE_HIGH).apply {
                // Zil sesini uygulama kendisi çalıyor; bildirim ayrıca ses çıkarmasın.
                setSound(null, null)
                enableVibration(true)
            }
        )
    }

    fun status(context: Context, direct: Int, total: Int): Notification {
        val text = when {
            total == 0 -> "Yakındaki telefonlar aranıyor…"
            total == direct -> "$direct kişiye doğrudan bağlı"
            else -> "$direct doğrudan · ${total - direct} zincirle ulaşılabilir"
        }
        val stopIntent = PendingIntent.getService(
            context, 1,
            Intent(context, MeshService::class.java).setAction(MeshService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_mesh)
            .setContentTitle("Dağ Mesh açık")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context, null))
            .addAction(0, "Durdur", stopIntent)
            .build()
    }

    fun updateStatus(context: Context, direct: Int, total: Int) {
        notifySafely(context, STATUS_ID, status(context, direct, total))
    }

    fun message(context: Context, msg: MeshManager.IncomingMessage) {
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_mesh)
            .setContentTitle(msg.peerName)
            .setContentText(msg.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg.text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(context, msg.peerId))
            .build()
        notifySafely(context, 1000 + (msg.peerId.hashCode() and 0xFFFF), notification)
    }

    fun incomingCall(context: Context, peerId: String, name: String) {
        val reject = PendingIntent.getService(
            context, 3,
            Intent(context, MeshService::class.java).setAction(MeshService.ACTION_REJECT_CALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = openApp(context, null)
        val notification = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_stat_mesh)
            .setContentTitle(name)
            .setContentText("Gelen sesli arama · cevaplamak için dokun")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Reddet", reject)
            .addAction(0, "Cevapla", open)
            .build()
        notifySafely(context, CALL_ID, notification)
    }

    fun cancelIncomingCall(context: Context) {
        NotificationManagerCompat.from(context).cancel(CALL_ID)
    }

    private fun openApp(context: Context, peerId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (peerId != null) intent.putExtra(MainActivity.EXTRA_PEER_ID, peerId)
        return PendingIntent.getActivity(
            context, peerId?.hashCode() ?: 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun notifySafely(context: Context, id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // Bildirim izni verilmemiş; sessizce geç.
        }
    }
}
