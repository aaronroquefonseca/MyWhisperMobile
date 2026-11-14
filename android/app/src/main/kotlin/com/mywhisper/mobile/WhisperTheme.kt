package com.mywhisper.mobile

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// A restrained ink-and-blue palette keeps long settings screens readable.
internal val WhisperLightColors = lightColorScheme(
    primary = Color(0xFF245BC4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDFE9FF),
    onPrimaryContainer = Color(0xFF123471),
    secondary = Color(0xFF52627D),
    secondaryContainer = Color(0xFFDFE9FF),
    onSecondaryContainer = Color(0xFF123471),
    surfaceContainer = Color(0xFFE8EEF8),
    background = Color(0xFFF2F5FA),
    surface = Color.White,
    onSurface = Color(0xFF192437),
    onSurfaceVariant = Color(0xFF4D5B70),
)

internal val WhisperDarkColors = darkColorScheme(
    primary = Color(0xFFADC6FF),
    onPrimary = Color(0xFF002D70),
    primaryContainer = Color(0xFF204782),
    onPrimaryContainer = Color(0xFFDFE9FF),
    secondary = Color(0xFFBAC8E0),
    secondaryContainer = Color(0xFF304666),
    onSecondaryContainer = Color(0xFFDFE9FF),
    surfaceContainer = Color(0xFF192331),
    background = Color(0xFF101722),
    surface = Color(0xFF192331),
    onSurface = Color(0xFFE4EAF5),
    onSurfaceVariant = Color(0xFFBBC7DA),
)
