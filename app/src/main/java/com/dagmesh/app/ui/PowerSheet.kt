package com.dagmesh.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dagmesh.app.App
import com.dagmesh.app.data.PowerMode
import com.dagmesh.app.mesh.ScanState

private val EcoGreen = Color(0xFF5FD68A)

fun powerIcon(mode: PowerMode): ImageVector = when (mode) {
    PowerMode.PERFORMANCE -> Icons.Rounded.Bolt
    PowerMode.BALANCED -> Icons.Rounded.BatteryFull
    PowerMode.SAVER -> Icons.Rounded.Eco
}

@Composable
fun powerTint(mode: PowerMode): Color = when (mode) {
    PowerMode.PERFORMANCE -> MaterialTheme.colorScheme.primary
    PowerMode.BALANCED -> MaterialTheme.colorScheme.secondary
    PowerMode.SAVER -> EcoGreen
}

/** Pil modu seçimi. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PowerSheet(onDismiss: () -> Unit) {
    val app = App.instance
    val mode by app.settings.powerMode.collectAsStateWithLifecycle()
    val scan by app.mesh.power.scanState.collectAsStateWithLifecycle()
    val lowBattery by app.mesh.power.lowBatteryOverride.collectAsStateWithLifecycle()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Pil modu", style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            when (scan) {
                                ScanState.CONTINUOUS, ScanState.CYCLE_ON -> MaterialTheme.colorScheme.secondary
                                ScanState.CYCLE_OFF -> MaterialTheme.colorScheme.primary
                                ScanState.OFF -> MaterialTheme.colorScheme.outline
                            }
                        )
                )
                Text(
                    when (scan) {
                        ScanState.CONTINUOUS -> "Şu an: sürekli tarıyor"
                        ScanState.CYCLE_ON -> "Şu an: aralıklı tarama · tarıyor"
                        ScanState.CYCLE_OFF -> "Şu an: aralıklı tarama · dinleniyor"
                        ScanState.OFF -> "Ağ kapalı"
                    } + if (lowBattery) " · pil düşük, tasarruf devrede" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PowerMode.entries.forEach { option ->
                val selected = option == mode
                Surface(
                    onClick = { app.settings.setPowerMode(option) },
                    shape = RoundedCornerShape(20.dp),
                    color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(
                        if (selected) 1.5.dp else 1.dp,
                        if (selected) powerTint(option) else MaterialTheme.colorScheme.outline,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(powerTint(option).copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(powerIcon(option), contentDescription = null, tint = powerTint(option))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(option.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                option.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (selected) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = "Seçili", tint = powerTint(option))
                        }
                    }
                }
            }
            Text(
                "Uygulama ekrandayken, arama sürerken ve dosya giderken her modda tam hızda çalışır. " +
                    "Kurulu bağlantılar hiçbir modda kesilmez.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
