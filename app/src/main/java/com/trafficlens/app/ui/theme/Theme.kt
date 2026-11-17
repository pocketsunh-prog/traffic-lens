package com.trafficlens.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Night-city palette shared by the whole dashboard. */
object Tint {
    val BackgroundTop = Color(0xFF060814)
    val BackgroundBottom = Color(0xFF0A0819)
    val CardTop = Color(0xFF141735)
    val CardBottom = Color(0xFF0D0F24)
    val CardBorderStart = Color(0xFF3A2F73)
    val CardBorderEnd = Color(0xFF1C2145)
    val TextPrimary = Color(0xFFF2F4FF)
    val TextSecondary = Color(0xFF8C92B8)
    val TextTertiary = Color(0xFF5E648C)
    val Violet = Color(0xFF8B5CF6)
    val Indigo = Color(0xFF6366F1)
    val Cyan = Color(0xFF22D3EE)
    val Green = Color(0xFF34D399)
    val Amber = Color(0xFFFBBF24)
    val Rose = Color(0xFFFB7185)
    val Surface = Color(0xFF10122A)
}

private val scheme = darkColorScheme(
    primary = Tint.Violet,
    onPrimary = Color.White,
    secondary = Tint.Cyan,
    onSecondary = Color(0xFF04121A),
    tertiary = Tint.Green,
    background = Tint.BackgroundTop,
    onBackground = Tint.TextPrimary,
    surface = Tint.Surface,
    onSurface = Tint.TextPrimary,
    surfaceVariant = Color(0xFF171A38),
    onSurfaceVariant = Tint.TextSecondary,
    outline = Tint.CardBorderStart
)

private val type = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 40.sp, letterSpacing = (-1).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 24.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 0.6.sp)
)

@Composable
fun TrafficLensTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
