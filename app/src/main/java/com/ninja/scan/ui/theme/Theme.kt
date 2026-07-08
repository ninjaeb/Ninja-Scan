package com.ninja.scan.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Same navy + crimson palette as the launcher icon and the website/store
// listing (docs/index.html's --mask/--accent), so the app itself carries
// the same branding rather than a generic Material blue.
private val LightColors = lightColorScheme(
    primary = Color(0xFFE94560),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDADD),
    onPrimaryContainer = Color(0xFF400010),
    secondary = Color(0xFF1A1A2E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD9E2FF),
    onSecondaryContainer = Color(0xFF1A1A2E),
    tertiary = Color(0xFF5B5B6B),
    background = Color(0xFFF7F5F0),
    onBackground = Color(0xFF1B1B2A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1B2A),
    surfaceVariant = Color(0xFFE4E1D8),
    onSurfaceVariant = Color(0xFF5B5B6B),
    outline = Color(0xFF9A9AAE),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF6B81),
    onPrimary = Color(0xFF3D0012),
    primaryContainer = Color(0xFF5C0A22),
    onPrimaryContainer = Color(0xFFFFDADD),
    secondary = Color(0xFF9FB3FF),
    onSecondary = Color(0xFF0B1024),
    secondaryContainer = Color(0xFF1A1A2E),
    onSecondaryContainer = Color(0xFFD9E2FF),
    tertiary = Color(0xFF9A9AAE),
    background = Color(0xFF101018),
    onBackground = Color(0xFFEDEEF2),
    surface = Color(0xFF1B1B2C),
    onSurface = Color(0xFFEDEEF2),
    surfaceVariant = Color(0xFF2A2A3D),
    onSurfaceVariant = Color(0xFF9A9AAE),
    outline = Color(0xFF5B5B6B),
)

@Composable
fun DocScannerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Deliberately not using dynamic (Material You) color: it derives the
    // palette from the user's wallpaper on API 31+, which would override
    // this branding on most current devices.
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colorScheme, content = content)
}
