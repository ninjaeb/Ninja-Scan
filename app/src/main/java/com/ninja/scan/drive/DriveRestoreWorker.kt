package com.ninja.scan.drive

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ninja.scan.DocScannerApp
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Downloads the "Ninja Scan" Drive backup back into the library: every PDF
 * not already present (matched by Drive file id) plus the business cards and
 * folder list from the manifest. Idempotent — running it twice restores
 * nothing new — so WorkManager retries after partial failures are safe.
 */
class DriveRestoreWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as DocScannerApp

        val authResult = runCatching {
            Identity.getAuthorizationClient(applicationContext)
                .authorize(DriveBackup.authorizationRequest())
                .await()
        }.getOrNull() ?: return@withContext Result.retry()
        val token = authResult.accessToken
        if (authResult.hasResolution() || token == null) return@withContext Result.failure()

        val drive = DriveRestClient(token)
        val folderId = try {
            drive.findFolder(DriveBackup.FOLDER_NAME)
        } catch (e: Exception) {
            return@withContext Result.retry()
        } ?: return@withContext Result.success(workDataOf(KEY_SCANS to 0, KEY_CARDS to 0))

        val manifest = try {
            drive.findFile(DriveManifest.FILE_NAME, folderId)?.let { manifestId ->
                val temp = File.createTempFile("manifest", ".json", applicationContext.cacheDir)
                try {
                    drive.downloadTo(manifestId, temp)
                    DriveManifest.decode(temp.readBytes())
                } finally {
                    temp.delete()
                }
            }
        } catch (e: DriveAuthException) {
            return@withContext Result.retry()
        } catch (e: Exception) {
            null // Absent/corrupt manifest: PDFs still restore with fallbacks.
        }

        manifest?.folders?.forEach { app.repository.addFolder(it) }

        val entries = manifest?.scans?.associateBy { it.driveFileId }.orEmpty()
        val known = app.repository.getDriveFileIds().toSet()
        var restored = 0
        var failures = 0
        val children = try {
            drive.listChildren(folderId)
        } catch (e: Exception) {
            return@withContext Result.retry()
        }
        for (file in children) {
            if (file.mimeType != "application/pdf" || file.id in known) continue
            try {
                if (app.repository.restoreScanFromDrive(drive, file, entries[file.id])) {
                    restored++
                }
            } catch (e: DriveAuthException) {
                return@withContext Result.retry()
            } catch (e: Exception) {
                failures++
            }
        }

        val cardsRestored = manifest?.cards?.let { app.repository.restoreCards(drive, it) } ?: 0

        if (failures > 0) {
            Result.retry()
        } else {
            Result.success(workDataOf(KEY_SCANS to restored, KEY_CARDS to cardsRestored))
        }
    }

    companion object {
        const val KEY_SCANS = "scans"
        const val KEY_CARDS = "cards"
    }
}
