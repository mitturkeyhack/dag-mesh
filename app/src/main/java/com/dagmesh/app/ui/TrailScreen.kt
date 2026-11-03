package com.dagmesh.app.ui

import android.content.Context
import android.content.Intent
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Hiking
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.UTurnLeft
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dagmesh.app.App
import com.dagmesh.app.mesh.MeshService
import com.dagmesh.app.trail.TrailRecorder
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt

private val TrailBlue = Color(0xFF4DA3FF)

/** "İz" sekmesi: yürüyüşü kaydet, kaybolunca aynı yoldan geri dön. */
@Composable
fun TrailTab(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val points by TrailRecorder.points.collectAsStateWithLifecycle()
    val recording by TrailRecorder.recording.collectAsStateWithLifecycle()
    val returning by TrailRecorder.returning.collectAsStateWithLifecycle()
    val here by TrailRecorder.current.collectAsStateWithLifecycle()
    val gpsOff by TrailRecorder.gpsOff.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    // Süre yazısı her saniye tazelensin.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(recording) {
        while (recording) { now = System.currentTimeMillis(); delay(1000) }
    }

    fun begin(fresh: Boolean) {
        // Ekran kapalıyken de kayıt sürsün: arka plan hizmeti (konum türü) açık olmalı.
        if (!App.instance.mesh.running.value) MeshService.start(context)
        TrailRecorder.startRecording(fresh)
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = { Text("İz silinsin mi?") },
            text = { Text("Kaydedilen yol telefondan silinir. Geri dönüşte kullanamazsın.") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; TrailRecorder.clear() }) { Text("Sil") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Vazgeç") } },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        if (gpsOff) GpsOffCard(context)

        if (points.isEmpty() && !recording) {
            TrailEmptyState(onStart = { begin(fresh = true) })
            Spacer(Modifier.height(8.dp))
            return@Column
        }

        TrailMap(points, here, recording, returning)

        // Özet
        val walked = TrailRecorder.length(points)
        val duration = points.firstOrNull()?.let { (if (recording) now else points.last().time) - it.time } ?: 0L
        val crow = here?.let { h -> points.firstOrNull()?.let { TrailRecorder.distance(h.latitude, h.longitude, it.lat, it.lon) } }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Yürünen", formatDistance(walked), Modifier.weight(1f))
            StatTile("Süre", formatDuration(duration), Modifier.weight(1f))
            StatTile("Başlangıca", crow?.let { formatDistance(it) } ?: "—", Modifier.weight(1f))
        }

        if (returning) {
            ReturnGuide(points, here)
            OutlinedButton(
                onClick = { TrailRecorder.setReturning(false) },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Dönüşü bitir") }
        } else if (recording) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = { TrailRecorder.stopRecording() },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Icon(Icons.Rounded.Stop, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Durdur")
                }
                Button(
                    onClick = { TrailRecorder.stopRecording(); TrailRecorder.setReturning(true) },
                    enabled = points.size >= 2,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Icon(Icons.Rounded.UTurnLeft, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Geri dön")
                }
            }
            Text(
                "Telefon cebinde, ekran kapalıyken de kaydeder.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        } else {
            Button(
                onClick = { TrailRecorder.setReturning(true) },
                enabled = points.size >= 2,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) {
                Icon(Icons.Rounded.UTurnLeft, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Başlangıca geri dön", fontWeight = FontWeight.SemiBold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { begin(fresh = false) },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Devam et")
                }
                OutlinedButton(
                    onClick = { begin(fresh = true) },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text("Yeni iz") }
                IconButton(onClick = { confirmClear = true }) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "İzi sil", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun TrailEmptyState(onStart: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Hiking, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(44.dp))
            }
            Text("Eve dönüş izi", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Yola çıkarken kaydı başlat. Yürüdüğün yolu GPS ile kaydeder; kaybolursan ok seni aynı yoldan başlangıca geri götürür.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FeaturePill("İnternetsiz")
                FeaturePill("Ekran kapalıyken")
                FeaturePill("Pusulalı")
            }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onStart,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Kaydı başlat", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun FeaturePill(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun GpsOffCard(context: Context) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.LocationOff, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Text("Konum (GPS) kapalı", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("Aç") }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(18.dp), modifier = modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** Yolun kuşbakışı çizimi (kuzey yukarıda). */
@Composable
private fun TrailMap(points: List<TrailRecorder.Pt>, here: Location?, recording: Boolean, returning: Boolean) {
    val pathColor = MaterialTheme.colorScheme.primary
    val homeColor = MaterialTheme.colorScheme.secondary
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val bg = MaterialTheme.colorScheme.surfaceContainer
    val target = if (returning && here != null) TrailRecorder.guide(points, here)?.target else null

    Surface(color = bg, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.05f)) {
            if (points.isEmpty()) {
                Text(
                    "GPS konumu bekleniyor…\nAçık alanda daha hızlı bulur.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            Canvas(Modifier.fillMaxSize().padding(22.dp)) {
                // Izgara
                val step = size.width / 6f
                var gx = 0f
                while (gx <= size.width) { drawLine(gridColor, Offset(gx, 0f), Offset(gx, size.height), 1f); gx += step }
                var gy = 0f
                while (gy <= size.height) { drawLine(gridColor, Offset(0f, gy), Offset(size.width, gy), 1f); gy += step }
                if (points.isEmpty()) return@Canvas

                val lat0 = points.first().lat
                val lon0 = points.first().lon
                val kx = 111_320.0 * cos(Math.toRadians(lat0))
                val ky = 110_540.0
                fun mx(lon: Double) = (lon - lon0) * kx
                fun my(lat: Double) = (lat - lat0) * ky

                val xs = points.map { mx(it.lon) } + listOfNotNull(here?.let { mx(it.longitude) })
                val ys = points.map { my(it.lat) } + listOfNotNull(here?.let { my(it.latitude) })
                val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
                val spanM = max(max(maxX - minX, maxY - minY), 60.0) // en az 60 m göster
                val scale = (minOf(size.width, size.height) / spanM).toFloat() // px / m
                val cx = ((minX + maxX) / 2)
                val cy = ((minY + maxY) / 2)
                fun px(lon: Double) = (size.width / 2 + (mx(lon) - cx) * scale).toFloat()
                fun py(lat: Double) = (size.height / 2 - (my(lat) - cy) * scale).toFloat()

                // Yol
                if (points.size >= 2) {
                    val path = Path()
                    points.forEachIndexed { i, p -> if (i == 0) path.moveTo(px(p.lon), py(p.lat)) else path.lineTo(px(p.lon), py(p.lat)) }
                    drawPath(path, pathColor.copy(alpha = 0.25f), style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(path, pathColor, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                // Kaydın ucu
                val last = points.last()
                if (!recording && points.size >= 2) {
                    drawCircle(pathColor, radius = 5.dp.toPx(), center = Offset(px(last.lon), py(last.lat)))
                }
                // Hedef iz noktası (dönüşte)
                if (target != null && here != null) {
                    drawLine(
                        TrailBlue, Offset(px(here.longitude), py(here.latitude)), Offset(px(target.lon), py(target.lat)),
                        strokeWidth = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)),
                    )
                    drawCircle(TrailBlue, radius = 6.dp.toPx(), center = Offset(px(target.lon), py(target.lat)), style = Stroke(3.dp.toPx()))
                }
                // Başlangıç (ev)
                val home = Offset(px(lon0), py(lat0))
                drawCircle(homeColor.copy(alpha = 0.25f), radius = 14.dp.toPx(), center = home)
                drawCircle(homeColor, radius = 8.dp.toPx(), center = home)
                drawCircle(Color.White, radius = 3.dp.toPx(), center = home)
                // Şu anki konum
                if (here != null) {
                    val me = Offset(px(here.longitude), py(here.latitude))
                    if (here.hasAccuracy()) {
                        drawCircle(TrailBlue.copy(alpha = 0.15f), radius = (here.accuracy * scale).coerceIn(10.dp.toPx(), size.width / 3), center = me)
                    }
                    drawCircle(Color.White, radius = 8.dp.toPx(), center = me)
                    drawCircle(TrailBlue, radius = 6.dp.toPx(), center = me)
                }

                // Ölçek çubuğu
                val maxBarM = (size.width / 4) / scale
                val nice = listOf(10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000).lastOrNull { it <= maxBarM } ?: 10
                val barPx = nice * scale
                val by = size.height - 2.dp.toPx()
                drawLine(textColor, Offset(0f, by), Offset(barPx, by), 2.dp.toPx(), cap = StrokeCap.Round)
                drawLine(textColor, Offset(0f, by - 5.dp.toPx()), Offset(0f, by), 2.dp.toPx())
                drawLine(textColor, Offset(barPx, by - 5.dp.toPx()), Offset(barPx, by), 2.dp.toPx())
                drawContext.canvas.nativeCanvas.drawText(
                    if (nice >= 1000) "${nice / 1000} km" else "$nice m",
                    barPx + 6.dp.toPx(), by + 3.dp.toPx(),
                    android.graphics.Paint().apply { color = android.graphics.Color.argb(200, 160, 160, 160); textSize = 11.sp.toPx(); isAntiAlias = true },
                )
            }
            // Üst köşe: durum ve kuzey
            Row(
                Modifier.align(Alignment.TopStart).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val (dot, label) = when {
                    returning -> TrailBlue to "Geri dönüş"
                    recording -> SosRed to "Kaydediliyor"
                    else -> textColor to "Kayıt durdu"
                }
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                    Text(label, style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(
                "K",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = textColor,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            )
            Row(
                Modifier.align(Alignment.BottomEnd).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Rounded.Home, contentDescription = null, tint = homeColor, modifier = Modifier.size(14.dp))
                Text("Başlangıç", style = MaterialTheme.typography.labelSmall, color = textColor)
            }
        }
    }
}

/** Dönüş rehberi: büyük ok + kalan yol. */
@Composable
private fun ReturnGuide(points: List<TrailRecorder.Pt>, here: Location?) {
    val heading = rememberHeading(here)
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (here == null) {
                Text("GPS konumu bekleniyor…", style = MaterialTheme.typography.titleMedium)
                Text(
                    "İnternetsiz ilk konum 30 sn – 2 dk sürebilir. Gökyüzünü gören bir yerde bekle.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                return@Column
            }
            val g = TrailRecorder.guide(points, here) ?: return@Column
            if (g.arrived) {
                Box(
                    Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Home, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(48.dp)) }
                Text("Başlangıç noktasına vardın", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("Yaklaşık ${formatDistance(g.toTarget)} yakınındasın.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }

            val bearing = TrailRecorder.bearing(here, g.target)
            val raw = if (heading != null) bearing - heading else bearing
            // 359° → 0° geçişinde ok ters dönmesin: açıyı sürekli biriktir.
            val acc = remember { floatArrayOf(raw) }
            val d = ((raw - acc[0]) % 360f + 540f) % 360f - 180f
            acc[0] += d
            val angle by animateFloatAsState(acc[0], tween(300), label = "arrow")

            Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                val ring = MaterialTheme.colorScheme.outlineVariant
                val arrowColor = MaterialTheme.colorScheme.primary
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(ring.copy(alpha = 0.4f), style = Stroke(2.dp.toPx()))
                    drawCircle(arrowColor.copy(alpha = 0.08f), radius = size.minDimension / 2 - 10.dp.toPx())
                }
                Canvas(Modifier.fillMaxSize().padding(36.dp).rotate(angle)) {
                    val w = size.width; val h = size.height
                    val p = Path().apply {
                        moveTo(w / 2, 0f)
                        lineTo(w * 0.86f, h * 0.92f)
                        lineTo(w / 2, h * 0.7f)
                        lineTo(w * 0.14f, h * 0.92f)
                        close()
                    }
                    drawPath(p, arrowColor)
                }
            }
            Text(
                formatDistance(g.remaining),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "iz boyunca başlangıca",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (g.offTrail > 50f) {
                Text(
                    "İzden ${formatDistance(g.offTrail)} uzaktasın. Ok seni önce ize geri götürür.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                if (heading != null) "Telefonu düz tut, okun gösterdiği yöne yürü."
                else "Pusula yok: ok kuzeye göre. Yön: ${compassName(bearing)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Telefonun baktığı yön (gerçek kuzeye göre derece) ya da pusula yoksa null. */
@Composable
private fun rememberHeading(location: Location?): Float? {
    val context = LocalContext.current
    var heading by remember { mutableStateOf<Float?>(null) }
    val declination = remember(location?.latitude?.roundToInt(), location?.longitude?.roundToInt()) {
        location?.let {
            GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), it.altitude.toFloat(), System.currentTimeMillis()).declination
        } ?: 0f
    }
    DisposableEffect(Unit) {
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sm == null || sensor == null) return@DisposableEffect onDispose { }
        val rot = FloatArray(9)
        val orient = FloatArray(3)
        var smooth: Float? = null
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                SensorManager.getOrientation(rot, orient)
                val az = Math.toDegrees(orient[0].toDouble()).toFloat()
                val s = smooth
                smooth = if (s == null) az else s + (((az - s) % 360f + 540f) % 360f - 180f) * 0.2f
                heading = ((smooth!! % 360f) + 360f) % 360f
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(l) }
    }
    return heading?.let { (it + declination + 360f) % 360f }
}

private fun compassName(bearing: Float): String {
    val names = listOf("kuzey", "kuzeydoğu", "doğu", "güneydoğu", "güney", "güneybatı", "batı", "kuzeybatı")
    val i = (((bearing + 360f) % 360f + 22.5f) / 45f).toInt() % 8
    return names[i]
}

fun formatDistance(m: Float): String = when {
    m < 1000f -> "${m.roundToInt()} m"
    m < 10_000f -> "%.1f km".format(m / 1000f).replace('.', ',')
    else -> "${(m / 1000f).roundToInt()} km"
}

private fun formatDuration(ms: Long): String {
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "$h sa $m dk" else if (totalMin > 0) "$m dk" else "${(ms / 1000).coerceAtLeast(0)} sn"
}
