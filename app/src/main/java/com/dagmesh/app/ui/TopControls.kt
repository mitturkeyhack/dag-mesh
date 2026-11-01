package com.dagmesh.app.ui

import android.os.BatteryManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dagmesh.app.data.PowerMode
import kotlinx.coroutines.delay

private val Amber = Color(0xFFFFB25B)

/** Telefonun şarj yüzdesi (dakikada bir güncellenir). */
@Composable
private fun batteryLevel(): Int {
    val context = LocalContext.current
    val level by produceState(initialValue = -1) {
        while (true) {
            value = try {
                context.getSystemService(BatteryManager::class.java)
                    .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            } catch (e: Exception) {
                -1
            }
            delay(60_000)
        }
    }
    return level
}

/** Yatay, yuvarlak köşeli modern pil çizimi. [fill] 0..1 arası doluluk. */
@Composable
fun BatteryGlyph(fill: Float, color: Color, modifier: Modifier = Modifier) {
    val animatedFill by animateFloatAsState(fill.coerceIn(0f, 1f), label = "fill")
    val outline = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    Canvas(modifier.size(width = 24.dp, height = 13.dp)) {
        val stroke = 1.4.dp.toPx()
        val capW = 2.2.dp.toPx()
        val bodyW = size.width - capW - 1.dp.toPx()
        val r = 3.5.dp.toPx()

        // Gövde
        drawRoundRect(
            color = outline,
            size = Size(bodyW, size.height),
            cornerRadius = CornerRadius(r, r),
            style = Stroke(stroke),
        )
        // Uç
        drawRoundRect(
            color = outline,
            topLeft = Offset(bodyW + 1.dp.toPx(), size.height * 0.32f),
            size = Size(capW, size.height * 0.36f),
            cornerRadius = CornerRadius(capW, capW),
        )
        // Doluluk
        val inset = stroke + 1.2.dp.toPx()
        val innerW = (bodyW - inset * 2) * animatedFill.coerceAtLeast(0.08f)
        drawRoundRect(
            color = color,
            topLeft = Offset(inset, inset),
            size = Size(innerW, size.height - inset * 2),
            cornerRadius = CornerRadius(r * 0.55f, r * 0.55f),
        )
    }
}

/** Üst çubuktaki pil modu kapsülü: gerçek şarj seviyesi + mod rengi + mod adı. */
@Composable
fun PowerModeChip(mode: PowerMode, onClick: () -> Unit) {
    val level = batteryLevel()
    val tint by animateColorAsState(powerTint(mode), label = "tint")
    Row(
        Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BatteryGlyph(fill = if (level >= 0) level / 100f else 0.7f, color = tint)
        Text(
            mode.title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Ağı açıp kapatan küçük yuvarlak düğme. */
@Composable
fun NetworkToggle(running: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(
        if (running) MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainer,
        label = "bg",
    )
    val fg by animateColorAsState(
        if (running) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "fg",
    )
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.PowerSettingsNew,
            contentDescription = if (running) "Ağı kapat" else "Ağı aç",
            tint = fg,
            modifier = Modifier.size(19.dp),
        )
    }
}

/** Ana ekrandaki ince, degrade "Paylaş" kapsülü. */
@Composable
fun ShareButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .shadow(14.dp, shape, ambientColor = MaterialTheme.colorScheme.primary, spotColor = MaterialTheme.colorScheme.primary)
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary, Amber)))
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 18.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.IosShare,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Paylaş",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}
