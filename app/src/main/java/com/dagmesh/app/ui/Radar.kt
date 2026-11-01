package com.dagmesh.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.dagmesh.app.mesh.MeshManager
import kotlin.math.cos
import kotlin.math.sin

/**
 * Yakındakileri gösteren radar.
 * İç halka: doğrudan bağlı telefonlar. Dış halkalar: zincirle (aracılarla) ulaşılanlar.
 */
@Composable
fun Radar(peers: List<MeshManager.Peer>, running: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "radar")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing)),
        label = "sweep",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "pulse",
    )

    val measurer = rememberTextMeasurer()
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val ring = MaterialTheme.colorScheme.outline
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurface)

    Canvas(modifier.aspectRatio(1f)) {
        val c = center
        val r = size.minDimension / 2f

        // Halkalar ve eksenler
        for (i in 1..3) {
            drawCircle(ring, radius = r * i / 3f, center = c, style = Stroke(1.dp.toPx()))
        }
        drawLine(ring.copy(alpha = 0.5f), Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.dp.toPx())
        drawLine(ring.copy(alpha = 0.5f), Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.dp.toPx())

        // Dönen tarama ışını
        if (running) {
            rotate(sweep, c) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        0f to Color.Transparent,
                        0.78f to Color.Transparent,
                        1f to secondary.copy(alpha = 0.38f),
                        center = c,
                    ),
                    radius = r,
                    center = c,
                )
                drawLine(
                    secondary.copy(alpha = 0.8f),
                    c,
                    Offset(c.x + r, c.y),
                    1.5.dp.toPx(),
                )
            }
        }

        // Ben (merkez)
        drawCircle(primary.copy(alpha = 0.25f), radius = 14.dp.toPx() * pulse, center = c)
        drawCircle(primary, radius = 6.dp.toPx(), center = c)

        // Diğer telefonlar
        peers.forEach { p ->
            val angle = Math.toRadians(Math.floorMod(p.id.hashCode(), 360).toDouble())
            val dist = r * when (p.hops) {
                1 -> 0.36f
                2 -> 0.64f
                else -> 0.88f
            }
            val pos = Offset(c.x + (dist * cos(angle)).toFloat(), c.y + (dist * sin(angle)).toFloat())
            val dotColor = if (p.isDirect) secondary else primary
            drawCircle(dotColor.copy(alpha = 0.22f), radius = 12.dp.toPx() * pulse, center = pos)
            drawCircle(dotColor, radius = 5.dp.toPx(), center = pos)

            val layout = measurer.measure(p.name.take(12), labelStyle)
            val x = (pos.x - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
            val y = (pos.y + 9.dp.toPx()).coerceAtMost(size.height - layout.size.height)
            drawText(layout, topLeft = Offset(x, y))
        }
    }
}
