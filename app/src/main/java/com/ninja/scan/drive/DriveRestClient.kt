package com.ninja.scan.drive

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

/** Thrown on 401 so workers can retry with a freshly authorized token. */
internal class DriveAuthException : Exception("Drive token rejected")

/** One file inside the backup folder, as listed by [DriveRestClient.listChildren]. */
internal data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val modifiedTime: Long,
)

/**
 * Minimal Drive v3 REST client shared by the backup and restore workers:
 * folder lookup, PDF/JSON upload, folder listing, and content download.
 */
internal class DriveRestClient(private val token: String) {

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

    /** Finds or creates the backup folder, reusing a cached id when valid. */
    fun resolveFolder(context: Context): String {
        DriveBackup.cachedFolderId(context)?.let { cached ->
            if (folderExists(cached)) return cached
        }
        val id = findFolder(DriveBackup.FOLDER_NAME) ?: createFolder(DriveBackup.FOLDER_NAME)
        DriveBackup.setCachedFolderId(context, id)
        return id
    }

    fun uploadPdf(file: File, name: String, folderId: String): String {
        val boundary = "docscanner-${file.name.hashCode()}-${file.length()}"
        val metadata = JSONObject()
            .put("name", name)
            .put("parents", JSONArray().put(folderId))
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

    /** Finds a file by exact name inside [folderId], or null. */
    fun findFile(name: String, folderId: String): String? {
        val query = URLEncoder.encode(
            "name='${name.replace("'", "\\'")}' and '$folderId' in parents and trashed=false",
            "UTF-8"
        )
        val response =
            request("GET", "https://www.googleapis.com/drive/v3/files?q=$query&fields=files(id)")
        val files = response.optJSONArray("files") ?: return null
        return if (files.length() > 0) files.getJSONObject(0).getString("id") else null
    }

    /** Creates or (when [existingFileId] is set) replaces a small JSON file. */
    fun uploadJson(name: String, folderId: String, bytes: ByteArray, existingFileId: String?): String {
        if (existingFileId != null) {
            request(
                "PATCH",
                "https://www.googleapis.com/upload/drive/v3/files/$existingFileId?uploadType=media&fields=id",
                "application/json; charset=UTF-8",
            ) { it.write(bytes) }
            return existingFileId
        }
        val boundary = "docscanner-manifest-${bytes.size}"
        val metadata = JSONObject()
            .put("name", name)
            .put("parents", JSONArray().put(folderId))
        return request(
            "POST",
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id",
            "multipart/related; boundary=$boundary",
        ) { output ->
            output.write("--$boundary\r\n".toByteArray())
            output.write("Content-Type: application/json; charset=UTF-8\r\n\r\n".toByteArray())
            output.write(metadata.toString().toByteArray())
            output.write("\r\n--$boundary\r\n".toByteArray())
            output.write("Content-Type: application/json; charset=UTF-8\r\n\r\n".toByteArray())
            output.write(bytes)
            output.write("\r\n--$boundary--\r\n".toByteArray())
        }.getString("id")
    }

    /** Lists all non-trashed children of [folderId], paginating as needed. */
    fun listChildren(folderId: String): List<DriveFile> {
        val results = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val query = URLEncoder.encode("'$folderId' in parents and trashed=false", "UTF-8")
            val url = buildString {
                append("https://www.googleapis.com/drive/v3/files?q=$query")
                append("&fields=nextPageToken,files(id,name,mimeType,modifiedTime)&pageSize=100")
                pageToken?.let { append("&pageToken=$it") }
            }
            val response = request("GET", url)
            val files = response.optJSONArray("files") ?: JSONArray()
            for (i in 0 until files.length()) {
                val file = files.getJSONObject(i)
                results.add(
                    DriveFile(
                        id = file.getString("id"),
                        name = file.optString("name"),
                        mimeType = file.optString("mimeType"),
                        modifiedTime = parseRfc3339(file.optString("modifiedTime")),
                    )
                )
            }
            pageToken = response.optString("nextPageToken").takeIf { it.isNotEmpty() }
        } while (pageToken != null)
        return results
    }

    /** Streams a file's content (`alt=media`) into [target]. */
    fun downloadTo(fileId: String, target: File) {
        val connection = URL("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            val status = connection.responseCode
            if (status == 401) throw DriveAuthException()
            if (status !in 200..299) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
                throw IllegalStateException("Drive API $status: ${error?.take(200)}")
            }
            connection.inputStream.use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Deletes a file the app created (e.g. a replaced backup copy). */
    fun deleteFile(fileId: String) {
        request("DELETE", "https://www.googleapis.com/drive/v3/files/$fileId")
    }

    private fun parseRfc3339(value: String): Long =
        runCatching { Instant.parse(value).toEpochMilli() }
            .getOrDefault(System.currentTimeMillis())

    private fun request(
        method: String,
        url: String,
        contentType: String? = null,
        writeBody: ((OutputStream) -> Unit)? = null,
    ): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            // HttpURLConnection has no PATCH verb; Google APIs accept the
            // standard method-override header on a POST instead.
            if (method == "PATCH") {
                connection.requestMethod = "POST"
                connection.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            } else {
                connection.requestMethod = method
            }
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            if (writeBody != null) {
                connection.doOutput = true
                contentType?.let { connection.setRequestProperty("Content-Type", it) }
                connection.outputStream.use(writeBody)
            }
            val status = connection.responseCode
            if (status == 401) throw DriveAuthException()
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
