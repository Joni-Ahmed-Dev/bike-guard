package com.bikeguard.app.ui.theme

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bikeguard.app.bikeApp
import com.bikeguard.app.data.THEME_DARK
import com.bikeguard.app.data.THEME_LIGHT

/**
 * Master design reference (v1.2.23): pale grey-green page (#F1F4F0), white cards,
 * near-black text (#111111), mint primary (#91E9AD), teal live signal (#54CDBA),
 * peach warning (#FFC178). Dark values are unchanged from before.
 */
object Palette {
    /**
     * Set by BikeGuardTheme (and by MainActivity before the first composition).
     * Every color below is a state read, so any composable using a Palette color
     * recomposes when the theme flips — no screen keeps a stale dark value.
     * The one exception was ViewerScreen's Status enum, which captured colors at
     * class-load time; it now resolves them at composition time instead (v1.2.20).
     */
    var isLight by mutableStateOf(false)

    // Dark values (the look up to v1.2.19) and light values (master design reference).
    private val DarkInk = Color(0xFF0B0F17)
    private val DarkSurface = Color(0xFF121826)
    private val DarkCard = Color(0xFF182032)
    private val DarkStroke = Color(0x22FFFFFF)
    private val DarkEmber = Color(0xFFFF9F1C)
    private val DarkSignal = Color(0xFF3DDBD9)
    private val DarkDanger = Color(0xFFFF5A5F)
    private val DarkText = Color(0xFFEAF0F8)
    private val DarkMuted = Color(0xFF8A97AB)

    private val LightInk = Color(0xFFF1F4F0)
    private val LightSurface = Color(0xFFFFFFFF)
    private val LightCard = Color(0xFFFFFFFF)
    private val LightStroke = Color(0xFFE3E8E3)
    private val LightEmber = Color(0xFF91E9AD)
    private val LightSignal = Color(0xFF54CDBA)
    private val LightDanger = Color(0xFFD92D20)
    private val LightText = Color(0xFF111111)
    private val LightMuted = Color(0xFF6B7A6E)
    private val LightWarn = Color(0xFFFFC178)

    val Ink get() = if (isLight) LightInk else DarkInk
    val Surface get() = if (isLight) LightSurface else DarkSurface
    val Card get() = if (isLight) LightCard else DarkCard
    val Stroke get() = if (isLight) LightStroke else DarkStroke
    val Ember get() = if (isLight) LightEmber else DarkEmber
    val Signal get() = if (isLight) LightSignal else DarkSignal
    val Danger get() = if (isLight) LightDanger else DarkDanger
    val Text get() = if (isLight) LightText else DarkText
    val Muted get() = if (isLight) LightMuted else DarkMuted
    // Warning states that must stay amber/peach even though the primary is now mint.
    val Warn get() = if (isLight) LightWarn else DarkEmber
}

/** Whether the app should currently draw the light theme: the stored mode, with "system" following the phone. */
fun isLightMode(context: Context): Boolean = when (context.bikeApp.prefs.themeMode) {
    THEME_LIGHT -> true
    THEME_DARK -> false
    else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES
}

private fun schemeFor(light: Boolean): ColorScheme = if (light) lightColorScheme(
    primary = Color(0xFF91E9AD), onPrimary = Color(0xFF111111),
    secondary = Color(0xFF54CDBA), onSecondary = Color(0xFF111111),
    background = Color(0xFFF1F4F0), onBackground = Color(0xFF111111),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFFFFFFF), onSurfaceVariant = Color(0xFF6B7A6E),
    surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFE9EEE9),
    error = Color(0xFFD92D20), outline = Color(0xFF6B7A6E), outlineVariant = Color(0xFFE3E8E3),
) else darkColorScheme(
    primary = Color(0xFFFF9F1C), onPrimary = Color(0xFF0B0F17),
    secondary = Color(0xFF3DDBD9), onSecondary = Color(0xFF0B0F17),
    background = Color(0xFF0B0F17), onBackground = Color(0xFFEAF0F8),
    surface = Color(0xFF121826), onSurface = Color(0xFFEAF0F8),
    surfaceVariant = Color(0xFF182032), onSurfaceVariant = Color(0xFF8A97AB),
    surfaceContainer = Color(0xFF121826), surfaceContainerHigh = Color(0xFF182032),
    error = Color(0xFFFF5A5F), outline = Color(0xFF8A97AB), outlineVariant = Color(0x22FFFFFF),
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
fun BikeGuardTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val light = isLightMode(ctx)
    // Belt and braces next to MainActivity's pre-composition assignment: the
    // value only changes on a recreate, so this never loops.
    SideEffect { Palette.isLight = light }
    MaterialTheme(colorScheme = schemeFor(light), typography = Type, content = content)
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = Palette.Card,
        shadowElevation = 2.dp,
    ) { Column(Modifier.padding(16.dp), content = content) }
}
