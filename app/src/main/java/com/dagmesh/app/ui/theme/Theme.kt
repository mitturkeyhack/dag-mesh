package com.dagmesh.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Gece mavisi dağ teması: koyu zemin, kor turuncusu vurgu, buzul turkuazı sinyal rengi.
val Night = Color(0xFF0A1220)
val NightSurface = Color(0xFF111B2E)
val NightSurface2 = Color(0xFF18253D)
val Ridge = Color(0xFF26364F)
val Ember = Color(0xFFFF8A3D)
val EmberDeep = Color(0xFF5A2A0E)
val Glacier = Color(0xFF3DD9C1)
val Mist = Color(0xFFE7EDF6)
val MistDim = Color(0xFF93A3BA)

private val scheme = darkColorScheme(
    primary = Ember,
    onPrimary = Color(0xFF1E0F04),
    primaryContainer = EmberDeep,
    onPrimaryContainer = Color(0xFFFFDCC7),
    secondary = Glacier,
    onSecondary = Color(0xFF00241E),
    background = Night,
    onBackground = Mist,
    surface = Night,
    onSurface = Mist,
    surfaceVariant = NightSurface2,
    onSurfaceVariant = MistDim,
    surfaceContainer = NightSurface,
    surfaceContainerHigh = NightSurface2,
    surfaceContainerHighest = Ridge,
    outline = Ridge,
    outlineVariant = Ridge,
    error = Color(0xFFFF6B6B),
)

private val base = Typography()
private val typography = base.copy(
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

val SectionStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)

@Composable
fun DagMeshTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
