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

val AcaiPurple = Color(0xFFEA1D2C)
val AcaiPurpleDark = Color(0xFFB41420)
val AcaiPurpleSoft = Color(0xFFFFECEE)
val RodriguesLime = Color(0xFF259B53)
val RodriguesLimeDark = Color(0xFF208348)
val WarmBackground = Color(0xFFF7F7F7)
val Ink = Color(0xFF252525)
val MutedInk = Color(0xFF717171)
val WarningOrange = Color(0xFFF59E0B)
val DestructiveRed = Color(0xFFD92D20)

private val GestorLightColors = lightColorScheme(
    primary = AcaiPurple,
    onPrimary = Color.White,
    primaryContainer = AcaiPurpleSoft,
    onPrimaryContainer = AcaiPurpleDark,
    secondary = RodriguesLimeDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFECEE),
    onSecondaryContainer = Color(0xFFB41420),
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
    surfaceVariant = Color(0xFFF2F2F2),
    onSurfaceVariant = MutedInk,
    outline = Color(0xFFD6D6D6),
    outlineVariant = Color(0xFFEAEAEA),
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
