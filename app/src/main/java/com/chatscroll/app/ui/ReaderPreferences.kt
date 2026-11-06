package com.chatscroll.app.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import com.chatscroll.app.data.ReadingPreferenceValues

@Immutable
data class ReaderPreferences(
    val fontSizeSp: Float = ReadingPreferenceValues.DEFAULT_FONT_SIZE,
    val lineHeightMultiplier: Float = ReadingPreferenceValues.DEFAULT_LINE_HEIGHT
) {
    fun validated(): ReaderPreferences = copy(
        fontSizeSp = ReadingPreferenceValues.fontSize(fontSizeSp),
        lineHeightMultiplier = ReadingPreferenceValues.lineHeight(lineHeightMultiplier)
    )
}

val LocalReaderPreferences = staticCompositionLocalOf { ReaderPreferences() }
