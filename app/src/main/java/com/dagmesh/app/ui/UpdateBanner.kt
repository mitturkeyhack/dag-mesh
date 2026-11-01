package com.dagmesh.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dagmesh.app.update.Updater

/** Ana ekranda, sadece güncelleme sürerken veya onay gerektiğinde görünen ince şerit. */
@Composable
fun UpdateBanner() {
    val context = LocalContext.current
    val state by Updater.state.collectAsStateWithLifecycle()
    val (title, percent) = when (val s = state) {
        is Updater.State.Downloading -> "Güncelleme indiriliyor · v${s.version}" to s.percent
        is Updater.State.Installing -> "Güncelleme kuruluyor · v${s.version}" to -1
        is Updater.State.NeedsConfirm -> "v${s.version} hazır · yüklemek için onay ver" to -2
        else -> return
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    Icons.Rounded.SystemUpdate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(22.dp),
                )
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (percent == -2) {
                    Button(onClick = { Updater.confirm(context) }, shape = RoundedCornerShape(14.dp)) { Text("Yükle") }
                }
            }
            when {
                percent >= 0 -> LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)),
                    color = MaterialTheme.colorScheme.secondary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainer,
                )
                percent == -1 -> LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)),
                    color = MaterialTheme.colorScheme.secondary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainer,
                )
                else -> Text(
                    "Bu ilk otomatik güncelleme. Bundan sonrakiler hiçbir şey sormadan kendiliğinden kurulacak.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
