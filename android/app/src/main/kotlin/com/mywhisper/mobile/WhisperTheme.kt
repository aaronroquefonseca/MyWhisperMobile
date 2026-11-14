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
internal fun whisperColorScheme(dark: Boolean) = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(LocalContext.current)
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(LocalContext.current)
    dark -> WhisperDarkColors
    else -> WhisperLightColors
}
