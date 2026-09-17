package com.rodrigues.gestor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val AcaiPurple = Color(0xFF5A078F)
val AcaiPurpleDark = Color(0xFF3F006B)
val AcaiPurpleSoft = Color(0xFFF4ECF8)
val RodriguesLime = Color(0xFF79CF13)
val RodriguesLimeDark = Color(0xFF4F8A0A)
val WarmBackground = Color(0xFFF7F7F9)
val Ink = Color(0xFF1D1721)
val MutedInk = Color(0xFF6C6670)
val WarningOrange = Color(0xFFF59E0B)
val DestructiveRed = Color(0xFFD92D20)

private val GestorLightColors = lightColorScheme(
    primary = AcaiPurple,
    onPrimary = Color.White,
    primaryContainer = AcaiPurpleSoft,
    onPrimaryContainer = AcaiPurpleDark,
    secondary = RodriguesLimeDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEEF8E0),
    onSecondaryContainer = Color(0xFF274600),
    tertiary = WarningOrange,
    onTertiary = Ink,
    error = DestructiveRed,
    onError = Color.White,
    errorContainer = Color(0xFFFFEDEB),
    onErrorContainer = Color(0xFF7A140F),
    background = WarmBackground,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF2F0F4),
    onSurfaceVariant = MutedInk,
    outline = Color(0xFFC9C3CC),
    outlineVariant = Color(0xFFE7E3E9),
)

private val GestorTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, fontSize = 30.sp, lineHeight = 35.sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, fontSize = 26.sp, lineHeight = 31.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, lineHeight = 27.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, lineHeight = 25.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 21.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 19.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 13.sp, lineHeight = 17.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 15.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 10.sp, lineHeight = 13.sp),
)

private val GestorShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(9.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
)

@Composable
fun RodriguesGestorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GestorLightColors,
        typography = GestorTypography,
        shapes = GestorShapes,
        content = content,
    )
}
