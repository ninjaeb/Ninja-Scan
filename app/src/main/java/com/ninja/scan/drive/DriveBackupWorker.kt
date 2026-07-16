package com.ninja.scan.drive

import android.content.Context
import android.util.Log
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
 * Uploads every scan without a Drive file id into the app's "Ninja Scan"
 * folder on Google Drive, deletes previously replaced copies, and refreshes
 * the library manifest (scan metadata + business cards + folder list) so a
 * later restore can rebuild the library losslessly. The clean stored PDF is
 * uploaded — watermarks stay editable metadata and are re-applied in-app.
 * Every PDF, card photo, and the manifest are AES-256-GCM encrypted (see
 * BackupCrypto/DriveBackup) with a key derived from a password set up in the
 * UI, so Drive itself never sees plaintext content.
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

        var failures = 0
        var scansBackedUp = 0
        var cardsBackedUp = 0

        // Every retry path shares this so NONE of them can retry forever
        // silently — that looked identical to "never syncing" from the UI,
        // since no terminal state was ever reported for the user to see.
        fun giveUpOrRetry(stage: String, e: Exception?): Result {
            failures++
            Log.w(TAG, "Backup failed at: $stage (attempt $runAttemptCount)", e)
            return if (runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure(
                    workDataOf(
                        KEY_SCANS_BACKED_UP to scansBackedUp,
                        KEY_CARDS_BACKED_UP to cardsBackedUp,
                        KEY_FAILURES to failures,
                    )
                )
            }
        }

        // Authorization was granted from the UI; here this returns a cached
        // access token without user interaction, or a resolution if consent
        // was revoked — in which case we give up until the user re-enables.
        val authResult = runCatching {
            Identity.getAuthorizationClient(applicationContext)
                .authorize(DriveBackup.authorizationRequest())
                .await()
        }.getOrNull() ?: return@withContext giveUpOrRetry("authorize", null)
        val token = authResult.accessToken
        if (authResult.hasResolution() || token == null) {
            DriveBackup.setEnabled(applicationContext, false)
            return@withContext Result.failure()
        }

        // The recovery key is generated/entered from the UI before enabling
        // backup or restoring, so this should already be cached by the time
        // a worker runs. If it's ever missing — e.g. this periodic pass
        // firing before that UI flow ever completed, or an existing install
        // that enabled backup before encryption existed — there's nothing
        // safe to encrypt with, so stop here rather than upload in
        // plaintext or retry forever. KEY_NEEDS_RECOVERY_KEY lets the UI
        // tell this apart from a transient failure and prompt accordingly,
        // instead of failing silently with no visible reason.
        val localKey = DriveBackup.loadLocalKey(applicationContext)
            ?: return@withContext Result.failure(workDataOf(KEY_NEEDS_RECOVERY_KEY to true))

        val drive = DriveRestClient(token)
        val folderId = try {
            drive.resolveFolder(applicationContext)
        } catch (e: Exception) {
            return@withContext giveUpOrRetry("resolveFolder", e)
        }
        val cardsFolderId = try {
            drive.resolveSubfolder(applicationContext, folderId, "cards")
        } catch (e: Exception) {
            return@withContext giveUpOrRetry("resolveSubfolder(cards)", e)
        }
        try {
            DriveBackup.ensureEncryptionMetadataUploaded(applicationContext, drive, folderId)
        } catch (e: DriveAuthException) {
            return@withContext giveUpOrRetry("uploadEncryptionMetadata (auth)", e)
        } catch (e: Exception) {
            return@withContext giveUpOrRetry("uploadEncryptionMetadata", e)
        }

        // Replaced copies (page edits, watermark changes) are deleted so the
        // backup folder never accumulates stale duplicates.
        val stale = DriveBackup.drainStaleFileIds(applicationContext)
        val staleFailures = stale.filterTo(mutableSetOf()) { id ->
            runCatching { drive.deleteFile(id) }.isFailure
        }
        DriveBackup.requeueStaleFileIds(applicationContext, staleFailures)

        val pendingScans = app.repository.getPendingBackup()
        val pendingCards = app.repository.getPendingPhotoBackup()
        val total = pendingScans.size + pendingCards.size
        var current = 0
        setProgress(workDataOf(DriveBackup.KEY_PROGRESS_CURRENT to current, DriveBackup.KEY_PROGRESS_TOTAL to total))

        for (scan in pendingScans) {
            val pdf = File(scan.pdfPath)
            if (pdf.exists()) {
                try {
                    val encrypted = File.createTempFile("upload", ".enc", applicationContext.cacheDir)
                    try {
                        BackupCrypto.encryptFile(pdf, encrypted, localKey)
                        // mimeType stays "application/pdf" even though the body is now
                        // opaque ciphertext: restore filters children by this mimeType to
                        // find scan files (see DriveRestoreWorker), and BackupCrypto's own
                        // magic header — not this label — is what gates decryption.
                        val fileId = drive.uploadPdf(encrypted, "${scan.title}.pdf", folderId)
                        app.repository.markBackedUp(scan.id, fileId)
                        scansBackedUp++
                    } finally {
                        encrypted.delete()
                    }
                } catch (e: DriveAuthException) {
                    return@withContext giveUpOrRetry("upload scan ${scan.id} (auth)", e)
                } catch (e: Exception) {
                    Log.w(TAG, "Upload failed for scan ${scan.id}", e)
                    failures++
                }
            }
            current++
            setProgress(workDataOf(DriveBackup.KEY_PROGRESS_CURRENT to current, DriveBackup.KEY_PROGRESS_TOTAL to total))
        }

        for (card in pendingCards) {
            val photo = card.thumbnailPath?.let(::File)
            if (photo != null && photo.exists()) {
                try {
                    val encrypted = File.createTempFile("upload", ".enc", applicationContext.cacheDir)
                    try {
                        BackupCrypto.encryptFile(photo, encrypted, localKey)
                        val fileId =
                            drive.uploadFile(encrypted, "card-${card.id}.jpg", cardsFolderId, "image/jpeg")
                        app.repository.markCardPhotoBackedUp(card.id, fileId)
                        cardsBackedUp++
                    } finally {
                        encrypted.delete()
                    }
                } catch (e: DriveAuthException) {
                    return@withContext giveUpOrRetry("upload card ${card.id} (auth)", e)
                } catch (e: Exception) {
                    Log.w(TAG, "Upload failed for card ${card.id}", e)
                    failures++
                }
            }
            current++
            setProgress(workDataOf(DriveBackup.KEY_PROGRESS_CURRENT to current, DriveBackup.KEY_PROGRESS_TOTAL to total))
        }

        // The manifest always mirrors the current library, even when there
        // was nothing new to upload (e.g. a card edit or folder rename).
        try {
            uploadManifest(app, drive, folderId, localKey)
        } catch (e: Exception) {
            return@withContext giveUpOrRetry("uploadManifest", e)
        }

        if (failures == 0) {
            Result.success(
                workDataOf(
                    KEY_SCANS_BACKED_UP to scansBackedUp,
                    KEY_CARDS_BACKED_UP to cardsBackedUp,
                    KEY_FAILURES to failures,
                )
            )
        } else {
            giveUpOrRetry("post-loop failures=$failures", null)
        }
    }

    private suspend fun uploadManifest(
        app: DocScannerApp,
        drive: DriveRestClient,
        folderId: String,
        localKey: ByteArray,
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
                isIdCard = scan.isIdCard,
            )
        }
        val cards = app.repository.getAllCards().map { card ->
            val tagTitles = app.repository.getCardTags(card.id).map { it.title }
            DriveManifest.CardEntry(DriveManifest.cardKey(card), card, tagTitles)
        }
        val tags = app.repository.getTags().map { tag ->
            DriveManifest.TagEntry(title = tag.title, description = tag.description, color = tag.color)
        }
        val content = DriveManifest.Content(scans, cards, app.repository.getFolderNames(), tags)
        val bytes = BackupCrypto.encryptBytes(DriveManifest.encode(content, System.currentTimeMillis()), localKey)

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

        // The create-fallback above (and past key/schema migrations) can
        // leave stale same-named manifests behind, making a later restore's
        // by-name lookup ambiguous — sweep every copy but the one just
        // written. Best-effort: a failed sweep never fails the backup.
        runCatching {
            drive.findFiles(DriveManifest.FILE_NAME, folderId)
                .filter { it != manifestId }
                .forEach { drive.deleteFile(it) }
        }
    }

    companion object {
        const val KEY_SCANS_BACKED_UP = "scans_backed_up"
        const val KEY_CARDS_BACKED_UP = "cards_backed_up"
        const val KEY_FAILURES = "failures"
        const val KEY_NEEDS_RECOVERY_KEY = "needs_recovery_key"
        private const val TAG = "DriveBackupWorker"
        // Low on purpose: this used to retry (silently, with no cap at all
        // for most failure paths) for potentially hours, which was
        // indistinguishable from "just never syncing." One retry surfaces a
        // real failure within about the default ~30s backoff instead.
        private const val MAX_ATTEMPTS = 2
    }
}
