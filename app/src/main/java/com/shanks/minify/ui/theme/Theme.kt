package com.shanks.minify.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// A cohesive dark palette — no jarring white background
private val DarkColorScheme = darkColorScheme(
    background        = Color(0xFF111111),   // near-black page background
    surface           = Color(0xFF1C1C1E),   // card surface
    surfaceVariant    = Color(0xFF2C2C2E),   // slightly lighter card variant
    primary           = Color(0xFF6E9EFF),   // soft blue accent
    onBackground      = Color(0xFFEAEAEA),
    onSurface         = Color(0xFFEAEAEA),
    onSurfaceVariant  = Color(0xFFAAAAAA),
    onPrimary         = Color(0xFF111111),
    primaryContainer  = Color(0xFF1E3A5F),
    onPrimaryContainer= Color(0xFFB8D4FF),
    error             = Color(0xFFFF6B6B),
    errorContainer    = Color(0xFF4A1515),
    onErrorContainer  = Color(0xFFFFB3B3),
)

@Composable
fun MinifyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}