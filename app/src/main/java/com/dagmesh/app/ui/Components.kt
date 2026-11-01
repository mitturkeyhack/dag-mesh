package com.dagmesh.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dagmesh.app.mesh.MeshManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val avatarColors = listOf(
    Color(0xFFFF8A3D), Color(0xFF3DD9C1), Color(0xFF7C9CFF), Color(0xFFFF6B9A),
    Color(0xFFFFC94D), Color(0xFF8BE06A), Color(0xFFB98CFF), Color(0xFF4DC3FF),
)

fun colorFor(id: String): Color = avatarColors[Math.floorMod(id.hashCode(), avatarColors.size)]

fun initials(name: String): String =
    name.trim().split(" ").filter { it.isNotBlank() }.take(2)
        .joinToString("") { it.first().uppercase() }.ifEmpty { "?" }

@Composable
fun Avatar(id: String, name: String, size: Dp = 46.dp, online: Boolean? = null) {
    val color = colorFor(id)
    Box(Modifier.size(size)) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.18f))
                .border(1.5.dp, color.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                initials(name),
                color = color,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.36f).sp,
            )
        }
        if (online == true) {
            // Çevrimiçi noktası: arka plan halkası içinde turkuaz nokta
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.3f)
                    .border(3.dp, MaterialTheme.colorScheme.background, CircleShape)
                    .background(MaterialTheme.colorScheme.secondary, CircleShape),
            )
        }
    }
}

/** 1-3 çubukluk sinyal göstergesi: doğrudan bağlı = 3 çubuk. */
@Composable
fun SignalBars(hops: Int, modifier: Modifier = Modifier) {
    val active = (4 - hops).coerceIn(1, 3)
    val on = if (hops <= 1) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.outline
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        listOf(8.dp, 12.dp, 16.dp).forEachIndexed { i, h ->
            Box(
                Modifier
                    .width(4.dp)
                    .height(h)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (i < active) on else off)
            )
        }
    }
}

fun peerStatus(peer: MeshManager.Peer?): String = when {
    peer == null -> "Menzil dışında · mesajlar sırada bekler"
    peer.isDirect -> "Doğrudan bağlı" + (peer.via?.let { " · $it" } ?: "")
    else -> "${peer.hops - 1} telefon aracılığıyla ulaşılıyor"
}

fun formatTime(ts: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ts }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    val pattern = if (sameDay) "HH:mm" else "d MMM"
    return SimpleDateFormat(pattern, Locale("tr")).format(Date(ts))
}

/** Uygulama logosu: dağ ve üstünde sinyal dalgaları. */
@Composable
fun MountainLogo(modifier: Modifier = Modifier) {
    val ember = MaterialTheme.colorScheme.primary
    val glacier = MaterialTheme.colorScheme.secondary
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val mountain = Path().apply {
            moveTo(w * 0.06f, h * 0.92f)
            lineTo(w * 0.36f, h * 0.48f)
            lineTo(w * 0.50f, h * 0.68f)
            lineTo(w * 0.63f, h * 0.53f)
            lineTo(w * 0.94f, h * 0.92f)
            close()
        }
        drawPath(mountain, ember)
        val snow = Path().apply {
            moveTo(w * 0.36f, h * 0.48f)
            lineTo(w * 0.445f, h * 0.60f)
            lineTo(w * 0.39f, h * 0.575f)
            lineTo(w * 0.335f, h * 0.61f)
            lineTo(w * 0.29f, h * 0.565f)
            close()
        }
        drawPath(snow, Color(0xFFFFD9C2))
        val stroke = Stroke(width = w * 0.045f, cap = StrokeCap.Round)
        val cx = w * 0.63f
        listOf(0.13f, 0.24f).forEach { r ->
            drawArc(
                color = glacier,
                startAngle = 215f,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = Offset(cx - w * r, h * 0.40f - w * r),
                size = androidx.compose.ui.geometry.Size(w * r * 2, w * r * 2),
                style = stroke,
            )
        }
    }
}
