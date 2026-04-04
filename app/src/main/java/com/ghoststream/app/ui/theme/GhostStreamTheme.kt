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
    primary = Color(0xFF83DFC0),
    onPrimary = Color(0xFF081612),
    primaryContainer = Color(0xFF13221E),
    onPrimaryContainer = Color(0xFFD9FFF0),
    secondary = Color(0xFF90B5C5),
    onSecondary = Color(0xFF0D1A20),
    secondaryContainer = Color(0xFF17242B),
    onSecondaryContainer = Color(0xFFD6E7EE),
    tertiary = Color(0xFFE2C07A),
    onTertiary = Color(0xFF221707),
    background = Color(0xFF060709),
    onBackground = Color(0xFFF4F6F7),
    surface = Color(0xFF101317),
    onSurface = Color(0xFFF4F6F7),
    surfaceVariant = Color(0xFF171C21),
    onSurfaceVariant = Color(0xFF9AA7B0),
    outline = Color(0xFF29323A),
    surfaceTint = Color(0xFF83DFC0),
    error = Color(0xFFFF8B87),
)

private val GhostStreamTypography = Typography(
    headlineLarge = TextStyle(
        fontSize = 34.sp,
        lineHeight = 38.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.7).sp,
    ),
    headlineMedium = TextStyle(
        fontSize = 28.sp,
        lineHeight = 31.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.6).sp,
    ),
    headlineSmall = TextStyle(
        fontSize = 22.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleLarge = TextStyle(
        fontSize = 21.sp,
        lineHeight = 25.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
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
