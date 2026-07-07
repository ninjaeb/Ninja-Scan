package com.eugeneboon.docscanner.drive

import android.content.Context
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.common.api.Scope

/**
 * Settings and scheduling for automatic Google Drive backup.
 *
 * Uses the narrow `drive.file` scope: the app can only see and manage files
 * it created itself (the "Doc Scanner" folder and the PDFs it uploads),
 * never the rest of the user's Drive.
 */
object DriveBackup {

    const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    const val FOLDER_NAME = "Doc Scanner"

    private const val PREFS = "drive_backup"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_FOLDER_ID = "folder_id"
    private const val WORK_NAME = "drive_backup_upload"

    fun authorizationRequest(): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_SCOPE)))
            .build()

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
        if (enabled) enqueue(context)
    }

    fun cachedFolderId(context: Context): String? =
        prefs(context).getString(KEY_FOLDER_ID, null)

    fun setCachedFolderId(context: Context, folderId: String?) {
        prefs(context).edit { putString(KEY_FOLDER_ID, folderId) }
    }

    /** Schedules an upload pass for all scans not yet backed up. */
    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<DriveBackupWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
