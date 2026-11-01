package com.dagmesh.app.mesh

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dagmesh.app.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Ekran kapalıyken ve uygulama arka plandayken ağın çalışmaya devam etmesini sağlar. */
class MeshService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collecting = false
    private val mesh get() = App.instance.mesh

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            ServiceCompat.startForeground(
                this,
                Notifications.STATUS_ID,
                Notifications.status(this, 0, 0),
                serviceType(),
            )
        } catch (e: Exception) {
            Log.e("DagMesh", "Arka plan hizmeti başlatılamadı", e)
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_RESTART) mesh.restart() else mesh.start()

        if (!collecting) {
            collecting = true
            scope.launch {
                mesh.peers.collect { peers ->
                    Notifications.updateStatus(this@MeshService, peers.values.count { it.isDirect }, peers.size)
                }
            }
            scope.launch {
                mesh.incoming.collect { msg ->
                    if (mesh.activeChatPeerId != msg.peerId) Notifications.message(this@MeshService, msg)
                }
            }
        }
        return START_STICKY
    }

    /**
     * Konum izni varsa hizmet "konum" türünde de çalışır. Böylece Android, ekran kapalıyken
     * ve uygulama arka plandayken de yakındaki cihaz taramasına izin verir.
     */
    private fun serviceType(): Int {
        if (Build.VERSION.SDK_INT < 29) return 0
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (hasLocation) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        return type
    }

    override fun onDestroy() {
        mesh.stop()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.dagmesh.app.STOP"
        const val ACTION_RESTART = "com.dagmesh.app.RESTART"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MeshService::class.java))
        }

        /** Ağı sıfırdan kurar (izin değişikliği veya hata sonrası). */
        fun restart(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MeshService::class.java).setAction(ACTION_RESTART),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MeshService::class.java))
        }
    }
}
