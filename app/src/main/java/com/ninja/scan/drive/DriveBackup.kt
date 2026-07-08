package com.ninja.scan.drive

import android.content.Context
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit

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
    private const val KEY_CARDS_FOLDER_ID = "cards_folder_id"
    private const val KEY_MANIFEST_ID = "manifest_id"
    private const val KEY_STALE_FILE_IDS = "stale_file_ids"
    private const val WORK_NAME = "drive_backup_upload"
    private const val PERIODIC_WORK_NAME = "drive_backup_periodic"
    private const val RESTORE_WORK_NAME = "drive_restore"
    private const val PERIODIC_BACKUP_INTERVAL_HOURS = 12L

    fun authorizationRequest(): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_SCOPE)))
            .build()

    /**
     * Requests the drive.file scope, shared by every screen that offers
     * Drive controls. If Google needs user consent (first time),
     * [onNeedsConsent] launches the returned system dialog; otherwise
     * [onGranted] runs immediately with the silently granted authorization.
     */
    fun requestAuthorization(
        context: Context,
        onNeedsConsent: (android.app.PendingIntent) -> Unit,
        onGranted: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        Identity.getAuthorizationClient(context)
            .authorize(authorizationRequest())
            .addOnSuccessListener { result ->
                val pendingIntent = result.pendingIntent
                if (result.hasResolution() && pendingIntent != null) {
                    onNeedsConsent(pendingIntent)
                } else {
                    onGranted()
                }
            }
            .addOnFailureListener { e ->
                onFailure(e.message ?: "authorization unavailable")
            }
    }

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
        if (enabled) {
            enqueue(context)
            enqueuePeriodic(context)
        } else {
            cancelPeriodic(context)
        }
    }

    fun cachedFolderId(context: Context): String? =
        prefs(context).getString(KEY_FOLDER_ID, null)

    fun setCachedFolderId(context: Context, folderId: String?) {
        prefs(context).edit { putString(KEY_FOLDER_ID, folderId) }
    }

    fun cachedCardsFolderId(context: Context): String? =
        prefs(context).getString(KEY_CARDS_FOLDER_ID, null)

    fun setCachedCardsFolderId(context: Context, folderId: String?) {
        prefs(context).edit { putString(KEY_CARDS_FOLDER_ID, folderId) }
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

    /**
     * Schedules a recurring upload pass as a safety net on top of the
     * event-driven [enqueue] — e.g. covers changes made while offline that
     * never got a chance to trigger an immediate backup. `KEEP` makes this
     * safe to call unconditionally (it no-ops if already scheduled).
     */
    fun enqueuePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<DriveBackupWorker>(
            PERIODIC_BACKUP_INTERVAL_HOURS, TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancelPeriodic(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
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
