package com.ninja.scan.drive

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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

    /** Small JSON file in the backup folder carrying a verifier for the
     * recovery key — never the key itself — so a different device can tell
     * a pasted-in recovery code is the right one before trusting it. */
    const val ENCRYPTION_FILE_NAME = "ninja-scan-encryption.json"

    /** What's needed before backup/restore can proceed on this device. */
    sealed class KeyRequirement {
        /** A usable key is already cached locally. */
        object Ready : KeyRequirement()
        /** No key locally and none generated anywhere yet — generate a new one. */
        object NeedsSetup : KeyRequirement()
        /** A recovery key was generated on another device — ask for it to unlock here. */
        object NeedsUnlock : KeyRequirement()
    }

    /** WorkInfo.progress keys shared by DriveBackupWorker and DriveRestoreWorker. */
    const val KEY_PROGRESS_CURRENT = "progress_current"
    const val KEY_PROGRESS_TOTAL = "progress_total"

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
     * [onGranted] runs immediately with the silently granted access token
     * (needed to check whether a backup encryption key is set up yet).
     */
    fun requestAuthorization(
        context: Context,
        onNeedsConsent: (android.app.PendingIntent) -> Unit,
        onGranted: (String) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        Identity.getAuthorizationClient(context)
            .authorize(authorizationRequest())
            .addOnSuccessListener { result ->
                val pendingIntent = result.pendingIntent
                val token = result.accessToken
                when {
                    result.hasResolution() && pendingIntent != null -> onNeedsConsent(pendingIntent)
                    token != null -> onGranted(token)
                    else -> onFailure("no access token")
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
            // Restore first, not an immediate backup pass: this fires right
            // after reinstall/new-device setup as often as it fires for an
            // already-populated library, and an immediate backup pass reads
            // whatever is locally present *right now* — on a freshly
            // reinstalled device that's an empty library, and
            // DriveBackupWorker.uploadManifest() unconditionally overwrites
            // the shared manifest with that empty state. The manifest is the
            // ONLY place business cards and both tag catalogs live (unlike
            // scans, which also restore independently from the PDF files
            // themselves), so this used to permanently wipe every card and
            // tag from Drive before the user got a chance to restore them —
            // the PDFs alone survived, which is exactly the asymmetry
            // reported (documents fine, cards/tags gone). DriveRestoreWorker
            // enqueues the matching backup pass itself once restore
            // completes (a no-op if Drive has nothing to restore), so any
            // local-only unsynced data still gets backed up right after.
            enqueueRestore(context)
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

    /** Observes the backup work's state for progress UI. */
    fun backupWorkInfo(context: Context): Flow<List<WorkInfo>> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK_NAME)

    // --- Backup encryption key -------------------------------------------
    //
    // The AES key that encrypts everything uploaded is a random 256-bit
    // value (see BackupCrypto.generateKey) — nothing derived from anything
    // the user chose, so nothing offline-guessable. What's cached here is
    // the raw key bytes, wrapped by an Android-Keystore key that can't
    // leave this device, so a plaintext key is never sitting in
    // SharedPreferences. A verifier (an encrypted known value, useless
    // without the key) lives in Drive as ENCRYPTION_FILE_NAME so a pasted-in
    // recovery code can be checked before trusting it.

    /**
     * True only when the cached wrapped key is actually present AND still
     * unwraps — not just that a value exists under [KEY_BACKUP_KEY_WRAPPED].
     * The two can disagree: the wrapped key sits in SharedPreferences, but
     * the Android Keystore entry that unwraps it does not survive an app
     * uninstall. A device with a stale SharedPreferences copy (e.g. one
     * that predates backup_rules.xml/data_extraction_rules.xml excluding
     * `sharedpref` from Android's OS-level Auto Backup) would otherwise
     * report a working key that [loadLocalKey] can actually never return,
     * skipping the recovery-key prompt and silently failing every
     * encrypted upload/download from then on.
     */
    fun hasLocalKey(context: Context): Boolean = loadLocalKey(context) != null

    /** The cached raw AES key, or null if this device hasn't generated/entered one yet. */
    fun loadLocalKey(context: Context): ByteArray? {
        val wrapped = prefs(context).getString(KEY_BACKUP_KEY_WRAPPED, null) ?: return null
        return runCatching { unwrapKey(Base64.getDecoder().decode(wrapped)) }.getOrNull()
    }

    /**
     * Re-derives the display form of this device's cached key — e.g. for a
     * "View recovery key" menu item, so losing the one-time display at
     * generation time doesn't mean losing the code for good as long as this
     * same device/install still has it cached. Null if none is cached.
     */
    fun currentRecoveryKey(context: Context): String? =
        loadLocalKey(context)?.let { BackupCrypto.encodeRecoveryKey(it) }

    private fun saveLocalKey(context: Context, raw: ByteArray) {
        prefs(context).edit {
            putString(KEY_BACKUP_KEY_WRAPPED, Base64.getEncoder().encodeToString(wrapKey(raw)))
        }
    }

    /**
     * Checks whether this device already has a usable key, and if not,
     * whether a recovery key was already generated (on this or another
     * device) by looking for [ENCRYPTION_FILE_NAME] in the backup folder —
     * across every same-named backup folder, since duplicates can exist
     * from past sessions and the file may live in any of them.
     */
    suspend fun resolveKeyRequirement(context: Context, token: String): KeyRequirement =
        withContext(Dispatchers.IO) {
            if (hasLocalKey(context)) return@withContext KeyRequirement.Ready
            val drive = DriveRestClient(token)
            val exists = runCatching {
                drive.findFolders(FOLDER_NAME).any { folderId ->
                    drive.findFile(ENCRYPTION_FILE_NAME, folderId) != null
                }
            }.getOrDefault(false)
            if (exists) KeyRequirement.NeedsUnlock else KeyRequirement.NeedsSetup
        }

    /**
     * First-time setup: generates a new random key, caches it locally, and
     * returns the one-time recovery code to show the user — this is the
     * only copy of it anywhere; losing it means the backup can't be
     * decrypted again, same as the app never storing it either.
     */
    fun generateRecoveryKey(context: Context): String {
        val key = BackupCrypto.generateKey()
        saveLocalKey(context, key)
        return BackupCrypto.encodeRecoveryKey(key)
    }

    /**
     * Downloads the verifier another device already generated, checks
     * [recoveryCode] against it, and caches the key locally if it matches —
     * this is how a fresh install/new device unlocks an existing encrypted
     * backup. Duplicate backup folders and verifier files can exist from
     * past sessions (possibly for since-replaced keys), so every candidate
     * is tried and the code is accepted if it matches any of them — the
     * restore worker then decrypts whichever content this key actually
     * fits. Returns false for a wrong/malformed code; throws on a
     * network/Drive failure.
     */
    suspend fun unlockWithRecoveryKey(context: Context, token: String, recoveryCode: String): Boolean =
        withContext(Dispatchers.IO) {
            val key = BackupCrypto.decodeRecoveryKey(recoveryCode) ?: return@withContext false
            val drive = DriveRestClient(token)
            val candidates = drive.findFolders(FOLDER_NAME)
                .flatMap { folderId -> drive.findFiles(ENCRYPTION_FILE_NAME, folderId) }
            for (fileId in candidates) {
                val temp = File.createTempFile("ninja-scan-encryption", ".json", context.cacheDir)
                try {
                    drive.downloadTo(fileId, temp)
                    val verifier = runCatching {
                        Base64.getDecoder().decode(JSONObject(temp.readText()).getString("verifier"))
                    }.getOrNull() ?: continue // corrupt candidate: try the next one
                    if (BackupCrypto.verifyKey(key, verifier)) {
                        saveLocalKey(context, key)
                        return@withContext true
                    }
                } finally {
                    temp.delete()
                }
            }
            false
        }

    /**
     * Uploads this device's key verifier to Drive if no such file exists
     * yet, so a future restore-on-another-device can offer the unlock
     * prompt. Called from the backup worker once a local key is in hand.
     */
    internal fun ensureEncryptionMetadataUploaded(context: Context, drive: DriveRestClient, folderId: String) {
        if (drive.findFile(ENCRYPTION_FILE_NAME, folderId) != null) return
        val key = loadLocalKey(context) ?: return
        val verifier = Base64.getEncoder().encodeToString(BackupCrypto.verifier(key))
        val json = JSONObject().put("verifier", verifier)
        drive.uploadJson(ENCRYPTION_FILE_NAME, folderId, json.toString().toByteArray(Charsets.UTF_8), null)
    }

    // Android-Keystore AES key that wraps the real backup key at rest, so a
    // plaintext key is never sitting in SharedPreferences even though the
    // key itself is a random value rather than Keystore-generated.
    private const val KEYSTORE_ALIAS = "ninja_scan_backup_key_wrap"

    private fun keystoreWrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun wrapKey(raw: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreWrappingKey())
        val ciphertext = cipher.doFinal(raw)
        return cipher.iv + ciphertext
    }

    private fun unwrapKey(wrapped: ByteArray): ByteArray {
        val iv = wrapped.copyOfRange(0, 12)
        val ciphertext = wrapped.copyOfRange(12, wrapped.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keystoreWrappingKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    private const val KEY_BACKUP_KEY_WRAPPED = "backup_key_wrapped"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
