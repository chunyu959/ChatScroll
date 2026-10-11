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

    const val DEFAULT_SECONDS = 3
    const val MIN_SECONDS = 1

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
}
