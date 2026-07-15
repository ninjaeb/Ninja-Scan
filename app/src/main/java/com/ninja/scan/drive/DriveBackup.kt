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

    /** Small JSON file in the backup folder carrying the PBKDF2 salt and a
     * password verifier — never the key itself — so a different device can
     * re-derive the same key from the same password to unlock a restore. */
    const val ENCRYPTION_FILE_NAME = "ninja-scan-encryption.json"

    /** What's needed before backup/restore can proceed on this device. */
    sealed class KeyRequirement {
        /** A usable key is already cached locally. */
        object Ready : KeyRequirement()
        /** No key locally and none set up anywhere yet — ask for a new password. */
        object NeedsSetup : KeyRequirement()
        /** A password was set up on another device — ask for it to unlock here. */
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

    /** Observes the backup work's state for progress UI. */
    fun backupWorkInfo(context: Context): Flow<List<WorkInfo>> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK_NAME)

    // --- Backup encryption key -------------------------------------------
    //
    // The AES key that encrypts everything uploaded is derived from a user
    // password (see BackupCrypto) and never uploaded itself. What IS cached
    // here, once derived, is the raw key bytes — wrapped by an
    // Android-Keystore key that can't leave this device, so a plaintext key
    // is never sitting in SharedPreferences. The salt/verifier needed to
    // re-derive the same key from the same password on another device live
    // in Drive as ENCRYPTION_FILE_NAME instead, since they aren't secret on
    // their own (a verifier is useless without the password).

    fun hasLocalKey(context: Context): Boolean = prefs(context).contains(KEY_BACKUP_KEY_WRAPPED)

    /** The cached raw AES key, or null if this device hasn't set up/unlocked one yet. */
    fun loadLocalKey(context: Context): ByteArray? {
        val wrapped = prefs(context).getString(KEY_BACKUP_KEY_WRAPPED, null) ?: return null
        return runCatching { unwrapKey(Base64.getDecoder().decode(wrapped)) }.getOrNull()
    }

    private fun saveLocalKey(context: Context, raw: ByteArray, salt: ByteArray, verifier: ByteArray) {
        val encoder = Base64.getEncoder()
        prefs(context).edit {
            putString(KEY_BACKUP_KEY_WRAPPED, encoder.encodeToString(wrapKey(raw)))
            putString(KEY_BACKUP_SALT, encoder.encodeToString(salt))
            putString(KEY_BACKUP_VERIFIER, encoder.encodeToString(verifier))
        }
    }

    /**
     * Checks whether this device already has a usable key, and if not,
     * whether a password was already set up (on this or another device) by
     * looking for [ENCRYPTION_FILE_NAME] in the backup folder.
     */
    suspend fun resolveKeyRequirement(context: Context, token: String): KeyRequirement =
        withContext(Dispatchers.IO) {
            if (hasLocalKey(context)) return@withContext KeyRequirement.Ready
            val drive = DriveRestClient(token)
            val folderId = runCatching { drive.resolveFolder(context) }.getOrNull()
                ?: return@withContext KeyRequirement.NeedsSetup
            val exists = runCatching { drive.findFile(ENCRYPTION_FILE_NAME, folderId) }.getOrNull() != null
            if (exists) KeyRequirement.NeedsUnlock else KeyRequirement.NeedsSetup
        }

    /** First-time setup: derives a new key from [password] and caches it locally. */
    suspend fun setupPassword(context: Context, password: CharArray): Unit =
        withContext(Dispatchers.Default) {
            val salt = BackupCrypto.randomSalt()
            val key = BackupCrypto.deriveKey(password, salt)
            val verifier = BackupCrypto.verifier(key)
            saveLocalKey(context, key, salt, verifier)
        }

    /**
     * Downloads the salt/verifier another device already set up, re-derives
     * the key from [password], and caches it locally if it matches — this is
     * how a fresh install/new device unlocks an existing encrypted backup.
     * Returns false on a wrong password; throws on a network/Drive failure.
     */
    suspend fun unlockWithPassword(context: Context, token: String, password: CharArray): Boolean =
        withContext(Dispatchers.IO) {
            val drive = DriveRestClient(token)
            val folderId = drive.resolveFolder(context)
            val fileId = drive.findFile(ENCRYPTION_FILE_NAME, folderId) ?: return@withContext false
            val temp = File.createTempFile("ninja-scan-encryption", ".json", context.cacheDir)
            try {
                drive.downloadTo(fileId, temp)
                val json = JSONObject(temp.readText())
                val decoder = Base64.getDecoder()
                val salt = decoder.decode(json.getString("salt"))
                val verifier = decoder.decode(json.getString("verifier"))
                val iterations = json.optInt("iterations", BackupCrypto.PBKDF2_ITERATIONS)
                val key = withContext(Dispatchers.Default) { BackupCrypto.deriveKey(password, salt, iterations) }
                if (!BackupCrypto.verifyPassword(key, verifier)) return@withContext false
                saveLocalKey(context, key, salt, verifier)
                true
            } finally {
                temp.delete()
            }
        }

    /**
     * Uploads this device's salt/verifier to Drive if no such file exists
     * yet, so a future restore-on-another-device can offer the unlock
     * prompt. Called from the backup worker once a local key is in hand.
     */
    fun ensureEncryptionMetadataUploaded(context: Context, drive: DriveRestClient, folderId: String) {
        if (drive.findFile(ENCRYPTION_FILE_NAME, folderId) != null) return
        // Already base64 — saveLocalKey encoded them before caching locally.
        val salt = prefs(context).getString(KEY_BACKUP_SALT, null) ?: return
        val verifier = prefs(context).getString(KEY_BACKUP_VERIFIER, null) ?: return
        val json = JSONObject()
            .put("salt", salt)
            .put("verifier", verifier)
            .put("iterations", BackupCrypto.PBKDF2_ITERATIONS)
        drive.uploadJson(ENCRYPTION_FILE_NAME, folderId, json.toString().toByteArray(Charsets.UTF_8), null)
    }

    // Android-Keystore AES key that wraps the real backup key at rest, so a
    // plaintext key is never sitting in SharedPreferences even though the
    // key itself came from a user password rather than the Keystore.
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
    private const val KEY_BACKUP_SALT = "backup_key_salt"
    private const val KEY_BACKUP_VERIFIER = "backup_key_verifier"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
