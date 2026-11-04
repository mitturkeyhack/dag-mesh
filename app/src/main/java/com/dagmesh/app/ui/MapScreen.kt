package com.dagmesh.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddLocationAlt
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dagmesh.app.App
import com.dagmesh.app.map.OfflineMap
import com.dagmesh.app.map.Pin
import com.dagmesh.app.map.PinStore
import com.dagmesh.app.map.PinType
import com.dagmesh.app.media.LocationHelper
import com.dagmesh.app.trail.TrailRecorder
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.ScaleBarOverlay
import org.osmdroid.views.overlay.compass.CompassOverlay
import org.osmdroid.views.overlay.compass.InternalCompassOrientationProvider
import java.util.Locale
import kotlin.math.roundToInt

/** "Harita" sekmesi: üstte Harita / Eve dönüş seçici. */
@Composable
fun MapTab(contentPadding: PaddingValues) {
    var page by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(4.dp),
        ) {
            listOf("Harita", "Eve dönüş").forEachIndexed { i, label ->
                val sel = page == i
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .background(if (sel) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .clickable { page = i }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (sel) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Box(Modifier.weight(1f)) {
            if (page == 0) MapPane(contentPadding) else TrailTab(contentPadding)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapPane(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val pins by PinStore.pins.collectAsStateWithLifecycle()
    val trail by TrailRecorder.points.collectAsStateWithLifecycle()
    val here by TrailRecorder.current.collectAsStateWithLifecycle()
    val sosIncoming by App.instance.mesh.sos.incoming.collectAsStateWithLifecycle()
    val download by OfflineMap.download.collectAsStateWithLifecycle()
    var topo by rememberSaveable { mutableStateOf(true) }
    var selected by remember { mutableStateOf<Pin?>(null) }
    var addAt by remember { mutableStateOf<GeoPoint?>(null) }
    var showDownload by remember { mutableStateOf(false) }
    var centered by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        TrailRecorder.watchForMap(true)
        onDispose { TrailRecorder.watchForMap(false) }
    }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(OfflineMap.TOPO)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 4.0
            maxZoomLevel = 20.0
            val start = TrailRecorder.current.value ?: LocationHelper.lastKnownQuick(context)
            if (start != null) {
                controller.setZoom(15.0)
                controller.setCenter(GeoPoint(start.latitude, start.longitude))
            } else {
                controller.setZoom(6.0)
                controller.setCenter(GeoPoint(39.0, 35.2)) // Türkiye
            }
            overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint?) = false
                override fun longPressHelper(p: GeoPoint?): Boolean {
                    addAt = p
                    return true
                }
            }))
            overlays.add(ScaleBarOverlay(this).apply { setAlignBottom(true); setScaleBarOffset(40, 140) })
            try {
                overlays.add(CompassOverlay(context, InternalCompassOrientationProvider(context), this).apply { enableCompass() })
            } catch (_: Exception) {}
        }
    }
    val dynamic = remember { mutableListOf<Overlay>() }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        mapView.onResume()
        onDispose {
            lifecycle.removeObserver(obs)
            mapView.onPause()
            mapView.onDetach()
        }
    }

    LaunchedEffect(topo) {
        // Uyduda karoları ekran yoğunluğuna büyütme: her piksel gerçek görüntü olsun, keskin görünsün.
        mapView.isTilesScaledToDpi = topo
        mapView.setTileSource(if (topo) OfflineMap.TOPO else OfflineMap.SATELLITE)
        mapView.invalidate()
    }

    // İlk GPS konumu gelince oraya git.
    LaunchedEffect(here != null) {
        val h = here
        if (h != null && !centered) {
            centered = true
            mapView.controller.animateTo(GeoPoint(h.latitude, h.longitude), 15.5, 600L)
        }
    }

    val trailColor = MaterialTheme.colorScheme.primary
    LaunchedEffect(pins, trail, here, sosIncoming) {
        mapView.overlays.removeAll(dynamic)
        dynamic.clear()
        if (trail.size >= 2) {
            dynamic += Polyline(mapView).apply {
                setPoints(trail.map { GeoPoint(it.lat, it.lon) })
                outlinePaint.color = android.graphics.Color.argb(230, (trailColor.red * 255).roundToInt(), (trailColor.green * 255).roundToInt(), (trailColor.blue * 255).roundToInt())
                outlinePaint.strokeWidth = 9f
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
            }
            dynamic += Marker(mapView).apply {
                position = GeoPoint(trail.first().lat, trail.first().lon)
                icon = badgeIcon(context, "🏠", 0xFF4CAF7A)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setOnMarkerClickListener { _, _ -> true }
            }
        }
        pins.values.sortedBy { it.time }.forEach { pin ->
            dynamic += Marker(mapView).apply {
                position = GeoPoint(pin.lat, pin.lon)
                icon = badgeIcon(context, pin.type.emoji, pin.type.color)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setOnMarkerClickListener { _, _ -> selected = pin; true }
            }
        }
        sosIncoming.values.forEach { s ->
            val p = s.point ?: return@forEach
            dynamic += Marker(mapView).apply {
                position = GeoPoint(p.lat, p.lon)
                icon = badgeIcon(context, "🆘", 0xFFE5484D)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "${s.name} · acil durum"
            }
        }
        here?.let { h ->
            dynamic += Marker(mapView).apply {
                position = GeoPoint(h.latitude, h.longitude)
                icon = meIcon(context)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setOnMarkerClickListener { _, _ -> true }
            }
        }
        mapView.overlays.addAll(dynamic)
        mapView.invalidate()
    }

    Box(
        Modifier
            .fillMaxSize()
            .padding(bottom = contentPadding.calculateBottomPadding() - 8.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(26.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(26.dp)),
    ) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        // Üst bilgi şeridi
        Column(Modifier.align(Alignment.TopStart).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (val d = download) {
                is OfflineMap.Download.Running -> DownloadProgress(d)
                else -> if (pins.isEmpty()) MapChip("Haritaya uzun bas: işaret koy")
            }
        }

        // Sağ kontroller
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SmallFloatingActionButton(
                onClick = { topo = !topo },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) { Icon(Icons.Rounded.Layers, contentDescription = "Katman") }
            SmallFloatingActionButton(
                onClick = { showDownload = true },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) { Icon(Icons.Rounded.CloudDownload, contentDescription = "Bölgeyi indir") }
            SmallFloatingActionButton(
                onClick = {
                    val h = here ?: LocationHelper.lastKnownQuick(context)
                    if (h != null) mapView.controller.animateTo(GeoPoint(h.latitude, h.longitude), maxOf(mapView.zoomLevelDouble, 15.0), 500L)
                    else android.widget.Toast.makeText(context, "GPS konumu bekleniyor…", android.widget.Toast.LENGTH_SHORT).show()
                },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) { Icon(Icons.Rounded.MyLocation, contentDescription = "Konumum") }
        }

        ExtendedFloatingActionButton(
            onClick = {
                val h = here
                addAt = if (h != null) GeoPoint(h.latitude, h.longitude) else GeoPoint(mapView.mapCenter.latitude, mapView.mapCenter.longitude)
            },
            icon = { Icon(Icons.Rounded.AddLocationAlt, contentDescription = null) },
            text = { Text("İşaret koy") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
        )

        Text(
            if (topo) "© OpenStreetMap · OpenTopoMap" else "© Esri, Maxar, Earthstar Geographics",
            fontSize = 9.sp,
            color = Color.Black.copy(alpha = 0.7f),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .background(Color.White.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp),
        )
    }

    addAt?.let { point -> AddPinSheet(point, here, onDismiss = { addAt = null }) }
    selected?.let { pin -> PinSheet(pin, here, onDismiss = { selected = null }) }
    if (showDownload) DownloadSheet(mapView, onLayerTopo = { topo = true }, onDismiss = { showDownload = false; OfflineMap.reset() })
}

