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
import com.dagmesh.app.call.CallState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Ekran kapalıyken ve uygulama arka plandayken ağın (ve aramaların) çalışmaya devam etmesini sağlar. */
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
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REJECT_CALL -> {
                mesh.calls.reject()
                if (mesh.running.value) return START_STICKY
            }
        }

        if (!goForeground(callActive = mesh.calls.state.value is CallState.Active)) {
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
                    if (mesh.activeChatPeerId != msg.peerId || !App.isInForeground) {
                        Notifications.message(this@MeshService, msg)
                    }
                }
            }
            scope.launch {
                var wasActive = false
                mesh.calls.state.collect { state ->
                    if (state is CallState.Incoming && !App.isInForeground) {
                        Notifications.incomingCall(this@MeshService, state.peerId, state.name)
                    } else {
                        Notifications.cancelIncomingCall(this@MeshService)
                    }
                    // Arama sürerken mikrofonun ekran kapalıyken de çalışması için hizmet türünü güncelle.
                    val active = state is CallState.Active
                    if (active != wasActive) {
                        wasActive = active
                        goForeground(callActive = active)
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun goForeground(callActive: Boolean): Boolean = try {
        ServiceCompat.startForeground(
            this,
            Notifications.STATUS_ID,
            Notifications.status(this, mesh.peers.value.values.count { it.isDirect }, mesh.peers.value.size),
            serviceType(callActive),
        )
        true
    } catch (e: Exception) {
        Log.e("DagMesh", "Arka plan hizmeti başlatılamadı", e)
        // Mikrofon türü reddedildiyse onsuz tekrar dene.
        if (callActive) goForeground(callActive = false) else false
    }

    /**
     * Konum izni varsa hizmet "konum" türünde de çalışır: Android, ekran kapalıyken
     * yakındaki cihaz taramasına ancak böyle izin verir. Arama sırasında "mikrofon" da eklenir.
     */
    private fun serviceType(callActive: Boolean): Int {
        if (Build.VERSION.SDK_INT < 29) return 0
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        if (callActive && Build.VERSION.SDK_INT >= 30 && granted(Manifest.permission.RECORD_AUDIO)) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return type
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        mesh.stop()
        scope.cancel()
        Notifications.cancelIncomingCall(this)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.dagmesh.app.STOP"
        const val ACTION_RESTART = "com.dagmesh.app.RESTART"
        const val ACTION_REJECT_CALL = "com.dagmesh.app.REJECT_CALL"

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
