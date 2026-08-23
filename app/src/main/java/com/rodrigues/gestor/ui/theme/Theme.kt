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

val AcaiPurple = Color(0xFF56008F)
val AcaiPurpleDark = Color(0xFF3E006A)
val AcaiPurpleSoft = Color(0xFFF0E7F5)
val RodriguesLime = Color(0xFF7FD300)
val RodriguesLimeDark = Color(0xFF4F8500)
val WarmBackground = Color(0xFFF8F6FB)
val Ink = Color(0xFF211628)
val MutedInk = Color(0xFF716A78)
val WarningOrange = Color(0xFFF59E0B)
val DestructiveRed = Color(0xFFD92D20)

private val GestorLightColors = lightColorScheme(
    primary = AcaiPurple,
    onPrimary = Color.White,
    primaryContainer = AcaiPurpleSoft,
    onPrimaryContainer = AcaiPurpleDark,
    secondary = RodriguesLimeDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEBF8D8),
    onSecondaryContainer = Color(0xFF284700),
    tertiary = WarningOrange,
    onTertiary = Ink,
    error = DestructiveRed,
    onError = Color.White,
    errorContainer = Color(0xFFFFE9E7),
    onErrorContainer = Color(0xFF7A140F),
    background = WarmBackground,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF2EEF5),
    onSurfaceVariant = MutedInk,
    outline = Color(0xFFBEB4C4),
    outlineVariant = Color(0xFFE8E0EC),
)

private val GestorTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, fontSize = 32.sp, lineHeight = 38.sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, fontSize = 28.sp, lineHeight = 34.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp, lineHeight = 27.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 23.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 10.sp, lineHeight = 14.sp),
)

private val GestorShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(30.dp),
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
