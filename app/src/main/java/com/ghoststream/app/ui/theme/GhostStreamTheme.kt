package com.ghoststream.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val GhostStreamColors = darkColorScheme(
    primary = Color(0xFF8FE5D3),
    onPrimary = Color(0xFF071B18),
    primaryContainer = Color(0xFF17312D),
    onPrimaryContainer = Color(0xFFD3FFF4),
    secondary = Color(0xFFA8B8FF),
    onSecondary = Color(0xFF172348),
    secondaryContainer = Color(0xFF202C57),
    onSecondaryContainer = Color(0xFFE0E5FF),
    tertiary = Color(0xFFFFC888),
    onTertiary = Color(0xFF2A1800),
    background = Color(0xFF07080C),
    onBackground = Color(0xFFF3F5F8),
    surface = Color(0xFF12151C),
    onSurface = Color(0xFFF3F5F8),
    surfaceVariant = Color(0xFF1C2430),
    onSurfaceVariant = Color(0xFF95A4B5),
    outline = Color(0xFF31404E),
    surfaceTint = Color(0xFF8FE5D3),
    error = Color(0xFFFF8B87),
)

private val GhostStreamTypography = Typography(
    headlineLarge = TextStyle(
        fontSize = 32.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontSize = 28.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp,
    ),
    headlineSmall = TextStyle(
        fontSize = 24.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold,
    ),
)

@Composable
fun GhostStreamTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = GhostStreamColors,
        typography = GhostStreamTypography,
        content = content,
    )
}
