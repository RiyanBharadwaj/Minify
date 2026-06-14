package com.shanks.minify.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ── Accent options ────────────────────────────────────────────────────────────

enum class AppAccent(val label: String, val color: Color) {
    PURPLE( "Purple",  Color(0xFFBF5AF2)),
    CYAN(   "Cyan",    Color(0xFF32D2F0)),
    MAGENTA("Magenta", Color(0xFFE040FB)),
    BLUE(   "Blue",    Color(0xFF0A84FF)),
    GREEN(  "Green",   Color(0xFF30D158)),
    ORANGE( "Orange",  Color(0xFFFF9F0A)),
    CUSTOM( "Custom",  Color(0xFFBF5AF2)),  // color overridden at runtime
}

val LocalAppAccent      = staticCompositionLocalOf { AppAccent.BLUE }
val LocalAppAccentColor = staticCompositionLocalOf { Color(0xFF0A84FF) }

private fun buildDarkScheme(accent: Color) = darkColorScheme(
    primary              = accent,
    onPrimary            = Color.White,
    primaryContainer     = accent.copy(alpha = 0.20f),
    onPrimaryContainer   = Color.White,
    secondary            = accent.copy(alpha = 0.70f),
    onSecondary          = Color.White,
    secondaryContainer   = Color(0xFF251E35),
    onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary             = Color(0xFF32D2F0),
    onTertiary           = Color(0xFF003544),
    background           = Color(0xFF0D0B14),
    onBackground         = Color.White,
    surface              = Color(0xFF1A1625),
    onSurface            = Color.White,
    surfaceVariant       = Color(0xFF1E1830),
    onSurfaceVariant     = Color(0xFFCAC4D0),
    outline              = accent.copy(alpha = 0.40f),
    outlineVariant       = Color(0xFF49454F),
    error                = Color(0xFFFF453A),
    onError              = Color.White,
    errorContainer       = Color(0xFF2A0A0A),
    onErrorContainer     = Color(0xFFFFB4AB),
)

@Composable
fun MinifyTheme(
    accentColor: Color,
    accent: AppAccent,
    content: @Composable () -> Unit
) {
    val scheme = buildDarkScheme(accentColor)
    CompositionLocalProvider(
        LocalAppAccent      provides accent,
        LocalAppAccentColor provides accentColor,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography  = Typography,
            content     = content
        )
    }
}