package com.rodrigues.gestor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RodriguesColors = lightColorScheme(
    primary = Color(0xFF4B0082),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF1E4FF),
    onPrimaryContainer = Color(0xFF2E004F),
    secondary = Color(0xFF82C91E),
    onSecondary = Color(0xFF172000),
    secondaryContainer = Color(0xFFEAF7D3),
    onSecondaryContainer = Color(0xFF263B00),
    tertiary = Color(0xFF191919),
    error = Color(0xFFB3261E),
    background = Color(0xFFF4F5F7),
    surface = Color.White,
    surfaceVariant = Color(0xFFF1EDF5),
    outlineVariant = Color(0xFFE6E0EA),
)

@Composable
fun RodriguesGestorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RodriguesColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
