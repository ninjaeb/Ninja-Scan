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
 * Encrypted content (see BackupCrypto) is decrypted with the key set up or
 * unlocked in the UI before this worker was enqueued; legacy plaintext
 * backups from before encryption existed still restore unchanged.
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
        // Duplicate "Ninja Scan" folders can exist from past sessions (a
        // folder re-created during a transient lookup failure); restore
        // reads across ALL of them so nothing is missed just because one
        // arbitrary folder pick happened to be a stale one.
        val folderIds = try {
            drive.findFolders(DriveBackup.FOLDER_NAME)
        } catch (e: Exception) {
            return@withContext Result.retry()
        }
        if (folderIds.isEmpty()) {
            return@withContext Result.success(workDataOf(KEY_SCANS to 0, KEY_CARDS to 0))
        }

        // Set up (or unlocked) from the UI before restore is ever enqueued;
        // null here just means every file turns out to be legacy plaintext
        // from before encryption existed, which still restores fine below.
        val localKey = DriveBackup.loadLocalKey(applicationContext)

        // Duplicate manifests can exist (a past update-failed-so-create-a-
        // new-one fallback, copies encrypted with a since-replaced key, or
        // copies living in a duplicate backup folder) — try each candidate
        // newest-first and keep the first one that actually decrypts and
        // decodes, instead of giving up on cards/folders because one
        // arbitrary pick happened to be stale.
        val manifest = try {
            var decoded: DriveManifest.Content? = null
            for (manifestId in folderIds.flatMap { drive.findFiles(DriveManifest.FILE_NAME, it) }) {
                val temp = File.createTempFile("manifest", ".json", applicationContext.cacheDir)
                try {
                    drive.downloadTo(manifestId, temp)
                    val bytes = temp.readBytes()
                    val plain = if (BackupCrypto.isEncrypted(bytes)) {
                        // A wrong-key candidate throws on decrypt — skip it
                        // and move on to the next copy rather than aborting.
                        runCatching { localKey?.let { BackupCrypto.decryptBytes(bytes, it) } }.getOrNull()
                    } else {
                        bytes
                    }
                    decoded = plain?.let(DriveManifest::decode)
                    if (decoded != null) break
                } finally {
                    temp.delete()
                }
            }
            decoded
        } catch (e: DriveAuthException) {
            return@withContext Result.retry()
        } catch (e: Exception) {
            null // Absent/corrupt/undecryptable manifest: PDFs still restore with fallbacks.
        }

        manifest?.folders?.forEach { app.repository.restoreFolder(it, manifest.folderColors[it]) }

        val entries = manifest?.scans?.associateBy { it.driveFileId }.orEmpty()
        val known = app.repository.getDriveFileIds().toSet()
        var restored = 0
        var failures = 0
        val children = try {
            folderIds.flatMap { drive.listChildren(it) }.distinctBy { it.id }
        } catch (e: Exception) {
            return@withContext Result.retry()
        }
        val total = children.size
        var current = 0
        setProgress(workDataOf(DriveBackup.KEY_PROGRESS_CURRENT to current, DriveBackup.KEY_PROGRESS_TOTAL to total))
        for (file in children) {
            if (file.mimeType == "application/pdf" && file.id !in known) {
                try {
                    if (app.repository.restoreScanFromDrive(drive, file, entries[file.id], localKey)) {
                        restored++
                    }
                } catch (e: DriveAuthException) {
                    return@withContext Result.retry()
                } catch (e: Exception) {
                    failures++
                }
            } else if (file.mimeType == "application/pdf") {
                // Already restored — but possibly by an earlier pass that ran
                // without a readable manifest, which loses folder assignment
                // and other manifest-only metadata to fallbacks. Best-effort
                // re-adopt from the manifest entry now that one decoded.
                entries[file.id]?.let { entry ->
                    runCatching { app.repository.adoptManifestMetadata(file.id, entry) }
                }
            }
            current++
            setProgress(workDataOf(DriveBackup.KEY_PROGRESS_CURRENT to current, DriveBackup.KEY_PROGRESS_TOTAL to total))
        }

        // An unexpected failure here (e.g. a local DB error) must not also
        // wipe out the scans already restored above — isolate it the same
        // way the scan-restore loop isolates per-file failures.
        val cardsRestored = try {
            manifest?.cards?.let {
                app.repository.restoreCards(drive, it, manifest.tags, localKey)
            } ?: 0
        } catch (e: DriveAuthException) {
            return@withContext Result.retry()
        } catch (e: Exception) {
            failures++
            0
        }

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
