package com.jarvis.assistant.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Iron Man tarzı renkler: siyah zemin, camgöbeği vurgu.
val JarvisCyan = Color(0xFF00E5FF)
val JarvisCyanDim = Color(0xFF00838F)
val JarvisBlack = Color(0xFF000000)
val JarvisSurface = Color(0xFF0B1418)
val JarvisSurfaceHigh = Color(0xFF112229)

private val JarvisColors = darkColorScheme(
    primary = JarvisCyan,
    onPrimary = JarvisBlack,
    primaryContainer = JarvisCyanDim,
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF4DD0E1),
    onSecondary = JarvisBlack,
    background = JarvisBlack,
    onBackground = Color(0xFFE0F7FA),
    surface = JarvisBlack,
    onSurface = Color(0xFFE0F7FA),
    surfaceVariant = JarvisSurface,
    onSurfaceVariant = Color(0xFFB2EBF2),
    surfaceContainer = JarvisSurface,
    surfaceContainerHigh = JarvisSurfaceHigh,
    surfaceContainerHighest = JarvisSurfaceHigh,
    outline = JarvisCyanDim,
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = JarvisColors, content = content)
}
