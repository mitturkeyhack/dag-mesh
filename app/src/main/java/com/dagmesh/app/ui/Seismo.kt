package com.dagmesh.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Dağ Mesh'in afet simgesi: deprem dalgası (sismograf çizgisi). */
val SeismoIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Seismo",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(2f, 12f)
            lineTo(6f, 12f)
            lineTo(8.2f, 6f)
            lineTo(11.8f, 19f)
            lineTo(14.4f, 9.5f)
            lineTo(16f, 12f)
            lineTo(22f, 12f)
        }
    }.build()
}

private val AfetInk = Color(0xFF2B1406)

/** Ana sayfada sağ altta, alt menünün üstünde duran turuncu "Afet" düğmesi. */
@Composable
fun AfetFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = AfetInk,
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
        icon = { Icon(SeismoIcon, contentDescription = null, modifier = Modifier.size(24.dp)) },
        text = { Text("Afet", fontWeight = FontWeight.Bold) },
        modifier = modifier,
    )
}

/**
 * Ana ekrandaki "SOS gönder" kısayolu için onay: yanlışlıkla dokunmaya karşı 5 sn geri sayım,
 * süre dolunca SOS kendiliğinden gider.
 */
@Composable
fun SosCountdownDialog(onSend: () -> Unit, onCancel: () -> Unit) {
    var left by remember { mutableIntStateOf(5) }
    LaunchedEffect(Unit) {
        while (left > 0) {
            delay(1000)
            left--
        }
        onSend()
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("SOS $left sn sonra gidiyor", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Konumun yakındaki herkese alarmla gönderilecek.")
                Text("Yanlışlıkla bastıysan İptal'e dokun.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = onSend, colors = ButtonDefaults.buttonColors(containerColor = SosRed, contentColor = Color.White)) {
                Text("Şimdi gönder", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("İptal") } },
    )
}
