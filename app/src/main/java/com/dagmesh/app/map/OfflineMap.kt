package com.dagmesh.app.map

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.MapView
import java.io.File

/**
 * Çevrimdışı harita: internet varken bölge bir kez indirilir, dağda internetsiz açılır.
 * Dağ için eş yükselti eğrili, patikalı topoğrafik harita (OpenTopoMap) kullanılır.
 */
object OfflineMap {

    /** Eş yükselti eğrileri, patikalar, pınarlar: dağ için en uygun katman. */
    val TOPO: ITileSource = XYTileSource(
        "OpenTopoMap", 0, 17, 256, ".png",
        arrayOf(
            "https://a.tile.opentopomap.org/",
            "https://b.tile.opentopomap.org/",
            "https://c.tile.opentopomap.org/",
        ),
        "© OpenStreetMap katkıcıları, SRTM · OpenTopoMap (CC-BY-SA)",
        // Sunucuyu yormamak için en fazla 2 eşzamanlı indirme.
        TileSourcePolicy(2, TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL or TileSourcePolicy.FLAG_USER_AGENT_NORMALIZED),
    )

    /** Uydu görüntüsü (Esri World Imagery). Toplu indirmeye kapalı, sadece gezinirken önbelleğe alınır. */
    val SATELLITE: ITileSource = object : OnlineTileSourceBase(
        "EsriWorldImagery", 0, 18, 256, ".jpg",
        arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
        "Esri, Maxar, Earthstar Geographics",
        TileSourcePolicy(2, TileSourcePolicy.FLAG_NO_BULK or TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL),
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String =
            baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" + MapTileIndex.getY(pMapTileIndex) + "/" + MapTileIndex.getX(pMapTileIndex)
    }

    /** Tek seferde indirilebilecek en fazla karo (≈ 150–250 MB). */
    const val MAX_TILES = 12_000
    const val MAX_ZOOM = 16
    private const val AVG_TILE_KB = 18

    sealed interface Download {
        data object Idle : Download
        data class Running(val done: Int, val total: Int) : Download
        data class Done(val total: Int, val failed: Int) : Download
        data class Failed(val message: String) : Download
    }

    private val _download = MutableStateFlow<Download>(Download.Idle)
    val download: StateFlow<Download> = _download.asStateFlow()

    private var task: CacheManager.CacheManagerTask? = null
    private lateinit var tileDir: File

    fun init(context: Context) {
        val base = File(context.filesDir, "osmdroid")
        tileDir = File(base, "tiles")
        Configuration.getInstance().apply {
            userAgentValue = "DagMesh/${context.packageName} (Android; offline mesh)"
            osmdroidBasePath = base
            osmdroidTileCache = tileDir
            tileFileSystemCacheMaxBytes = 2_000L * 1024 * 1024
            tileFileSystemCacheTrimBytes = 1_700L * 1024 * 1024
            // İndirilen karolar uzun süre geçerli sayılsın; internetsizken zaten süresi geçmiş olanlar da gösterilir.
            expirationOverrideDuration = 60L * 24 * 3_600_000
        }
        // Eski sürümdeki OSM sokak katmanı engellendi; önbellekte kalan "erişim engellendi" karolarını temizle.
        Thread {
            try { SqlTileWriter().apply { purgeCache("Mapnik"); onDetach() } } catch (_: Throwable) {}
        }.start()
    }

    /** Telefonda kayıtlı harita boyutu (MB). */
    fun cacheSizeMb(): Long = try {
        (tileDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }) / (1024 * 1024)
    } catch (_: Exception) {
        0
    }

    fun estimate(map: MapView, box: BoundingBox, zMin: Int, zMax: Int): Int =
        try { CacheManager(map).possibleTilesInArea(box, zMin, zMax) } catch (_: Exception) { -1 }

    fun estimateMb(tiles: Int): Int = (tiles.toLong() * AVG_TILE_KB / 1024).toInt().coerceAtLeast(1)

    fun start(context: Context, map: MapView, box: BoundingBox, zMin: Int, zMax: Int) {
        if (_download.value is Download.Running) return
        try {
            val manager = CacheManager(map)
            _download.value = Download.Running(0, manager.possibleTilesInArea(box, zMin, zMax))
            task = manager.downloadAreaAsyncNoUI(context.applicationContext, box, zMin, zMax, object : CacheManager.CacheManagerCallback {
                private var total = 0
                override fun onTaskComplete() {
                    _download.value = Download.Done(total, 0)
                }
                override fun onTaskFailed(errors: Int) {
                    _download.value = if (errors >= total && total > 0) Download.Failed("İndirilemedi. İnternet bağlantını kontrol et.")
                    else Download.Done(total, errors)
                }
                override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) {
                    _download.value = Download.Running(progress, total)
                }
                override fun downloadStarted() = Unit
                override fun setPossibleTilesInArea(total: Int) {
                    this.total = total
                    _download.value = Download.Running(0, total)
                }
            })
        } catch (e: Exception) {
            _download.value = Download.Failed(e.message ?: "İndirme başlatılamadı")
        }
    }

    fun cancel() {
        try { task?.cancel(true) } catch (_: Exception) {}
        task = null
        _download.value = Download.Idle
    }

    fun reset() {
        if (_download.value !is Download.Running) _download.value = Download.Idle
    }
}
