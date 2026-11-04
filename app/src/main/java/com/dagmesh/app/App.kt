package com.dagmesh.app

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.dagmesh.app.data.AppDatabase
import com.dagmesh.app.data.Identity
import com.dagmesh.app.data.Settings
import com.dagmesh.app.mesh.MeshManager
import com.dagmesh.app.share.ApkShare
import com.dagmesh.app.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application(), ImageLoaderFactory {
    lateinit var identity: Identity
        private set
    lateinit var db: AppDatabase
        private set
    lateinit var settings: Settings
        private set
    lateinit var mesh: MeshManager
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Uygulamayı internetsiz paylaşma (QR / Quick Share). */
    val apkShare by lazy { ApkShare(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        identity = Identity(this)
        db = AppDatabase.build(this)
        settings = Settings(this)
        mesh = MeshManager(this, identity, settings, db.dao(), scope)
        Updater.schedule(this)
        com.dagmesh.app.trail.TrailRecorder.init(this)
        com.dagmesh.app.map.OfflineMap.init(this)
        com.dagmesh.app.map.PinStore.init(this, mesh, scope)
    }

    /** Fotoğraf ve video küçük resimleri için görsel yükleyici. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(VideoFrameDecoder.Factory()) }
        .crossfade(true)
        .build()

    companion object {
        lateinit var instance: App
            private set

        /** Uygulama ekranda mı? (Bildirim gösterip göstermemeye karar vermek için.) */
        val isInForeground: Boolean
            get() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }
}
