package com.dagmesh.app

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.dagmesh.app.data.AppDatabase
import com.dagmesh.app.data.Identity
import com.dagmesh.app.mesh.MeshManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application(), ImageLoaderFactory {
    lateinit var identity: Identity
        private set
    lateinit var db: AppDatabase
        private set
    lateinit var mesh: MeshManager
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        identity = Identity(this)
        db = AppDatabase.build(this)
        mesh = MeshManager(this, identity, db.dao(), scope)
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
