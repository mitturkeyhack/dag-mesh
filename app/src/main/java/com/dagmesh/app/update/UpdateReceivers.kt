package com.dagmesh.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.IntentCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dagmesh.app.App
import com.dagmesh.app.Permissions
import com.dagmesh.app.mesh.MeshService

/** Arka planda (uygulama kapalıyken de) güncelleme kontrolü. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (Updater.checkAndInstall(applicationContext, userInitiated = false)) Result.success() else Result.retry()
}

/** Android'in kurulum sonucunu bildirdiği yer. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RESULT) return
        val version = intent.getStringExtra(EXTRA_VERSION).orEmpty()
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                Updater.onNeedsConfirm(context, version, confirm)
                // Uygulama açıksa onay ekranını hemen göster.
                if (App.isInForeground) {
                    try {
                        context.startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) {
                        Log.w("DagMesh/Update", "Onay ekranı açılamadı", e)
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // Uygulama yeniden başlayınca PackageReplacedReceiver haber verir.
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w("DagMesh/Update", "Kurulum başarısız ($status): $message")
                Updater.onFailed(
                    if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "Güncelleme iptal edildi"
                    else "Güncelleme kurulamadı"
                )
            }
        }
    }

    companion object {
        const val ACTION_RESULT = "com.dagmesh.app.UPDATE_RESULT"
        const val EXTRA_VERSION = "version"
    }
}

/** Uygulama güncellendikten sonra ağı kaldığı yerden başlatır ve kullanıcıya haber verir. */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Updater.onUpdated(context)
        val app = context.applicationContext as? App ?: return
        if (app.identity.name.value != null && Permissions.hasCore(context)) {
            try {
                MeshService.start(context)
            } catch (e: Exception) {
                Log.w("DagMesh/Update", "Ağ yeniden başlatılamadı", e)
            }
        }
    }
}
