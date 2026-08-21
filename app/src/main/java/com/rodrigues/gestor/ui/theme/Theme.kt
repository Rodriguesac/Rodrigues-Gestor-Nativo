package com.rodrigues.gestor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RodriguesDarkColors = darkColorScheme(
    primary = Color(0xFF9EDB47),
    onPrimary = Color(0xFF142000),
    primaryContainer = Color(0xFF263719),
    onPrimaryContainer = Color(0xFFDDF5B8),
    secondary = Color(0xFF8DBDFF),
    onSecondary = Color(0xFF08203A),
    secondaryContainer = Color(0xFF14283E),
    onSecondaryContainer = Color(0xFFD6E8FF),
    tertiary = Color(0xFFFFB86B),
    onTertiary = Color(0xFF3D2100),
    error = Color(0xFFFF6B65),
    onError = Color(0xFF4A0002),
    background = Color(0xFF0B1218),
    onBackground = Color(0xFFE9EEF3),
    surface = Color(0xFF121A21),
    onSurface = Color(0xFFE9EEF3),
    surfaceVariant = Color(0xFF1A242D),
    onSurfaceVariant = Color(0xFFAAB5BF),
    outline = Color(0xFF71808C),
    outlineVariant = Color(0xFF27333D),
)

@Composable
fun RodriguesGestorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RodriguesDarkColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
