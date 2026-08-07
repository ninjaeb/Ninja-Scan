package com.ninja.scan.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.core.content.edit

/**
 * Gates the app behind a biometric check once per process lifetime.
 * [unlockedThisProcess] is an in-memory flag (not persisted), so it's reset
 * whenever the app process itself restarts — but stays true for as long as
 * that process keeps running, so it's never asked more than once per app
 * launch no matter how many times the app is backgrounded/foregrounded.
 */
object AppLock {
    private const val PREFS = "app_lock_prefs"
    private const val KEY_ENABLED = "enabled"

    /** False if this device has no biometric hardware or nothing enrolled — nothing to gate with. */
    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /** Defaults to on wherever it's actually available, so it protects the app out of the box. */
    fun isEnabled(context: Context): Boolean =
        isAvailable(context) && prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
    }

    @Volatile
    var unlockedThisProcess: Boolean = false

    /** Guards against launching a second lock screen on top of the first. */
    @Volatile
    var lockActivityShowing: Boolean = false

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
