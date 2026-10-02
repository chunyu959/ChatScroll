package com.chatscroll.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Warm light palette: ivory background with a terracotta accent
val Ivory = Color(0xFFFAF9F5)          // app background
val Ink = Color(0xFF1F1E1D)            // primary text
val InkSoft = Color(0xFF757168)        // secondary text
val Terracotta = Color(0xFFD97757)     // accent
val TerracottaDeep = Color(0xFFB65C3B)
val UserBubble = Color(0xFFF1E1CE)     // light apricot bubble
val UserBubbleText = Color(0xFF3C372F)
val CodeBackground = Color(0xFFF3EFE6)
val WarmBorder = Color(0xFFE8E3D8)
val HeaderRow = Color(0xFFF6F1E7)

private val LightColors = lightColorScheme(
    primary = Terracotta,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF6E3D7),
    onPrimaryContainer = TerracottaDeep,
    secondary = TerracottaDeep,
    onSecondary = Color.White,
    background = Ivory,
    onBackground = Ink,
    surface = Ivory,
    onSurface = Ink,
    surfaceVariant = CodeBackground,
    onSurfaceVariant = InkSoft,
    outline = WarmBorder,
    outlineVariant = WarmBorder,
    error = Color(0xFFB3261E)
)

/** The app ships with a single warm light theme (no dark mode by design). */
@Composable
fun ChatScrollTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content
    )
}
