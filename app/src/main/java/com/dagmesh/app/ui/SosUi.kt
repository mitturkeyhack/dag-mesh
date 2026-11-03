package com.dagmesh.app.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dagmesh.app.App
import com.dagmesh.app.media.LocationHelper
import com.dagmesh.app.sos.SosManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

val SosRed = Color(0xFFE5484D)
private val SosDark = Color(0xFF3A0B0D)

private fun openMap(context: android.content.Context, point: LocationHelper.Point, label: String) {
    val uri = Uri.parse("geo:${point.lat},${point.lon}?q=${point.lat},${point.lon}(${Uri.encode(label)})")
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(context, "Harita uygulaması bulunamadı", Toast.LENGTH_SHORT).show()
    }
}

private fun minutesAgo(time: Long): String {
    val min = ((System.currentTimeMillis() - time) / 60_000).toInt()
    return if (min <= 0) "az önce" else "$min dk önce"
}

/**
 * Ana ekrandaki SOS düğmesi: kazayla basılmasın diye 2 saniye basılı tutulur.
 * Basılı tutarken kırmızı halka dolar.
 */
@Composable
fun SosHoldButton(modifier: Modifier = Modifier) {
    val sos = App.instance.mesh.sos
    val haptics = LocalHapticFeedback.current
    var progress by remember { mutableFloatStateOf(0f) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown()
                        val start = System.currentTimeMillis()
                        var fired = false
                        // Parmak kalkana kadar (ya da kayana kadar) halkayı doldur.
                        while (true) {
                            // true: parmak kalktı/kaydı; null: 16 ms doldu, hâlâ basılı.
                            val ended = withTimeoutOrNull(16) {
                                waitForUpOrCancellation()
                                true
                            }
                            val elapsed = System.currentTimeMillis() - start
                            progress = (elapsed / HOLD_MS.toFloat()).coerceAtMost(1f)
                            if (!fired && elapsed >= HOLD_MS) {
                                fired = true
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                sos.start()
                            }
                            if (ended == true) break
                        }
                        progress = 0f
                    }
                }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(SosRed.copy(alpha = 0.15f))
                    if (progress > 0f) {
                        drawArc(
                            color = SosRed,
                            startAngle = -90f,
                            sweepAngle = 360f * progress,
                            useCenter = false,
                            style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                }
                Text("SOS", color = SosRed, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Column(Modifier.weight(1f)) {
                Text("Acil durum", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (progress > 0f) "Bırakma… ${"%.0f".format((1f - progress) * HOLD_MS / 1000f + 0.4f)} sn"
                    else "2 saniye basılı tut: konumun herkese alarmla gider",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (progress > 0f) SosRed else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Gönderen tarafta: SOS yayındayken ana ekranda kırmızı kart. */
@Composable
fun SosActiveCard(out: SosManager.Outgoing) {
    val sos = App.instance.mesh.sos
    val transition = rememberInfiniteTransition(label = "sos")
    val pulse by transition.animateFloat(0.6f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "p")
    Surface(color = SosRed, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = pulse))
                )
                Text("SOS yayında", color = Color.White, style = MaterialTheme.typography.titleLarge)
            }
            Text(
                when {
                    out.point == null -> "Konum aranıyor (GPS)… uyarı konumsuz gidiyor"
                    else -> "Konumun gönderiliyor · ± ${out.point.accuracy} m"
                },
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (out.helpers.isNotEmpty()) {
                Text(
                    "🏃 Yardıma geliyor: " + out.helpers.values.joinToString(", "),
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            val onlySeen = out.seenBy - out.helpers.keys
            if (onlySeen.isNotEmpty()) {
                Text(
                    "👁 Gördü: " + onlySeen.values.joinToString(", "),
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (out.helpers.isEmpty() && onlySeen.isEmpty()) {
                Text(
                    "Henüz kimse görmedi. Menzile giren herkese otomatik ulaşacak.",
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(
                onClick = { sos.stop() },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = SosRed),
                shape = RoundedCornerShape(14.dp),
            ) { Text("İyiyim, durdur") }
        }
    }
}

/** Alan tarafta: başkası acil durumdaysa (ve cevaplandıysa) ana ekranda küçük kart. */
@Composable
fun SosIncomingCard(item: SosManager.Incoming) {
    val context = LocalContext.current
    val sos = App.instance.mesh.sos
    val me by LocationHelper.last.collectAsStateWithLifecycle()
    Surface(color = SosDark, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("🆘 ${item.name} acil durumda", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(
                listOfNotNull(
                    item.point?.let { p -> me?.let { LocationHelper.distanceAndDirection(it, p) } ?: "Konumu var" } ?: "Konum bilinmiyor",
                    "son haber ${minutesAgo(item.lastSeen)}",
                    if (item.coming) "sen yardıma gidiyorsun" else null,
                ).joinToString(" · "),
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item.point?.let { p ->
                    TextButton(onClick = { openMap(context, p, item.name) }) { Text("Haritada aç", color = Color.White) }
                }
                if (!item.coming) {
                    TextButton(onClick = { sos.acknowledge(item.id, coming = true) }) { Text("Yardıma gidiyorum", color = Color.White) }
                }
                TextButton(onClick = { sos.dismiss(item.id) }) { Text("Kapat", color = Color.White.copy(alpha = 0.7f)) }
            }
        }
    }
}

/** Gelen acil durum: tam ekran kırmızı uyarı (alarm çalarken). */
@Composable
fun SosAlertScreen(item: SosManager.Incoming) {
    val context = LocalContext.current
    val sos = App.instance.mesh.sos
    val me by LocationHelper.last.collectAsStateWithLifecycle()
    val transition = rememberInfiniteTransition(label = "alert")
    val glow by transition.animateFloat(0.35f, 0.9f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "g")
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(5_000)
            now = System.currentTimeMillis()
        }
    }
    BackHandler { sos.acknowledge(item.id, coming = false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(SosRed.copy(alpha = glow), SosDark, Color.Black)))
            .clickable(enabled = true, onClick = {}),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(32.dp))
            Text("🆘", fontSize = 72.sp)
            Text("ACİL DURUM", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                "${item.name} yardım istiyor",
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))

            Surface(color = Color.White.copy(alpha = 0.12f), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val p = item.point
                    Text(
                        when {
                            p == null -> "Konumu henüz bilinmiyor"
                            me == null -> "Konumu alındı · senin konumun bekleniyor"
                            else -> LocationHelper.distanceAndDirection(me!!, p)
                        },
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        listOfNotNull(
                            p?.let { "± ${it.accuracy} m" },
                            if (item.hops > 0) "${item.hops} telefon aracılığıyla ulaştı" else "doğrudan ulaştı",
                            "son haber ${minutesAgo(item.lastSeen).also { now }}",
                        ).joinToString(" · "),
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = { sos.acknowledge(item.id, coming = true) },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = SosRed),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
            ) { Text("Yardıma geliyorum", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                item.point?.let { p ->
                    OutlinedButton(
                        onClick = { openMap(context, p, item.name) },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.Map, contentDescription = null, tint = Color.White)
                        Text("  Haritada aç", color = Color.White)
                    }
                }
                OutlinedButton(
                    onClick = { sos.silence() },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.VolumeOff, contentDescription = null, tint = Color.White)
                    Text("  Sesi kapat", color = Color.White)
                }
            }
            TextButton(onClick = { sos.acknowledge(item.id, coming = false) }) {
                Text("Gördüm, gidemiyorum", color = Color.White.copy(alpha = 0.75f))
            }
        }
    }
}

private const val HOLD_MS = 2_000L
