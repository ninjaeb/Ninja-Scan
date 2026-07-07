package com.eugeneboon.docscanner.drive

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.eugeneboon.docscanner.DocScannerApp
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Uploads every scan without a Drive file id into the app's "Doc Scanner"
 * folder on Google Drive, then records the returned file id. Runs only when
 * backup is enabled and authorization was previously granted in the UI;
 * anything transient (network, 5xx) retries with backoff.
 */
class DriveBackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as DocScannerApp
        if (!DriveBackup.isEnabled(applicationContext)) return@withContext Result.success()

        val pending = app.repository.getPendingBackup()
        if (pending.isEmpty()) return@withContext Result.success()

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
            resolveFolder(drive)
        } catch (e: Exception) {
            return@withContext Result.retry()
        }

        var failures = 0
        for (scan in pending) {
            val pdf = File(scan.pdfPath)
            if (!pdf.exists()) continue
            try {
                val fileId = drive.uploadPdf(pdf, "${scan.title}.pdf", folderId)
                app.repository.markBackedUp(scan.id, fileId)
            } catch (e: Exception) {
                failures++
            }
        }
        if (failures > 0) Result.retry() else Result.success()
    }

    /** Finds or creates the backup folder, reusing a cached id when valid. */
    private fun resolveFolder(drive: DriveRestClient): String {
        DriveBackup.cachedFolderId(applicationContext)?.let { cached ->
            if (drive.folderExists(cached)) return cached
        }
        val id = drive.findFolder(DriveBackup.FOLDER_NAME)
            ?: drive.createFolder(DriveBackup.FOLDER_NAME)
        DriveBackup.setCachedFolderId(applicationContext, id)
        return id
    }
}

/** Minimal Drive v3 REST client — enough for folder lookup and PDF upload. */
private class DriveRestClient(private val token: String) {

    fun folderExists(folderId: String): Boolean =
        runCatching {
            request("GET", "https://www.googleapis.com/drive/v3/files/$folderId?fields=id,trashed")
                .let { !it.optBoolean("trashed", false) }
        }.getOrDefault(false)

    fun findFolder(name: String): String? {
        val query = URLEncoder.encode(
            "mimeType='application/vnd.google-apps.folder' and name='$name' and trashed=false",
            "UTF-8"
        )
        val response =
            request("GET", "https://www.googleapis.com/drive/v3/files?q=$query&fields=files(id)")
        val files = response.optJSONArray("files") ?: return null
        return if (files.length() > 0) files.getJSONObject(0).getString("id") else null
    }

    fun createFolder(name: String): String {
        val body = JSONObject()
            .put("name", name)
            .put("mimeType", "application/vnd.google-apps.folder")
        return request(
            "POST",
            "https://www.googleapis.com/drive/v3/files?fields=id",
            "application/json; charset=UTF-8",
        ) { it.write(body.toString().toByteArray()) }.getString("id")
    }

    fun uploadPdf(file: File, name: String, folderId: String): String {
        val boundary = "docscanner-${file.name.hashCode()}-${file.length()}"
        val metadata = JSONObject()
            .put("name", name)
            .put("parents", org.json.JSONArray().put(folderId))
        return request(
            "POST",
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id",
            "multipart/related; boundary=$boundary",
        ) { output ->
            output.write("--$boundary\r\n".toByteArray())
            output.write("Content-Type: application/json; charset=UTF-8\r\n\r\n".toByteArray())
            output.write(metadata.toString().toByteArray())
            output.write("\r\n--$boundary\r\n".toByteArray())
            output.write("Content-Type: application/pdf\r\n\r\n".toByteArray())
            file.inputStream().use { it.copyTo(output) }
            output.write("\r\n--$boundary--\r\n".toByteArray())
        }.getString("id")
    }

    private fun request(
        method: String,
        url: String,
        contentType: String? = null,
        writeBody: ((java.io.OutputStream) -> Unit)? = null,
    ): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            if (writeBody != null) {
                connection.doOutput = true
                contentType?.let { connection.setRequestProperty("Content-Type", it) }
                connection.outputStream.use(writeBody)
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
                throw IllegalStateException("Drive API $status: ${error?.take(200)}")
            }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }
}
