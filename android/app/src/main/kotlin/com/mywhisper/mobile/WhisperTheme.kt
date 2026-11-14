package com.mywhisper.mobile

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// A restrained ink-and-teal palette keeps long settings screens readable.
internal val WhisperLightColors = lightColorScheme(
    primary = Color(0xFF176657),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEAF4EF),
    onPrimaryContainer = Color(0xFF164E40),
    secondary = Color(0xFF576D66),
    secondaryContainer = Color(0xFFEAF4EF),
    onSecondaryContainer = Color(0xFF164E40),
    surfaceContainer = Color(0xFFFFFFFF),
    background = Color(0xFFF6F7F8),
    surface = Color.White,
    onSurface = Color(0xFF20292B),
    onSurfaceVariant = Color(0xFF5F6F70),
)

internal val WhisperDarkColors = darkColorScheme(
    primary = Color(0xFF80D9BD),
    onPrimary = Color(0xFF103B30),
    primaryContainer = Color(0xFF223A33),
    onPrimaryContainer = Color(0xFFEAF4EF),
    secondary = Color(0xFFACCCC0),
    secondaryContainer = Color(0xFF2B4A3F),
    onSecondaryContainer = Color(0xFFEAF4EF),
    surfaceContainer = Color(0xFF202629),
    background = Color(0xFF171B1D),
    surface = Color(0xFF202629),
    onSurface = Color(0xFFEFF4F3),
    onSurfaceVariant = Color(0xFFA5B5B7),
)

/** Follow the device palette on Android 12+, retaining a fallback for older devices. */
@Composable
internal fun whisperColorScheme(dark: Boolean, black: Boolean = false): androidx.compose.material3.ColorScheme {
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(LocalContext.current)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(LocalContext.current)
        dark -> WhisperDarkColors
        else -> WhisperLightColors
    }
    // Wallpaper colors remain the accents. Neutral dark surfaces keep dense
    // settings readable and avoid tinting every nested panel the accent color.
    if (dark && black) return colors.copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceBright = Color(0xFF303030),
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF0D0D0D),
        surfaceContainer = Color(0xFF141414),
        surfaceContainerHigh = Color(0xFF1B1B1B),
        surfaceContainerHighest = Color(0xFF242424),
        surfaceVariant = Color(0xFF242424),
        onBackground = Color(0xFFE8E8E8),
        onSurface = Color(0xFFE8E8E8),
        onSurfaceVariant = Color(0xFFBDBDBD),
        outline = Color(0xFF929292),
        outlineVariant = Color(0xFF424242),
    )
    return if (dark) colors.copy(
        background = Color(0xFF121416),
        surface = Color(0xFF121416),
        surfaceDim = Color(0xFF121416),
        surfaceBright = Color(0xFF353A3F),
        surfaceContainerLowest = Color(0xFF0D0F11),
        surfaceContainerLow = Color(0xFF1B1E21),
        surfaceContainer = Color(0xFF202428),
        surfaceContainerHigh = Color(0xFF272C30),
        surfaceContainerHighest = Color(0xFF30363B),
        surfaceVariant = Color(0xFF2B3035),
        onBackground = Color(0xFFE7E9EC),
        onSurface = Color(0xFFE7E9EC),
        onSurfaceVariant = Color(0xFFBAC1C8),
        outline = Color(0xFF89939C),
        outlineVariant = Color(0xFF424B53),
    ) else colors
}
