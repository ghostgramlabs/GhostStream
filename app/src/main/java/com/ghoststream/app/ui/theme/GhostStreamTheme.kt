package com.ghoststream.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val GhostStreamColors = darkColorScheme(
    primary = Color(0xFF87E6FF),
    onPrimary = Color(0xFF03212C),
    primaryContainer = Color(0xFF14384A),
    onPrimaryContainer = Color(0xFFD5F6FF),
    secondary = Color(0xFF8CB9FF),
    onSecondary = Color(0xFF0E2244),
    background = Color(0xFF04070C),
    onBackground = Color(0xFFF4F7FC),
    surface = Color(0xFF0C121C),
    onSurface = Color(0xFFF4F7FC),
    surfaceVariant = Color(0xFF162132),
    onSurfaceVariant = Color(0xFF9BA8BC),
    error = Color(0xFFFF827F),
)

@Composable
fun GhostStreamTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = GhostStreamColors,
        typography = Typography(),
        content = content,
    )
}

