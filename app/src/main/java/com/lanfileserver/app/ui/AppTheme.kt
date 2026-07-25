package com.lanfileserver.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF0F766E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = Color(0xFF134E4A),
    secondary = Color(0xFF475569),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E8F0),
    onSecondaryContainer = Color(0xFF1E293B),
    tertiary = Color(0xFFC2410C),
    onTertiary = Color.White,
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF172033),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF172033),
    surfaceVariant = Color(0xFFEEF2F4),
    onSurfaceVariant = Color(0xFF46515D),
    outline = Color(0xFF94A3B8),
    error = Color(0xFFB42318),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5EEAD4),
    onPrimary = Color(0xFF083B37),
    primaryContainer = Color(0xFF115E59),
    onPrimaryContainer = Color(0xFFCCFBF1),
    secondary = Color(0xFFCBD5E1),
    onSecondary = Color(0xFF273445),
    tertiary = Color(0xFFFDBA74),
    onTertiary = Color(0xFF5A2207),
    background = Color(0xFF111827),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF18212E),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF273445),
    onSurfaceVariant = Color(0xFFCBD5E1),
    outline = Color(0xFF64748B),
    error = Color(0xFFFDA29B),
)

@Composable
fun HotspotFileServerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(4.dp),
            small = RoundedCornerShape(6.dp),
            medium = RoundedCornerShape(8.dp),
            large = RoundedCornerShape(8.dp),
            extraLarge = RoundedCornerShape(8.dp),
        ),
        content = content,
    )
}

