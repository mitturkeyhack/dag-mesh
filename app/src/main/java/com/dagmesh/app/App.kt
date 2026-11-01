package com.dagmesh.app

import android.app.Application
import com.dagmesh.app.data.AppDatabase
import com.dagmesh.app.data.Identity
import com.dagmesh.app.mesh.MeshManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {
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

    companion object {
        lateinit var instance: App
            private set
    }
}
