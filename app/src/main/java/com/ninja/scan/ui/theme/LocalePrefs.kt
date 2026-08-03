package com.ninja.scan.ui.theme

import android.content.Context
import android.content.res.Configuration
import androidx.core.content.edit
import java.util.Locale

/**
 * Persists the user's manual in-app language choice, independent of the
 * device's system language. [SYSTEM_DEFAULT] (the default) means "follow the
 * device" — Android's normal values/values-zh/values-ms resource-qualifier
 * fallback already handles that case with no code involved. Anything else
 * overrides it. Mirrors [ThemePrefs]'s storage pattern for the light/dark
 * toggle.
 */
object LocalePrefs {
    private const val PREFS = "locale_prefs"
    private const val KEY_LANGUAGE = "language"

    const val SYSTEM_DEFAULT = ""
    const val ENGLISH = "en"
    const val CHINESE = "zh"
    const val MALAY = "ms"

    fun getLanguage(context: Context): String =
        prefs(context).getString(KEY_LANGUAGE, SYSTEM_DEFAULT) ?: SYSTEM_DEFAULT

    fun setLanguage(context: Context, language: String) {
        prefs(context).edit { putString(KEY_LANGUAGE, language) }
    }

    /**
     * Wraps [base] with the chosen language's [Configuration]; a no-op when
     * following the system default. The Application and every Activity apply
     * this in their own attachBaseContext, since each gets its own base
     * context on creation — wrapping only the one the user changed the
     * setting from would leave every other screen on the old language until
     * it happened to recreate.
     */
    fun wrap(base: Context): Context {
        val language = getLanguage(base)
        if (language.isEmpty()) return base
        val locale = Locale(language)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
