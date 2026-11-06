package com.chatscroll.app.data

import android.content.Context

/**
 * The few preferences the app keeps, stored in the app-private directory so they
 * survive a restart but never leave the device.
 *
 * [rememberPosition] decides whether reading position is kept at all.
 * [rememberSeconds] is how often it is written while reading: the app can be swiped
 * away without warning, so a position is only ever as fresh as the last write.
 */
object Settings {
    private const val FILE = "settings"
    private const val REMEMBER = "remember_position"
    private const val SECONDS = "remember_seconds"
    private const val THEME_MODE = "theme_mode"
    private const val FONT_SIZE = "font_size_sp"
    private const val LINE_HEIGHT = "line_height_multiplier"
    private const val PURE_READING = "pure_reading"
    private const val LIBRARY_ORDER = "library_order"
    private const val LIBRARY_FOLDER = "library_folder"

    const val DEFAULT_SECONDS = 3
    const val MIN_SECONDS = 1
    const val DEFAULT_THEME_MODE = ReadingPreferenceValues.DEFAULT_THEME_MODE
    const val DEFAULT_FONT_SIZE = ReadingPreferenceValues.DEFAULT_FONT_SIZE
    const val MIN_FONT_SIZE = ReadingPreferenceValues.MIN_FONT_SIZE
    const val MAX_FONT_SIZE = ReadingPreferenceValues.MAX_FONT_SIZE
    const val DEFAULT_LINE_HEIGHT = ReadingPreferenceValues.DEFAULT_LINE_HEIGHT
    const val MIN_LINE_HEIGHT = ReadingPreferenceValues.MIN_LINE_HEIGHT
    const val MAX_LINE_HEIGHT = ReadingPreferenceValues.MAX_LINE_HEIGHT

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun rememberPosition(context: Context): Boolean =
        prefs(context).getBoolean(REMEMBER, true)

    fun setRememberPosition(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(REMEMBER, enabled).apply()
    }

    fun rememberSeconds(context: Context): Int =
        prefs(context).getInt(SECONDS, DEFAULT_SECONDS).coerceAtLeast(MIN_SECONDS)

    fun setRememberSeconds(context: Context, seconds: Int) {
        prefs(context).edit().putInt(SECONDS, seconds.coerceAtLeast(MIN_SECONDS)).apply()
    }

    fun themeMode(context: Context): String =
        ReadingPreferenceValues.themeMode(prefs(context).getString(THEME_MODE, DEFAULT_THEME_MODE))

    fun setThemeMode(context: Context, mode: String) {
        prefs(context).edit().putString(THEME_MODE, ReadingPreferenceValues.themeMode(mode)).apply()
    }

    fun fontSize(context: Context): Float =
        ReadingPreferenceValues.fontSize(prefs(context).getFloat(FONT_SIZE, DEFAULT_FONT_SIZE))

    fun setFontSize(context: Context, fontSize: Float) {
        prefs(context).edit().putFloat(FONT_SIZE, ReadingPreferenceValues.fontSize(fontSize)).apply()
    }

    fun pureReading(context: Context): Boolean = prefs(context).getBoolean(PURE_READING, false)

    fun setPureReading(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(PURE_READING, enabled).apply()
    }

    fun libraryOrder(context: Context): String = ConversationOrdering.normalized(prefs(context).getString(LIBRARY_ORDER, null))

    fun setLibraryOrder(context: Context, order: String) {
        prefs(context).edit().putString(LIBRARY_ORDER, ConversationOrdering.normalized(order)).apply()
    }

    fun libraryFolder(context: Context): String = prefs(context).getString(LIBRARY_FOLDER, "all") ?: "all"

    fun setLibraryFolder(context: Context, folder: String) {
        prefs(context).edit().putString(LIBRARY_FOLDER, folder).apply()
    }

    fun lineHeight(context: Context): Float =
        ReadingPreferenceValues.lineHeight(prefs(context).getFloat(LINE_HEIGHT, DEFAULT_LINE_HEIGHT))

    fun setLineHeight(context: Context, lineHeight: Float) {
        prefs(context).edit().putFloat(LINE_HEIGHT, ReadingPreferenceValues.lineHeight(lineHeight)).apply()
    }
}
