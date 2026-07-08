package com.ninja.scan.drive

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ninja.scan.DocScannerApp
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Uploads every scan without a Drive file id into the app's "Ninja Scan"
 * folder on Google Drive, deletes previously replaced copies, and refreshes
 * the library manifest (scan metadata + business cards + folder list) so a
 * later restore can rebuild the library losslessly. The clean stored PDF is
 * uploaded — watermarks stay editable metadata and are re-applied in-app.
 * Runs only when backup is enabled and authorization was previously granted
 * in the UI; anything transient (network, 5xx) retries with backoff.
 */
class DriveBackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as DocScannerApp
        if (!DriveBackup.isEnabled(applicationContext)) return@withContext Result.success()

        // Authorization was granted from the UI; here this returns a cached
        // access token without user interaction, or a resolution if consent
        // was revoked — in which case we give up until the user re-enables.
        val authResult = runCatching {
            Identity.getAuthorizationClient(applicationContext)
                .authorize(DriveBackup.authorizationRequest())
                .await()
        }.getOrNull() ?: return@withContext Result.retry()
        val token = authResult.accessToken
        if (authResult.hasResolution() || token == null) {
            DriveBackup.setEnabled(applicationContext, false)
            return@withContext Result.failure()
        }

        val drive = DriveRestClient(token)
        val folderId = try {
            drive.resolveFolder(applicationContext)
        } catch (e: Exception) {
            return@withContext Result.retry()
        }
        val cardsFolderId = try {
            drive.resolveSubfolder(applicationContext, folderId, "cards")
        } catch (e: Exception) {
            return@withContext Result.retry()
        }

        // Replaced copies (page edits, watermark changes) are deleted so the
        // backup folder never accumulates stale duplicates.
        val stale = DriveBackup.drainStaleFileIds(applicationContext)
        val staleFailures = stale.filterTo(mutableSetOf()) { id ->
            runCatching { drive.deleteFile(id) }.isFailure
        }
        DriveBackup.requeueStaleFileIds(applicationContext, staleFailures)

        var failures = 0
        for (scan in app.repository.getPendingBackup()) {
            val pdf = File(scan.pdfPath)
            if (!pdf.exists()) continue
            try {
                val fileId = drive.uploadPdf(pdf, "${scan.title}.pdf", folderId)
                app.repository.markBackedUp(scan.id, fileId)
            } catch (e: DriveAuthException) {
                return@withContext Result.retry()
            } catch (e: Exception) {
                failures++
            }
        }

        for (card in app.repository.getPendingPhotoBackup()) {
            val photo = card.thumbnailPath?.let(::File) ?: continue
            if (!photo.exists()) continue
            try {
                val fileId = drive.uploadFile(photo, "card-${card.id}.jpg", cardsFolderId, "image/jpeg")
                app.repository.markCardPhotoBackedUp(card.id, fileId)
            } catch (e: DriveAuthException) {
                return@withContext Result.retry()
            } catch (e: Exception) {
                failures++
            }
        }

        // The manifest always mirrors the current library, even when there
        // was nothing new to upload (e.g. a card edit or folder rename).
        try {
            uploadManifest(app, drive, folderId)
        } catch (e: Exception) {
            return@withContext Result.retry()
        }

        if (failures > 0) Result.retry() else Result.success()
    }

    private suspend fun uploadManifest(
        app: DocScannerApp,
        drive: DriveRestClient,
        folderId: String,
    ) {
        val scans = app.repository.getBackedUpScans().map { scan ->
            DriveManifest.ScanEntry(
                driveFileId = scan.driveFileId.orEmpty(),
                title = scan.title,
                createdAt = scan.createdAt,
                pageCount = scan.pageCount,
                folder = scan.folder,
                watermark = scan.watermark,
                watermarkBaked = false,
                ocrText = scan.ocrText,
            )
        }
        val cards = app.repository.getAllCards().map { card ->
            DriveManifest.CardEntry(DriveManifest.cardKey(card), card)
        }
        val content = DriveManifest.Content(scans, cards, app.repository.getFolderNames())
        val bytes = DriveManifest.encode(content, System.currentTimeMillis())

        val existing = DriveBackup.cachedManifestId(applicationContext)
            ?: drive.findFile(DriveManifest.FILE_NAME, folderId)
        val manifestId = try {
            drive.uploadJson(DriveManifest.FILE_NAME, folderId, bytes, existing)
        } catch (e: DriveAuthException) {
            throw e
        } catch (e: Exception) {
            // The cached id may point at a manifest the user deleted; retry
            // once as a fresh create before giving up.
            if (existing != null) {
                drive.uploadJson(DriveManifest.FILE_NAME, folderId, bytes, null)
            } else {
                throw e
            }
        }
        DriveBackup.setCachedManifestId(applicationContext, manifestId)
    }
}
