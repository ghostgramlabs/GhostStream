package com.ghostgramlabs.directserve.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.ghoststream.core.model.ThemeMode

private val GhostStreamDarkColorScheme = darkColorScheme(
    primary = GhostDarkAccent,
    onPrimary = GhostDarkAccentForeground,
    primaryContainer = GhostDarkAccentPressed,
    onPrimaryContainer = GhostDarkAccentForeground,
    secondary = GhostDarkTextSecondary,
    onSecondary = GhostDarkBackground,
    secondaryContainer = GhostDarkCard,
    onSecondaryContainer = GhostDarkTextPrimary,
    tertiary = GhostDarkMuted,
    onTertiary = GhostDarkBackground,
    tertiaryContainer = GhostDarkSurface,
    onTertiaryContainer = GhostDarkTextPrimary,
    background = GhostDarkBackground,
    onBackground = GhostDarkTextPrimary,
    surface = GhostDarkSurface,
    onSurface = GhostDarkTextPrimary,
    surfaceVariant = GhostDarkCard,
    onSurfaceVariant = GhostDarkTextSecondary,
    outline = GhostDarkBorder,
    outlineVariant = GhostDarkBorder,
    surfaceTint = GhostDarkAccent,
    error = GhostDarkAccentPressed,
    onError = GhostDarkAccentForeground,
    errorContainer = GhostDarkCard,
    onErrorContainer = GhostDarkTextPrimary,
)

private val GhostStreamLightColorScheme = lightColorScheme(
    primary = GhostLightAccent,
    onPrimary = GhostLightAccentForeground,
    primaryContainer = GhostLightAccentPressed,
    onPrimaryContainer = GhostLightAccentForeground,
    secondary = GhostLightTextSecondary,
    onSecondary = GhostLightBackground,
    secondaryContainer = GhostLightCard,
    onSecondaryContainer = GhostLightTextPrimary,
    tertiary = GhostLightMuted,
    onTertiary = GhostLightBackground,
    tertiaryContainer = GhostLightSurface,
    onTertiaryContainer = GhostLightTextPrimary,
    background = GhostLightBackground,
    onBackground = GhostLightTextPrimary,
    surface = GhostLightSurface,
    onSurface = GhostLightTextPrimary,
    surfaceVariant = GhostLightCard,
    onSurfaceVariant = GhostLightTextSecondary,
    outline = GhostLightBorder,
    outlineVariant = GhostLightBorder,
    surfaceTint = GhostLightAccent,
    error = GhostLightAccentPressed,
    onError = GhostLightAccentForeground,
    errorContainer = GhostLightCard,
    onErrorContainer = GhostLightTextPrimary,
)

private val GhostStreamTypography = Typography(
    headlineLarge = TextStyle(
        fontSize = 30.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.4).sp,
    ),
    headlineMedium = TextStyle(
        fontSize = 26.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.3).sp,
    ),
    headlineSmall = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Medium,
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 21.sp,
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
        fontWeight = FontWeight.Medium,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
    ),
)

val ColorScheme.cardContainer: Color
    get() = surfaceVariant

val ColorScheme.mutedText: Color
    get() = tertiary

val ColorScheme.borderColor: Color
    get() = outline

@Composable
fun GhostStreamTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val useDarkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val colorScheme = if (useDarkTheme) GhostStreamDarkColorScheme else GhostStreamLightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !useDarkTheme
                isAppearanceLightNavigationBars = !useDarkTheme
            }
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = GhostStreamTypography,
        content = content,
    )
}
