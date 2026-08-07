package com.ninja.scan.ui

import android.content.Context
import androidx.core.content.edit

/** Tracks which one-time "long-press to select" hints the user has already dismissed. */
object HintPrefs {
    private const val PREFS = "hint_prefs"

    fun isDismissed(context: Context, key: String): Boolean =
        prefs(context).getBoolean(key, false)

    fun dismiss(context: Context, key: String) {
        prefs(context).edit { putBoolean(key, true) }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
