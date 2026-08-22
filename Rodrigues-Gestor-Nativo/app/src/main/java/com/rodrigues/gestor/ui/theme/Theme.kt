package com.rodrigues.gestor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val GestorLightColors = lightColorScheme(
    primary = Color(0xFFE9151D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE8E9),
    onPrimaryContainer = Color(0xFF7A0007),
    secondary = Color(0xFF2878D0),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAF3FF),
    onSecondaryContainer = Color(0xFF123A69),
    tertiary = Color(0xFFF28C18),
    onTertiary = Color.White,
    error = Color(0xFFD91E27),
    onError = Color.White,
    errorContainer = Color(0xFFFFE9EA),
    onErrorContainer = Color(0xFF7A0007),
    background = Color(0xFFF7F7F8),
    onBackground = Color(0xFF161616),
    surface = Color.White,
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFF1F2F4),
    onSurfaceVariant = Color(0xFF666A70),
    outline = Color(0xFFB9BDC3),
    outlineVariant = Color(0xFFE2E4E8),
)

@Composable
fun RodriguesGestorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GestorLightColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
