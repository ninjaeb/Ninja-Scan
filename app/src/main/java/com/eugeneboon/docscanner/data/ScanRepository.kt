package com.eugeneboon.docscanner.data

import android.content.Context
import android.net.Uri
import com.eugeneboon.docscanner.util.ImageOptimizer
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScanRepository(
    private val context: Context,
    private val dao: ScanDao,
) {

    val scans: Flow<List<ScanDocument>> = dao.observeAll()

    private val scansDir: File
        get() = File(context.filesDir, "scans").apply { mkdirs() }

    /**
     * Persists a scanner result: builds a cloud-optimized PDF from the
     * full-resolution pages, writes a list thumbnail, and records the scan
     * in the library. Falls back to the scanner's own PDF if it is smaller
     * than the rebuilt one (e.g. single low-detail page).
     */
    suspend fun saveScan(result: GmsDocumentScanningResult): ScanDocument =
        withContext(Dispatchers.IO) {
            val timestamp = System.currentTimeMillis()
            val name = "Scan ${
                SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(Date(timestamp))
            }"
            val baseName = "scan_$timestamp"

            val pageUris = result.pages.orEmpty().map { it.imageUri }
            require(pageUris.isNotEmpty()) { "Scanner returned no pages" }

            val pdfFile = File(scansDir, "$baseName.pdf")
            val pageCount = ImageOptimizer.writeOptimizedPdf(context, pageUris, pdfFile)
            check(pageCount > 0) { "Could not decode any scanned page" }

            // The scanner also emits its own PDF; keep whichever is smaller.
            result.pdf?.uri?.let { scannerPdf ->
                val scannerSize = sizeOf(scannerPdf)
                if (scannerSize in 1 until pdfFile.length()) {
                    context.contentResolver.openInputStream(scannerPdf)?.use { input ->
                        pdfFile.outputStream().use { input.copyTo(it) }
                    }
                }
            }

            val thumbFile = File(scansDir, "$baseName.thumb.jpg")
            val hasThumb = ImageOptimizer.writeThumbnail(context, pageUris.first(), thumbFile)

            val scan = ScanDocument(
                title = name,
                createdAt = timestamp,
                pageCount = pageCount,
                pdfPath = pdfFile.absolutePath,
                thumbnailPath = if (hasThumb) thumbFile.absolutePath else null,
                sizeBytes = pdfFile.length(),
            )
            scan.copy(id = dao.insert(scan))
        }

    suspend fun rename(scan: ScanDocument, title: String) {
        val trimmed = title.trim()
        if (trimmed.isNotEmpty()) dao.update(scan.copy(title = trimmed))
    }

    suspend fun delete(scan: ScanDocument) = withContext(Dispatchers.IO) {
        File(scan.pdfPath).delete()
        scan.thumbnailPath?.let { File(it).delete() }
        dao.delete(scan)
    }

    /** Copies a scan's PDF to a user-chosen destination (SAF), e.g. Google Drive. */
    suspend fun exportTo(scan: ScanDocument, destination: Uri): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(destination)?.use { output ->
                    File(scan.pdfPath).inputStream().use { it.copyTo(output) }
                } ?: return@runCatching false
                true
            }.getOrDefault(false)
        }

    private fun sizeOf(uri: Uri): Long =
        runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        }.getOrDefault(-1L)
}