@Composable
private fun MapChip(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f))
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun DownloadProgress(d: OfflineMap.Download.Running) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp), modifier = Modifier.width(230.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Harita indiriliyor… ${d.done}/${d.total}", style = MaterialTheme.typography.labelMedium)
            LinearProgressIndicator(
                progress = { if (d.total > 0) d.done / d.total.toFloat() else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "İptal",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { OfflineMap.cancel() },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AddPinSheet(point: GeoPoint, here: android.location.Location?, onDismiss: () -> Unit) {
    var type by remember { mutableStateOf(PinType.WATER) }
    var note by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("İşaret koy", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                coords(point.latitude, point.longitude) + (here?.let { " · " + LocationHelper.distanceAndDirection(it, LocationHelper.Point(point.latitude, point.longitude, 0)) } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 3,
            ) {
                PinType.entries.forEach { t ->
                    val sel = t == type
                    val c = Color(t.color)
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (sel) c.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .border(if (sel) 2.dp else 0.dp, if (sel) c else Color.Transparent, RoundedCornerShape(16.dp))
                            .clickable { type = t }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(t.emoji, fontSize = 22.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(t.title, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 1)
                    }
                }
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(200) },
                placeholder = { Text(type.hint) },
                label = { Text("Not (isteğe bağlı)") },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                (if (type.lifeHours == 0) "Kalıcı işaret" else "${lifeText(type.lifeHours)} sonra kendiliğinden silinir") +
                    " · Menzildeki herkese gönderilir",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    PinStore.add(type, point.latitude, point.longitude, note)
                    onDismiss()
                },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Kaydet ve paylaş", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinSheet(pin: Pin, here: android.location.Location?, onDismiss: () -> Unit) {
    val mine = pin.authorId == App.instance.mesh.myId
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(Color(pin.type.color).copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) { Text(pin.type.emoji, fontSize = 28.sp) }
                Column {
                    Text(pin.type.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        (if (mine) "Sen" else pin.authorName) + " · " + ago(pin.time),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (pin.note.isNotBlank()) Text(pin.note, style = MaterialTheme.typography.bodyLarge)
            if (here != null) {
                Text(
                    LocationHelper.distanceAndDirection(here, LocationHelper.Point(pin.lat, pin.lon, 0)),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                coords(pin.lat, pin.lon) + if (pin.expiresAt > 0) " · ${ago(pin.expiresAt, future = true)} silinecek" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { PinStore.remove(pin); onDismiss() },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                ) { Text(if (mine) "Herkesten sil" else "Haritamdan kaldır") }
                Button(onClick = onDismiss, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) { Text("Tamam") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadSheet(mapView: MapView, onLayerTopo: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val download by OfflineMap.download.collectAsStateWithLifecycle()
    val box: BoundingBox = remember { mapView.boundingBox }
    val zoom = mapView.zoomLevelDouble.toInt()
    val zMin = (zoom - 3).coerceIn(6, 13)
    val zMax = OfflineMap.MAX_ZOOM
    val tiles = remember { OfflineMap.estimate(mapView, box, zMin, zMax) }
    val size = remember { OfflineMap.cacheSizeMb() }
    val tooBig = tiles > OfflineMap.MAX_TILES || zoom < 10
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Bölgeyi çevrimdışı indir", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "Ekranda gördüğün bölgenin topoğrafik haritası (eş yükselti eğrileri, patikalar, pınarlar) telefona kaydedilir. Dağda internetsiz açılır. İndirirken internet gerekir, Wi‑Fi önerilir.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Alan: yaklaşık ${areaKm(box)} · yakınlık $zMin–$zMax",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        if (tiles > 0) "$tiles parça · ≈ ${OfflineMap.estimateMb(tiles)} MB" else "Hesaplanamadı",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Telefonda kayıtlı harita: $size MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when (val d = download) {
                is OfflineMap.Download.Running -> {
                    LinearProgressIndicator(progress = { if (d.total > 0) d.done / d.total.toFloat() else 0f }, modifier = Modifier.fillMaxWidth())
                    Text("İndiriliyor ${d.done}/${d.total}. Bu pencereyi kapatabilirsin, indirme sürer.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { OfflineMap.cancel() }) { Text("İptal et") }
                }
                is OfflineMap.Download.Done -> {
                    Text(
                        if (d.failed == 0) "✓ Bölge indirildi. Artık internetsiz açılır."
                        else "İndirildi, ${d.failed} parça alınamadı. Tekrar indirirsen eksikler tamamlanır.",
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Button(onClick = onDismiss, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) { Text("Tamam") }
                }
                is OfflineMap.Download.Failed -> {
                    Text(d.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { OfflineMap.reset() }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) { Text("Tekrar dene") }
                }
                OfflineMap.Download.Idle -> {
                    if (tooBig) {
                        Text(
                            "Bölge çok büyük. Haritayı biraz yakınlaştır (köy/yayla boyutu), sonra tekrar dene.",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Button(
                        onClick = {
                            onLayerTopo()
                            mapView.setTileSource(OfflineMap.TOPO)
                            OfflineMap.start(context, mapView, box, zMin, zMax)
                        },
                        enabled = !tooBig && tiles > 0,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        Icon(Icons.Rounded.CloudDownload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("İndir", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- yardımcılar

private val iconCache = HashMap<String, BitmapDrawable>()

/** Renkli damla biçimli işaret simgesi (içinde emoji). */
private fun badgeIcon(context: Context, emoji: String, color: Long): BitmapDrawable {
    val key = "$emoji-$color"
    iconCache[key]?.let { return it }
    val d = context.resources.displayMetrics.density
    val w = (40 * d).toInt()
    val h = (50 * d).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val r = w / 2f - 2 * d
    val cx = w / 2f
    val cy = r + 2 * d
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color.toInt() }
    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.WHITE }
    val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.argb(70, 0, 0, 0) }
    // Sivri uç
    val tip = android.graphics.Path().apply {
        moveTo(cx - r * 0.55f, cy + r * 0.7f)
        lineTo(cx, h - 2 * d)
        lineTo(cx + r * 0.55f, cy + r * 0.7f)
        close()
    }
    c.drawCircle(cx, cy + 1.5f * d, r, shadow)
    c.drawPath(tip, white)
    c.drawCircle(cx, cy, r, white)
    c.drawCircle(cx, cy, r - 2.5f * d, fill)
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 17 * d; textAlign = Paint.Align.CENTER }
    val fm = text.fontMetrics
    c.drawText(emoji, cx, cy - (fm.ascent + fm.descent) / 2, text)
    return BitmapDrawable(context.resources, bmp).also { iconCache[key] = it }
}

private fun meIcon(context: Context): BitmapDrawable {
    iconCache["me"]?.let { return it }
    val d = context.resources.displayMetrics.density
    val s = (26 * d).toInt()
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    c.drawCircle(s / 2f, s / 2f, s / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(60, 77, 163, 255) })
    c.drawCircle(s / 2f, s / 2f, 8 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
    c.drawCircle(s / 2f, s / 2f, 6 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(77, 163, 255) })
    return BitmapDrawable(context.resources, bmp).also { iconCache["me"] = it }
}

private fun coords(lat: Double, lon: Double) = String.format(Locale.US, "%.5f, %.5f", lat, lon)

private fun lifeText(hours: Int) = if (hours >= 48) "${hours / 24} gün" else "$hours saat"

private fun ago(time: Long, future: Boolean = false): String {
    val diff = if (future) time - System.currentTimeMillis() else System.currentTimeMillis() - time
    val min = (diff / 60_000).coerceAtLeast(0)
    val t = when {
        min < 1 -> return if (future) "birazdan" else "az önce"
        min < 60 -> "$min dk"
        min < 48 * 60 -> "${min / 60} saat"
        else -> "${min / 1440} gün"
    }
    return if (future) "$t sonra" else "$t önce"
}

private fun areaKm(box: BoundingBox): String {
    val w = box.longitudeSpanWithDateLine * 111.32 * kotlin.math.cos(Math.toRadians(box.centerLatitude))
    val h = box.latitudeSpan * 110.54
    return String.format(Locale("tr"), "%.1f × %.1f km", w, h)
}
