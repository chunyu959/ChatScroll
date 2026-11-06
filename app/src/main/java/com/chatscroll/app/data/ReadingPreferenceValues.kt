package com.chatscroll.app.data

/** Validation shared by persistence, controls, and the reader. No Android dependency. */
object ReadingPreferenceValues {
    const val DEFAULT_FONT_SIZE = 16f
    const val MIN_FONT_SIZE = 14f
    const val MAX_FONT_SIZE = 24f
    const val DEFAULT_LINE_HEIGHT = 1.55f
    const val MIN_LINE_HEIGHT = 1.2f
    const val MAX_LINE_HEIGHT = 2f
    const val DEFAULT_THEME_MODE = "system"

    fun themeMode(value: String?): String =
        value?.takeIf { it == "system" || it == "light" || it == "dark" } ?: DEFAULT_THEME_MODE

    fun fontSize(value: Float): Float =
        if (value.isFinite()) value.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE) else DEFAULT_FONT_SIZE

    fun lineHeight(value: Float): Float =
        if (value.isFinite()) value.coerceIn(MIN_LINE_HEIGHT, MAX_LINE_HEIGHT) else DEFAULT_LINE_HEIGHT

    fun secondsText(value: String, fallback: Int): Int =
        (value.toIntOrNull() ?: fallback).coerceAtLeast(1)
}
