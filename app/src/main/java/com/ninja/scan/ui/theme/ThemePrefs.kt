package com.ninja.scan.ui.theme

import android.content.Context
import androidx.core.content.edit

/**
 * Persists the user's manual light/dark theme choice. Defaults to light —
 * the app no longer follows the system setting, since a user-visible
 * toggle now gives explicit control.
 */
object ThemePrefs {
    private const val PREFS = "theme_prefs"
    private const val KEY_DARK = "dark_theme"

    fun isDark(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DARK, false)

    fun setDark(context: Context, dark: Boolean) {
        prefs(context).edit { putBoolean(KEY_DARK, dark) }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
