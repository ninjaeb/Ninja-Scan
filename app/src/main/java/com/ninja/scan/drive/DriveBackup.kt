package com.ninja.scan.drive

import android.content.Context
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.flow.Flow

/**
 * Settings and scheduling for automatic Google Drive backup.
 *
 * Uses the narrow `drive.file` scope: the app can only see and manage files
 * it created itself (the "Ninja Scan" folder and the PDFs it uploads),
 * never the rest of the user's Drive.
 */
object DriveBackup {

    const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    const val FOLDER_NAME = "Ninja Scan"

    private const val PREFS = "drive_backup"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_FOLDER_ID = "folder_id"
    private const val KEY_MANIFEST_ID = "manifest_id"
    private const val KEY_STALE_FILE_IDS = "stale_file_ids"
    private const val WORK_NAME = "drive_backup_upload"
    private const val RESTORE_WORK_NAME = "drive_restore"

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

    fun cachedManifestId(context: Context): String? =
        prefs(context).getString(KEY_MANIFEST_ID, null)

    fun setCachedManifestId(context: Context, fileId: String?) {
        prefs(context).edit { putString(KEY_MANIFEST_ID, fileId) }
    }

    /** Remembers a replaced Drive file so the next backup pass deletes it. */
    fun addStaleFileId(context: Context, fileId: String) {
        val current = prefs(context).getStringSet(KEY_STALE_FILE_IDS, emptySet()).orEmpty()
        prefs(context).edit { putStringSet(KEY_STALE_FILE_IDS, current + fileId) }
    }

    /** Returns and clears the replaced-file backlog. */
    fun drainStaleFileIds(context: Context): Set<String> {
        val current = prefs(context).getStringSet(KEY_STALE_FILE_IDS, emptySet()).orEmpty()
        prefs(context).edit { remove(KEY_STALE_FILE_IDS) }
        return current
    }

    /** Re-queues stale ids whose deletion failed, to retry next pass. */
    fun requeueStaleFileIds(context: Context, fileIds: Set<String>) {
        if (fileIds.isEmpty()) return
        val current = prefs(context).getStringSet(KEY_STALE_FILE_IDS, emptySet()).orEmpty()
        prefs(context).edit { putStringSet(KEY_STALE_FILE_IDS, current + fileIds) }
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

    /** Schedules a restore pass that downloads the Drive backup into the library. */
    fun enqueueRestore(context: Context) {
        val request = OneTimeWorkRequestBuilder<DriveRestoreWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(RESTORE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /** Observes the restore work's state for progress/result UI. */
    fun restoreWorkInfo(context: Context): Flow<List<WorkInfo>> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(RESTORE_WORK_NAME)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
