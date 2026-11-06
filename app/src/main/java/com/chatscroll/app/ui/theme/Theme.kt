package com.chatscroll.app.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat

@Immutable
data class ReaderPalette(
    val isDark: Boolean,
    val background: Color,
    val text: Color,
    val secondaryText: Color,
    val accent: Color,
    val deepAccent: Color,
    val userBubble: Color,
    val userBubbleText: Color,
    val codeBackground: Color,
    val codeText: Color,
    val border: Color,
    val tableHeader: Color,
    val tableRow: Color,
    val searchHighlight: Color,
    val searchText: Color
)

// Keep the original warm ivory and terracotta design in light mode.
val LightReaderPalette = ReaderPalette(
    isDark = false,
    background = Color(0xFFFAF9F5), text = Color(0xFF1F1E1D),
    secondaryText = Color(0xFF6B645B), accent = Color(0xFFAF573D),
    deepAccent = Color(0xFF93452F), userBubble = Color(0xFFF1E1CE),
    userBubbleText = Color(0xFF3C372F), codeBackground = Color(0xFFF3EFE6),
    codeText = Color(0xFF8A4B28), border = Color(0xFFE8E3D8),
    tableHeader = Color(0xFFF6F1E7), tableRow = Color.White,
    searchHighlight = Color(0xFFFFD98A), searchText = Color(0xFF302315)
)

val DarkReaderPalette = ReaderPalette(
    isDark = true,
    background = Color(0xFF1E1C1A), text = Color(0xFFF1EBE2),
    secondaryText = Color(0xFFBBB2A5), accent = Color(0xFFF0A183),
    deepAccent = Color(0xFFF0A183), userBubble = Color(0xFF44362B),
    userBubbleText = Color(0xFFF4E6D5), codeBackground = Color(0xFF292622),
    codeText = Color(0xFFE5C49D), border = Color(0xFF514A41),
    tableHeader = Color(0xFF383129), tableRow = Color(0xFF24211E),
    searchHighlight = Color(0xFF9C6C2C), searchText = Color(0xFFFFF4DB)
)

val LocalReaderPalette = staticCompositionLocalOf { LightReaderPalette }

// Retain existing imports while making every caller follow the selected palette.
val Ivory: Color @Composable get() = LocalReaderPalette.current.background
val Ink: Color @Composable get() = LocalReaderPalette.current.text
val InkSoft: Color @Composable get() = LocalReaderPalette.current.secondaryText
val Terracotta: Color @Composable get() = LocalReaderPalette.current.accent
val TerracottaDeep: Color @Composable get() = LocalReaderPalette.current.deepAccent
val UserBubble: Color @Composable get() = LocalReaderPalette.current.userBubble
val UserBubbleText: Color @Composable get() = LocalReaderPalette.current.userBubbleText
val CodeBackground: Color @Composable get() = LocalReaderPalette.current.codeBackground
val WarmBorder: Color @Composable get() = LocalReaderPalette.current.border
val HeaderRow: Color @Composable get() = LocalReaderPalette.current.tableHeader

private val LightColors = lightColorScheme(
    primary = LightReaderPalette.accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF6E3D7),
    onPrimaryContainer = LightReaderPalette.deepAccent,
    secondary = LightReaderPalette.deepAccent,
    onSecondary = Color.White,
    secondaryContainer = LightReaderPalette.userBubble,
    onSecondaryContainer = LightReaderPalette.userBubbleText,
    background = LightReaderPalette.background,
    onBackground = LightReaderPalette.text,
    surface = LightReaderPalette.background,
    onSurface = LightReaderPalette.text,
    surfaceVariant = LightReaderPalette.codeBackground,
    onSurfaceVariant = LightReaderPalette.secondaryText,
    surfaceContainer = Color(0xFFF5F0E8),
    surfaceContainerHigh = Color(0xFFF0EAE0),
    surfaceContainerHighest = Color(0xFFEAE3D8),
    surfaceContainerLow = Color(0xFFF8F5EF),
    surfaceContainerLowest = Color.White,
    outline = LightReaderPalette.border,
    outlineVariant = LightReaderPalette.border,
    error = Color(0xFFB3261E)
)

private val DarkColors = darkColorScheme(
    primary = DarkReaderPalette.accent,
    onPrimary = Color(0xFF402316),
    primaryContainer = Color(0xFF603D2D),
    onPrimaryContainer = Color(0xFFFFDCCB),
    secondary = DarkReaderPalette.deepAccent,
    onSecondary = Color(0xFF402316),
    secondaryContainer = DarkReaderPalette.userBubble,
    onSecondaryContainer = DarkReaderPalette.userBubbleText,
    background = DarkReaderPalette.background,
    onBackground = DarkReaderPalette.text,
    surface = DarkReaderPalette.background,
    onSurface = DarkReaderPalette.text,
    surfaceVariant = DarkReaderPalette.codeBackground,
    onSurfaceVariant = DarkReaderPalette.secondaryText,
    surfaceContainer = Color(0xFF282420),
    surfaceContainerHigh = Color(0xFF322C26),
    surfaceContainerHighest = Color(0xFF3C352D),
    surfaceContainerLow = Color(0xFF24211E),
    surfaceContainerLowest = Color(0xFF171513),
    outline = DarkReaderPalette.border,
    outlineVariant = DarkReaderPalette.border,
    error = Color(0xFFFFB4AB)
)

private val ReaderTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium,
        fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium,
        fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp, lineHeight = 30.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp)
)

private val ReaderShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun ChatScrollTheme(themeMode: String = "system", content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            view.context.activity()?.window?.let { window ->
                WindowInsetsControllerCompat(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }
    CompositionLocalProvider(LocalReaderPalette provides if (dark) DarkReaderPalette else LightReaderPalette) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors,
            typography = ReaderTypography, shapes = ReaderShapes, content = content)
    }
}

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.activity() else null
    else -> null
}
