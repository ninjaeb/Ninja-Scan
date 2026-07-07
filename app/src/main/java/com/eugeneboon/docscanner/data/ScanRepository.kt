package com.eugeneboon.docscanner.data

import android.content.Context
import android.net.Uri
import com.eugeneboon.docscanner.util.EditPage
import com.eugeneboon.docscanner.util.ImageOptimizer
import com.eugeneboon.docscanner.util.PdfEditor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScanRepository(
    private val context: Context,
    private val dao: ScanDao,
) {

    private companion object {
        const val OCR_RENDER_DIMENSION_PX = 1600
    }

    val scans: Flow<List<ScanDocument>> = dao.observeAll()

    fun search(query: String): Flow<List<ScanDocument>> = dao.search(query)

    suspend fun getPendingBackup(): List<ScanDocument> = dao.getPendingBackup()

    suspend fun markBackedUp(scanId: Long, driveFileId: String) =
        dao.setDriveFileId(scanId, driveFileId)

    suspend fun getScan(id: Long): ScanDocument? = dao.getById(id)

    val folders: Flow<List<String>> = dao.observeFolders()

    suspend fun moveToFolder(scan: ScanDocument, folder: String?) =
        dao.setFolder(scan.id, folder?.trim()?.takeIf { it.isNotEmpty() })

    /**
     * Replaces a scan's PDF with a rebuilt version from the editor: pages
     * reordered/rotated/removed/added. The watermark is NOT baked into the
     * stored PDF — it is saved on the scan and stamped on the fly when the
     * document is viewed, shared, exported, or uploaded, so it stays
     * editable and removable. Refreshes the thumbnail and OCR text, and
     * clears the Drive file id so the next backup uploads the new version.
     */
    suspend fun applyPageEdits(
        scanId: Long,
        pages: List<EditPage>,
        watermark: String?,
    ): ScanDocument = withContext(Dispatchers.IO) {
        val scan = requireNotNull(dao.getById(scanId)) { "Scan not found" }
        require(pages.isNotEmpty()) { "Document must keep at least one page" }
        val cleanedWatermark = watermark?.trim()?.takeIf { it.isNotEmpty() }

        val source = File(scan.pdfPath)
        val pagesChanged =
            pages != List(PdfEditor.pageCount(source)) { EditPage.FromPdf(it) }

        var pageCount = scan.pageCount
        var ocrText = scan.ocrText
        var thumbnailPath = scan.thumbnailPath
        if (pagesChanged) {
            val rebuilt = File(scansDir, "${source.nameWithoutExtension}.rebuild.pdf")
            pageCount = PdfEditor.rebuildPdf(context, source, pages, null, rebuilt)
            check(pageCount > 0) { "Could not rebuild the document" }
            if (!rebuilt.renameTo(source)) {
                rebuilt.copyTo(source, overwrite = true)
                rebuilt.delete()
            }
            val thumbPath = scan.thumbnailPath
                ?: File(scansDir, "${source.nameWithoutExtension}.thumb.jpg").absolutePath
            if (PdfEditor.writeThumbnail(source, File(thumbPath))) thumbnailPath = thumbPath
            ocrText = recognizeTextFromPdf(source)
        }

        val updated = scan.copy(
            pageCount = pageCount,
            sizeBytes = source.length(),
            thumbnailPath = thumbnailPath,
            ocrText = ocrText,
            watermark = cleanedWatermark,
            driveFileId = if (pagesChanged || cleanedWatermark != scan.watermark) {
                null
            } else {
                scan.driveFileId
            },
        )
        dao.update(updated)
        updated
    }

    private val shareDir: File
        get() = File(context.cacheDir, "share").apply { mkdirs() }

    private fun shareBaseName(scan: ScanDocument): String =
        scan.title.replace(Regex("[^A-Za-z0-9 ._-]"), "_").ifBlank { "scan-${scan.id}" }

    /**
     * Returns the PDF to hand to other apps: the stored file as-is when the
     * scan has no watermark, otherwise a watermarked copy in the cache.
     */
    suspend fun preparePdfForSharing(scan: ScanDocument): File = withContext(Dispatchers.IO) {
        val source = File(scan.pdfPath)
        val watermark = scan.watermark?.takeIf { it.isNotBlank() }
            ?: return@withContext source
        val target = File(shareDir, "${shareBaseName(scan)}.pdf")
        check(PdfEditor.writeWatermarkedCopy(context, source, watermark, target) > 0) {
            "Could not prepare the document"
        }
        target
    }

    /** Renders the scan's pages (watermarked if set) as shareable JPEGs. */
    suspend fun preparePageImages(scan: ScanDocument): List<File> =
        withContext(Dispatchers.IO) {
            PdfEditor.renderPagesAsJpegs(
                File(scan.pdfPath),
                scan.watermark,
                shareDir,
                shareBaseName(scan),
            )
        }

    /** Re-runs on-device OCR over a rebuilt PDF, page by page. Best-effort. */
    private suspend fun recognizeTextFromPdf(pdf: File): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val texts = mutableListOf<String>()
            for (index in 0 until PdfEditor.pageCount(pdf)) {
                val bitmap =
                    PdfEditor.renderPageFromFile(pdf, index, OCR_RENDER_DIMENSION_PX, 0)
                        ?: continue
                runCatching {
                    recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text
                }.getOrNull()?.takeIf { it.isNotBlank() }?.let(texts::add)
                bitmap.recycle()
            }
            texts.joinToString("\n\n")
        } catch (e: Exception) {
            ""
        } finally {
            recognizer.close()
        }
    }

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
                ocrText = recognizeText(pageUris),
            )
            scan.copy(id = dao.insert(scan))
        }

    /**
     * Runs on-device text recognition over every page so scans are full-text
     * searchable. Best-effort: pages that fail to process contribute nothing.
     */
    private suspend fun recognizeText(pageUris: List<Uri>): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            pageUris.mapNotNull { uri ->
                runCatching {
                    recognizer.process(InputImage.fromFilePath(context, uri))
                        .await().text.takeIf { it.isNotBlank() }
                }.getOrNull()
            }.joinToString("\n\n")
        } catch (e: Exception) {
            ""
        } finally {
            recognizer.close()
        }
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

    /** Copies a scan's PDF (watermarked if set) to a SAF destination. */
    suspend fun exportTo(scan: ScanDocument, destination: Uri): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val source = preparePdfForSharing(scan)
                context.contentResolver.openOutputStream(destination)?.use { output ->
                    source.inputStream().use { it.copyTo(output) }
                } ?: return@runCatching false
                true
            }.getOrDefault(false)
        }

    private fun sizeOf(uri: Uri): Long =
        runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        }.getOrDefault(-1L)
}
