package com.dagmesh.app.ui

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dagmesh.app.App
import com.dagmesh.app.call.CallState
import com.dagmesh.app.media.MediaFiles
import kotlinx.coroutines.delay

private val CallRed = Color(0xFFE5484D)
private val CallGreen = Color(0xFF2FBF71)

/** Tam ekran arama ekranı (giden, gelen ve süren arama). */
@Composable
fun CallScreen(state: CallState) {
    val calls = App.instance.mesh.calls
    val context = LocalContext.current
    val muted by calls.muted.collectAsStateWithLifecycle()
    val speaker by calls.speaker.collectAsStateWithLifecycle()

    val acceptWithPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) calls.accept()
        else {
            Toast.makeText(context, "Mikrofon izni olmadan arama yapılamaz", Toast.LENGTH_SHORT).show()
            calls.reject()
        }
    }

    BackHandler { /* Arama sürerken geri tuşu ekranı kapatmasın. */ }

    val (peerId, name) = when (state) {
        is CallState.Outgoing -> state.peerId to state.name
        is CallState.Incoming -> state.peerId to state.name
        is CallState.Active -> state.peerId to state.name
        CallState.Idle -> return
    }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }

    val status = when (state) {
        is CallState.Outgoing -> "Aranıyor…"
        is CallState.Incoming -> "Gelen sesli arama"
        is CallState.Active -> MediaFiles.formatDuration(now - state.startedAt)
        CallState.Idle -> ""
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0E1A30), MaterialTheme.colorScheme.background, Color(0xFF07101C))
                )
            )
            // Arka plandaki ekrana dokunulmasın.
            .clickable(enabled = true, onClick = {}),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            Box(contentAlignment = Alignment.Center) {
                if (state !is CallState.Active) PulseRings(colorFor(peerId))
                Avatar(peerId, name, size = 128.dp)
            }
            Spacer(Modifier.height(28.dp))
            Text(name, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                status,
                style = MaterialTheme.typography.titleMedium,
                color = if (state is CallState.Active) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "İnternetsiz · telefondan telefona",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            Spacer(Modifier.weight(1f))

            when (state) {
                is CallState.Incoming -> Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    CallButton(Icons.Rounded.CallEnd, "Reddet", CallRed, big = true) { calls.reject() }
                    CallButton(Icons.Rounded.Call, "Cevapla", CallGreen, big = true) {
                        acceptWithPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }

                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        ToggleButton(
                            icon = if (muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                            label = if (muted) "Sessizde" else "Sessiz",
                            active = muted,
                            onClick = { calls.toggleMute() },
                        )
                        ToggleButton(
                            icon = Icons.AutoMirrored.Rounded.VolumeUp,
                            label = "Hoparlör",
                            active = speaker,
                            onClick = { calls.toggleSpeaker() },
                        )
                    }
                    Spacer(Modifier.height(36.dp))
                    CallButton(Icons.Rounded.CallEnd, "Kapat", CallRed, big = true) { calls.hangup() }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CallButton(icon: ImageVector, label: String, color: Color, big: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(if (big) 76.dp else 60.dp)
                .clip(CircleShape)
                .background(color)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(34.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun ToggleButton(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (active) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(28.dp),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Aranırken avatarın etrafında genişleyen halkalar. */
@Composable
private fun PulseRings(color: Color) {
    val transition = rememberInfiniteTransition(label = "rings")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
        label = "t",
    )
    Canvas(Modifier.size(240.dp)) {
        val base = 64.dp.toPx()
        val max = size.minDimension / 2f
        for (i in 0 until 3) {
            val phase = (t + i / 3f) % 1f
            drawCircle(
                color = color.copy(alpha = (1f - phase) * 0.45f),
                radius = base + (max - base) * phase,
                style = Stroke(2.dp.toPx()),
            )
        }
    }
}
