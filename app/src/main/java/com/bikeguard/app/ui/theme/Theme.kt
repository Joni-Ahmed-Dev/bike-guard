package com.bikeguard.app.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Instrument-panel palette: deep ink, amber warning-light primary, cyan "signal" for live state. */
object Palette {
    val Ink = Color(0xFF0B0F17)
    val Surface = Color(0xFF121826)
    val Card = Color(0xFF182032)
    val Stroke = Color(0x22FFFFFF)
    val Ember = Color(0xFFFF9F1C)
    val Signal = Color(0xFF3DDBD9)
    val Danger = Color(0xFFFF5A5F)
    val Text = Color(0xFFEAF0F8)
    val Muted = Color(0xFF8A97AB)
}

private val Scheme = darkColorScheme(
    primary = Palette.Ember, onPrimary = Palette.Ink,
    secondary = Palette.Signal, onSecondary = Palette.Ink,
    background = Palette.Ink, onBackground = Palette.Text,
    surface = Palette.Surface, onSurface = Palette.Text,
    surfaceVariant = Palette.Card, onSurfaceVariant = Palette.Muted,
    surfaceContainer = Palette.Surface, surfaceContainerHigh = Palette.Card,
    error = Palette.Danger, outline = Palette.Muted, outlineVariant = Palette.Stroke,
)

private val Type = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 68.sp, letterSpacing = (-2).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp),
)

@Composable
fun BikeGuardTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = Scheme, typography = Type, content = content)

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = Palette.Card.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, Palette.Stroke),
    ) { Column(Modifier.padding(16.dp), content = content) }
}
