package com.dagmesh.app.mesh

import android.app.Service
import android.content.Context
import android.content.Intent
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
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
        } catch (e: Exception) {
            Log.e("DagMesh", "Arka plan hizmeti başlatılamadı", e)
            stopSelf()
            return START_NOT_STICKY
        }

        mesh.start()

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

    override fun onDestroy() {
        mesh.stop()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.dagmesh.app.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MeshService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MeshService::class.java))
        }
    }
}
