package com.ninja.scan.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.ninja.scan.drive.BackupCrypto
import com.ninja.scan.drive.DriveBackup
import com.ninja.scan.drive.DriveFile
import com.ninja.scan.drive.DriveManifest
import com.ninja.scan.drive.DriveRestClient
import com.ninja.scan.util.CardParser
import com.ninja.scan.util.EditPage
import com.ninja.scan.util.ImageOptimizer
import com.ninja.scan.util.OcrLayout
import com.ninja.scan.util.PdfEditor
import com.ninja.scan.util.XlsxWriter
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScanRepository(
    private val context: Context,
    private val dao: ScanDao,
    private val cardDao: BusinessCardDao,
    private val folderDao: FolderDao,
    private val tagDao: TagDao,
) {

    private companion object {
        const val OCR_RENDER_DIMENSION_PX = 1600
        const val ORIGINAL_MAX_DIMENSION_PX = 4096
        const val ORIGINAL_JPEG_QUALITY = 92
        val CARD_COLUMNS = listOf(
            "Name", "Company", "Job Title", "Phone", "Email", "Website",
            "Address", "Notes", "Tags",
        )

        fun pageFileName(index: Int): String =
            "page-%03d.jpg".format(Locale.US, index + 1)
    }

    val scans: Flow<List<ScanDocument>> = dao.observeAll()

    fun search(query: String): Flow<List<ScanDocument>> = dao.search(query)

    suspend fun getPendingBackup(): List<ScanDocument> = dao.getPendingBackup()

    suspend fun markBackedUp(scanId: Long, driveFileId: String) =
        dao.setDriveFileId(scanId, driveFileId)

    suspend fun getBackedUpScans(): List<ScanDocument> = dao.getBackedUp()

    suspend fun getDriveFileIds(): List<String> = dao.getDriveFileIds()

    suspend fun getAllCards(): List<BusinessCard> = cardDao.getAll()

    suspend fun getPendingPhotoBackup(): List<BusinessCard> = cardDao.getPendingPhotoBackup()

    suspend fun markCardPhotoBackedUp(cardId: Long, driveFileId: String) =
        cardDao.setPhotoDriveFileId(cardId, driveFileId)

    val tags: Flow<List<Tag>> = tagDao.observeAll()

    suspend fun getTags(): List<Tag> = tagDao.getAll()

    suspend fun createTag(title: String, description: String, color: String): Tag {
        val id = tagDao.insert(Tag(title = title.trim(), description = description.trim(), color = color))
        enqueueBackupIfEnabled()
        return Tag(id, title.trim(), description.trim(), color)
    }

    suspend fun updateTag(tag: Tag) {
        tagDao.update(tag)
        enqueueBackupIfEnabled()
    }

    suspend fun deleteTag(tagId: Long) {
        tagDao.delete(tagId)
        enqueueBackupIfEnabled()
    }

    suspend fun getCardTags(cardId: Long): List<Tag> = tagDao.getTagsForCard(cardId)

    /** Matches the "+ Tag" popup's tap-to-toggle UX directly. */
    suspend fun toggleCardTag(cardId: Long, tagId: Long, currentlyApplied: Boolean) {
        if (currentlyApplied) tagDao.removeCardTag(cardId, tagId) else tagDao.addCardTag(CardTagCrossRef(cardId, tagId))
        enqueueBackupIfEnabled()
    }

    suspend fun getCardTagsByCard(): Map<Long, List<Tag>> =
        tagDao.getAllCardTagRows().groupBy({ it.cardId }, { Tag(it.tagId, it.title, "", it.color) })

    /**
     * Downloads one backed-up PDF into the library. Metadata comes from the
     * manifest [entry] when available; otherwise the filename and Drive
     * timestamp are used and OCR is re-run on-device. Returns true when a
     * scan row was inserted.
     */
    internal suspend fun restoreScanFromDrive(
        drive: DriveRestClient,
        file: DriveFile,
        entry: DriveManifest.ScanEntry?,
        localKey: ByteArray?,
    ): Boolean = withContext(Dispatchers.IO) {
        // Deterministic name: a retried restore simply overwrites a partial
        // download instead of duplicating it.
        val pdf = File(scansDir, "restored_${file.id}.pdf")
        drive.downloadTo(file.id, pdf)
        if (BackupCrypto.fileIsEncrypted(pdf)) {
            val key = localKey ?: run { pdf.delete(); return@withContext false }
            val decrypted = File(scansDir, "restored_${file.id}.decrypting.pdf")
            BackupCrypto.decryptFile(pdf, decrypted, key)
            decrypted.copyTo(pdf, overwrite = true)
            decrypted.delete()
        }
        if (PdfEditor.pageCount(pdf) == 0) {
            pdf.delete()
            return@withContext false
        }
        val thumb = File(scansDir, "restored_${file.id}.thumb.jpg")
        val hasThumb = PdfEditor.writeThumbnail(pdf, thumb)
        val scan = ScanDocument(
            title = entry?.title?.takeIf { it.isNotBlank() }
                ?: file.name.removeSuffix(".pdf"),
            createdAt = entry?.createdAt?.takeIf { it > 0 } ?: file.modifiedTime,
            pageCount = PdfEditor.pageCount(pdf).coerceAtLeast(1),
            pdfPath = pdf.absolutePath,
            thumbnailPath = if (hasThumb) thumb.absolutePath else null,
            sizeBytes = pdf.length(),
            ocrText = entry?.ocrText?.takeIf { it.isNotBlank() }
                ?: recognizeTextFromPdf(pdf),
            driveFileId = file.id,
            folder = entry?.folder,
            watermark = if (entry?.watermarkBaked == true) null else entry?.watermark,
            originalsDir = null,
            isIdCard = entry?.isIdCard ?: false,
        )
        dao.insert(scan)
        true
    }

    /**
     * Inserts manifest cards not already in the library, downloading each
     * card's backed-up photo (if any) alongside its text fields. Returns the
     * count of cards restored.
     */
    internal suspend fun restoreCards(
        drive: DriveRestClient,
        entries: List<DriveManifest.CardEntry>,
        catalogTags: List<DriveManifest.TagEntry> = emptyList(),
        localKey: ByteArray? = null,
    ): Int = withContext(Dispatchers.IO) {
        val existing = cardDao.getAll()
            .map { listOf(it.name, it.phone, it.email, it.createdAt.toString()) }
            .toSet()
        val cardsDir = File(context.filesDir, "cards").apply { mkdirs() }
        // Local color wins: the catalog only seeds a tag when no local tag with
        // that title (case-insensitively) exists yet.
        val tagsByTitle = tagDao.getAll()
            .associateByTo(mutableMapOf()) { it.title.lowercase() }
        suspend fun resolveTag(title: String): Tag {
            tagsByTitle[title.lowercase()]?.let { return it }
            val catalogEntry = catalogTags.find { it.title.equals(title, ignoreCase = true) }
            val id = tagDao.insert(
                Tag(
                    title = title,
                    description = catalogEntry?.description.orEmpty(),
                    color = catalogEntry?.color ?: "#9E9E9E",
                )
            )
            val tag = Tag(id, title, catalogEntry?.description.orEmpty(), catalogEntry?.color ?: "#9E9E9E")
            tagsByTitle[title.lowercase()] = tag
            return tag
        }
        var restored = 0
        for (entry in entries) {
            val card = entry.card
            val key = listOf(card.name, card.phone, card.email, card.createdAt.toString())
            if (key in existing) continue
            // One bad entry (a stray insert/tag-linking failure) must not
            // abort every remaining card — each is restored independently,
            // mirroring how the scan-restore loop isolates per-file failures.
            try {
                val thumbnailPath = card.photoDriveFileId?.let { fileId ->
                    runCatching {
                        val target = File(cardsDir, "restored_$fileId.jpg")
                        drive.downloadTo(fileId, target)
                        if (BackupCrypto.fileIsEncrypted(target)) {
                            val photoKey = localKey ?: error("photo is encrypted but no backup key is set up")
                            val decrypted = File(cardsDir, "restored_$fileId.decrypting.jpg")
                            BackupCrypto.decryptFile(target, decrypted, photoKey)
                            decrypted.copyTo(target, overwrite = true)
                            decrypted.delete()
                        }
                        target.absolutePath
                    }.getOrNull() // download/decrypt failure: skip the photo, keep the card record
                }
                val newId = cardDao.insert(card.copy(id = 0, thumbnailPath = thumbnailPath))
                for (tagTitle in entry.tagTitles) {
                    val tag = resolveTag(tagTitle)
                    tagDao.addCardTag(CardTagCrossRef(newId, tag.id))
                }
                restored++
            } catch (e: Exception) {
                // Skip this card; the rest of the restore continues.
            }
        }
        restored
    }

    suspend fun getScan(id: Long): ScanDocument? = dao.getById(id)

    val folders: Flow<List<String>> = folderDao.observeAll()

    /** Folder name -> hex color, so folder chips can match the tag chip look. */
    val folderColors: Flow<Map<String, String>> =
        folderDao.observeAllDetailed().map { list -> list.associate { it.name to it.color } }

    suspend fun moveToFolder(scan: ScanDocument, folder: String?) {
        val cleaned = folder?.trim()?.takeIf { it.isNotEmpty() }
        // Folders typed into the move/save dialogs become real folder rows.
        cleaned?.let { folderDao.insertNamed(it) }
        dao.setFolder(scan.id, cleaned)
        enqueueBackupIfEnabled()
    }

    suspend fun addFolder(name: String) {
        name.trim().takeIf { it.isNotEmpty() }?.let {
            folderDao.insertNamed(it)
            enqueueBackupIfEnabled()
        }
    }

    suspend fun renameFolder(oldName: String, newName: String) {
        val cleaned = newName.trim()
        if (cleaned.isEmpty() || cleaned == oldName) return
        folderDao.rename(oldName, cleaned)
        enqueueBackupIfEnabled()
    }

    suspend fun deleteFolder(name: String) {
        folderDao.delete(name)
        enqueueBackupIfEnabled()
    }

    suspend fun getFolderNames(): List<String> = folderDao.getAll()

    private fun enqueueBackupIfEnabled() {
        if (DriveBackup.isEnabled(context)) DriveBackup.enqueue(context)
    }

    /**
     * Updates the on-the-fly watermark text without touching the stored PDF
     * (it is stamped in at render/share/export time, per [applyPageEdits]).
     */
    suspend fun updateWatermark(scan: ScanDocument, watermark: String?): ScanDocument =
        withContext(Dispatchers.IO) {
            val cleaned = watermark?.trim()?.takeIf { it.isNotEmpty() }
            // The Drive copy is the clean PDF, so a watermark change only
            // needs a manifest refresh — the uploaded file stays valid.
            val updated = scan.copy(watermark = cleaned)
            dao.update(updated)
            if (cleaned != scan.watermark) enqueueBackupIfEnabled()
            updated
        }

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
        var originalsDirPath = scan.originalsDir
        if (pagesChanged) {
            val oldOriginals = scan.originalsDir?.let(::File)?.takeIf { it.isDirectory }

            // Prefer the untouched full-resolution originals as page sources
            // so repeated edits don't degrade quality.
            val effectivePages = pages.map { page ->
                if (page is EditPage.FromPdf) {
                    val original = oldOriginals
                        ?.let { File(it, pageFileName(page.index)) }
                        ?.takeIf { it.exists() }
                    if (original != null) {
                        EditPage.FromImage(Uri.fromFile(original), page.rotation)
                    } else {
                        page
                    }
                } else {
                    page
                }
            }

            val rebuilt = File(scansDir, "${source.nameWithoutExtension}.rebuild.pdf")
            pageCount = PdfEditor.rebuildPdf(context, source, effectivePages, null, rebuilt)
            check(pageCount > 0) { "Could not rebuild the document" }

            // Regenerate the originals to mirror the new page composition
            // before swapping the PDF (sources reference the old files).
            val newOriginals = rebuildOriginals(pages, oldOriginals, source)

            if (!rebuilt.renameTo(source)) {
                rebuilt.copyTo(source, overwrite = true)
                rebuilt.delete()
            }
            if (newOriginals != null) {
                oldOriginals?.deleteRecursively()
                val finalDir = File(scansDir, "originals/${source.nameWithoutExtension}")
                finalDir.deleteRecursively()
                originalsDirPath =
                    if (newOriginals.renameTo(finalDir)) finalDir.absolutePath
                    else newOriginals.absolutePath
            }

            val thumbPath = scan.thumbnailPath
                ?: File(scansDir, "${source.nameWithoutExtension}.thumb.jpg").absolutePath
            if (PdfEditor.writeThumbnail(source, File(thumbPath))) thumbnailPath = thumbPath
            ocrText = recognizeTextFromPdf(source)
        }

        // Only content changes invalidate the Drive copy (the clean PDF is
        // what's uploaded); a watermark-only change just refreshes the
        // manifest. The replaced Drive file is deleted on the next backup.
        if (pagesChanged) scan.driveFileId?.let { DriveBackup.addStaleFileId(context, it) }
        val updated = scan.copy(
            pageCount = pageCount,
            sizeBytes = source.length(),
            thumbnailPath = thumbnailPath,
            ocrText = ocrText,
            watermark = cleanedWatermark,
            originalsDir = originalsDirPath,
            driveFileId = if (pagesChanged) null else scan.driveFileId,
        )
        dao.update(updated)
        if (pagesChanged || cleanedWatermark != scan.watermark) enqueueBackupIfEnabled()
        updated
    }

    /**
     * Writes a fresh originals directory matching the edited page order.
     * Unrotated pages are copied byte-for-byte; rotated or missing sources
     * are re-encoded at high resolution.
     */
    private fun rebuildOriginals(
        pages: List<EditPage>,
        oldOriginals: File?,
        sourcePdf: File,
    ): File? = runCatching {
        val newDir = File(scansDir, "originals/${sourcePdf.nameWithoutExtension}.new")
        newDir.deleteRecursively()
        newDir.mkdirs()
        pages.forEachIndexed { index, page ->
            val target = File(newDir, pageFileName(index))
            runCatching {
                when (page) {
                    is EditPage.FromPdf -> {
                        val original = oldOriginals
                            ?.let { File(it, pageFileName(page.index)) }
                            ?.takeIf { it.exists() }
                        when {
                            original != null && page.rotation % 360 == 0 ->
                                original.copyTo(target, overwrite = true)
                            original != null ->
                                writeRotatedJpeg(Uri.fromFile(original), page.rotation, target)
                            else -> {
                                val bitmap = PdfEditor.renderPageFromFile(
                                    sourcePdf, page.index, ORIGINAL_MAX_DIMENSION_PX,
                                    page.rotation,
                                )
                                if (bitmap != null) {
                                    FileOutputStream(target).use {
                                        bitmap.compress(
                                            Bitmap.CompressFormat.JPEG,
                                            ORIGINAL_JPEG_QUALITY, it,
                                        )
                                    }
                                    bitmap.recycle()
                                }
                            }
                        }
                    }
                    is EditPage.FromImage -> {
                        if (page.rotation % 360 == 0) {
                            context.contentResolver.openInputStream(page.uri)?.use { input ->
                                target.outputStream().use { input.copyTo(it) }
                            }
                        } else {
                            writeRotatedJpeg(page.uri, page.rotation, target)
                        }
                    }
                }
            }
        }
        newDir
    }.getOrNull()

    private fun writeRotatedJpeg(uri: Uri, rotation: Int, target: File) {
        val bitmap = ImageOptimizer.decodeImage(context, uri, ORIGINAL_MAX_DIMENSION_PX) ?: return
        val rotated = PdfEditor.rotate(bitmap, rotation)
        FileOutputStream(target).use {
            rotated.compress(Bitmap.CompressFormat.JPEG, ORIGINAL_JPEG_QUALITY, it)
        }
        rotated.recycle()
    }

    private val shareDir: File
        get() = File(context.cacheDir, "share").apply { mkdirs() }

    private fun shareBaseName(scan: ScanDocument): String {
        val sanitized =
            scan.title.replace(Regex("[^A-Za-z0-9 ._-]"), "_").ifBlank { "scan-${scan.id}" }
        return "$sanitized-scan-with-Ninja-Scan-App"
    }

    /** PDF shares/exports are branded as a filename prefix instead of a suffix. */
    private fun pdfShareBaseName(scan: ScanDocument): String {
        val sanitized =
            scan.title.replace(Regex("[^A-Za-z0-9 ._-]"), "_").ifBlank { "scan-${scan.id}" }
        return "By-Ninja-Scan-App-$sanitized"
    }

    /**
     * Returns the PDF to hand to other apps: a named copy of the stored file
     * (watermarked when set) so shared/exported files carry a recognizable
     * name instead of the internal storage filename.
     */
    suspend fun preparePdfForSharing(scan: ScanDocument): File = withContext(Dispatchers.IO) {
        val source = File(scan.pdfPath)
        val target = File(shareDir, "${pdfShareBaseName(scan)}.pdf")
        val watermark = scan.watermark?.takeIf { it.isNotBlank() }
        if (watermark != null) {
            check(
                PdfEditor.writeWatermarkedCopy(context, source, watermark, target, scan.isIdCard) > 0
            ) { "Could not prepare the document" }
        } else {
            source.copyTo(target, overwrite = true)
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
                isIdCard = scan.isIdCard,
            )
        }

    /** Builds a single PDF from just the given (0-based) page indices, in order. */
    suspend fun preparePagesPdf(scan: ScanDocument, pageIndices: List<Int>): File =
        withContext(Dispatchers.IO) {
            val source = File(scan.pdfPath)
            val target = File(shareDir, "${pdfShareBaseName(scan)}-selected.pdf")
            val pages = pageIndices.map { EditPage.FromPdf(it) }
            check(
                PdfEditor.rebuildPdf(context, source, pages, scan.watermark, target, scan.isIdCard) > 0
            ) { "Could not prepare the document" }
            target
        }

    /** Renders just the given (0-based) page indices (watermarked if set) as shareable JPEGs. */
    suspend fun preparePageImages(scan: ScanDocument, pageIndices: List<Int>): List<File> =
        withContext(Dispatchers.IO) {
            PdfEditor.renderPagesAsJpegs(
                File(scan.pdfPath),
                scan.watermark,
                shareDir,
                shareBaseName(scan),
                pageIndices,
                scan.isIdCard,
            )
        }

    /**
     * Saves the scan's pages (watermarked if set) as JPEGs into the device's
     * Pictures/Ninja Scan gallery folder. Returns how many pages were saved.
     */
    suspend fun saveImagesToDevice(scan: ScanDocument): Int = withContext(Dispatchers.IO) {
        preparePageImages(scan).count { file ->
            val uri = insertGalleryImage(file) ?: return@count false
            context.contentResolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { it.copyTo(output) }
            } != null
        }
    }

    /**
     * Below API 29 (scoped storage), writing to the public Pictures folder
     * needs WRITE_EXTERNAL_STORAGE and an explicit file path — a real but
     * vanishingly rare case, so we degrade to "nothing saved" rather than
     * build a runtime permission-request flow for it.
     */
    private fun insertGalleryImage(file: File): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/${DriveBackup.FOLDER_NAME}",
                )
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    DriveBackup.FOLDER_NAME,
                )
                dir.mkdirs()
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, File(dir, file.name).absolutePath)
            }
        }
        return context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    }

    /** Stitches all pages into one tall shareable JPEG ("long image"). */
    suspend fun prepareLongImage(scan: ScanDocument): File = withContext(Dispatchers.IO) {
        val target = File(shareDir, "${shareBaseName(scan)}-long.jpg")
        check(
            PdfEditor.writeLongImage(
                File(scan.pdfPath), scan.watermark, target, isIdCard = scan.isIdCard,
            ) > 0
        ) { "Could not prepare the document" }
        target
    }

    /** Stitches just the given (0-based) page indices into one tall shareable JPEG. */
    suspend fun preparePagesLongImage(scan: ScanDocument, pageIndices: List<Int>): File =
        withContext(Dispatchers.IO) {
            val target = File(shareDir, "${shareBaseName(scan)}-selected-long.jpg")
            check(
                PdfEditor.writeLongImage(
                    File(scan.pdfPath), scan.watermark, target, pageIndices, scan.isIdCard,
                ) > 0
            ) { "Could not prepare the document" }
            target
        }

    /** Exports every page as its own single-page PDF (watermarked if set). */
    suspend fun prepareSeparatePdfs(scan: ScanDocument): List<File> =
        withContext(Dispatchers.IO) {
            val source = File(scan.pdfPath)
            (0 until PdfEditor.pageCount(source)).mapNotNull { index ->
                val target = File(shareDir, "${pdfShareBaseName(scan)}-p${index + 1}.pdf")
                val pages = listOf<EditPage>(EditPage.FromPdf(index))
                if (PdfEditor.rebuildPdf(context, source, pages, scan.watermark, target, scan.isIdCard) > 0) {
                    target
                } else {
                    null
                }
            }
        }

    /** Exports just the given (0-based) page indices, each as its own single-page PDF. */
    suspend fun preparePagesSeparatePdfs(scan: ScanDocument, pageIndices: List<Int>): List<File> =
        withContext(Dispatchers.IO) {
            val source = File(scan.pdfPath)
            pageIndices.mapNotNull { index ->
                val target = File(shareDir, "${pdfShareBaseName(scan)}-p${index + 1}.pdf")
                val pages = listOf<EditPage>(EditPage.FromPdf(index))
                if (PdfEditor.rebuildPdf(context, source, pages, scan.watermark, target, scan.isIdCard) > 0) {
                    target
                } else {
                    null
                }
            }
        }

    // ---------------------------------------------------------------------
    // Business cards
    // ---------------------------------------------------------------------

    val cards: Flow<List<BusinessCard>> = cardDao.observeAll()

    /** OCRs a scanned card image and extracts contact fields, best-effort. */
    suspend fun parseCardImage(imageUri: Uri): BusinessCard = withContext(Dispatchers.IO) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val ocrLines = try {
            val result = recognizer.process(InputImage.fromFilePath(context, imageUri)).await()
            OcrLayout.buildOcrLines(result)
        } catch (e: Exception) {
            emptyList()
        } finally {
            recognizer.close()
        }
        val cardsDir = File(context.filesDir, "cards").apply { mkdirs() }
        val thumb = File(cardsDir, "card_${System.currentTimeMillis()}.jpg")
        val hasThumb = ImageOptimizer.writeThumbnail(context, imageUri, thumb)
        CardParser.parse(ocrLines).copy(
            createdAt = System.currentTimeMillis(),
            thumbnailPath = if (hasThumb) thumb.absolutePath else null,
        )
    }

    suspend fun saveCard(card: BusinessCard): BusinessCard {
        val saved = if (card.id == 0L) {
            card.copy(id = cardDao.insert(card))
        } else {
            card.also { cardDao.update(it) }
        }
        enqueueBackupIfEnabled()
        return saved
    }

    suspend fun deleteCard(card: BusinessCard) {
        card.thumbnailPath?.let { File(it).delete() }
        card.photoDriveFileId?.let { DriveBackup.addStaleFileId(context, it) }
        cardDao.delete(card)
        enqueueBackupIfEnabled()
    }

    suspend fun deleteCards(cards: List<BusinessCard>) {
        if (cards.isEmpty()) return
        for (card in cards) {
            card.thumbnailPath?.let { File(it).delete() }
            card.photoDriveFileId?.let { DriveBackup.addStaleFileId(context, it) }
            cardDao.delete(card)
        }
        enqueueBackupIfEnabled() // one enqueue for the whole batch, not N
    }

    /** Writes all cards as CSV to a user-chosen SAF destination. */
    suspend fun exportCardsCsv(cards: List<BusinessCard>, destination: Uri): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val tagsByCard = getCardTagsByCard()
                context.contentResolver.openOutputStream(destination)?.use { output ->
                    output.write(buildCsv(cards, tagsByCard).toByteArray(Charsets.UTF_8))
                } != null
            }.getOrDefault(false)
        }

    /** Writes all cards as an Excel workbook to a SAF destination. */
    suspend fun exportCardsXlsx(cards: List<BusinessCard>, destination: Uri): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val tagsByCard = getCardTagsByCard()
                context.contentResolver.openOutputStream(destination)?.use { output ->
                    XlsxWriter.write(output, "Contacts", CARD_COLUMNS, cards.map { cardRow(it, tagsByCard) })
                } != null
            }.getOrDefault(false)
        }

    private fun buildCsv(cards: List<BusinessCard>, tagsByCard: Map<Long, List<Tag>>): String = buildString {
        fun quote(value: String) = "\"" + value.replace("\"", "\"\"") + "\""
        append(CARD_COLUMNS.joinToString(",") { quote(it) }).append("\r\n")
        for (card in cards) {
            append(cardRow(card, tagsByCard).joinToString(",") { quote(it) }).append("\r\n")
        }
    }

    private fun cardRow(card: BusinessCard, tagsByCard: Map<Long, List<Tag>>): List<String> = listOf(
        card.name, card.company, card.jobTitle, card.phone,
        card.email, card.website, card.address, card.notes,
        tagsByCard[card.id].orEmpty().joinToString(", ") { it.title },
    )

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

            // Keep the untouched full-resolution captures so pages can be
            // re-enhanced or re-edited later without quality loss.
            val originalsDir = File(scansDir, "originals/$baseName").apply { mkdirs() }
            pageUris.forEachIndexed { index, uri ->
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        File(originalsDir, pageFileName(index)).outputStream()
                            .use { input.copyTo(it) }
                    }
                }
            }

            val scan = ScanDocument(
                title = name,
                createdAt = timestamp,
                pageCount = pageCount,
                pdfPath = pdfFile.absolutePath,
                thumbnailPath = if (hasThumb) thumbFile.absolutePath else null,
                sizeBytes = pdfFile.length(),
                ocrText = recognizeText(pageUris),
                originalsDir = originalsDir.absolutePath,
            )
            val saved = scan.copy(id = dao.insert(scan))
            enqueueBackupIfEnabled()
            saved
        }

    /**
     * Persists an ID card scan: front and back captures are composited onto
     * one printable A4 page (front on top, back below) instead of one PDF
     * page per side, matching how a physical ID copy is usually printed and
     * submitted. A single-sided capture (back cancelled) still saves fine.
     */
    suspend fun saveIdCardScan(result: GmsDocumentScanningResult): ScanDocument =
        withContext(Dispatchers.IO) {
            val timestamp = System.currentTimeMillis()
            val name = "ID card ${
                SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(Date(timestamp))
            }"
            val baseName = "idcard_$timestamp"

            val pageUris = result.pages.orEmpty().map { it.imageUri }
            require(pageUris.isNotEmpty()) { "Scanner returned no pages" }
            val frontUri = pageUris[0]
            val backUri = pageUris.getOrNull(1)

            val pdfFile = File(scansDir, "$baseName.pdf")
            val wrote = ImageOptimizer.writeIdCardPdf(context, frontUri, backUri, pdfFile)
            check(wrote) { "Could not decode the scanned ID card" }

            val thumbFile = File(scansDir, "$baseName.thumb.jpg")
            val hasThumb = ImageOptimizer.writeThumbnail(context, frontUri, thumbFile)

            // Keep the untouched front/back captures so they can be re-scanned
            // or re-composited later without quality loss.
            val originalsDir = File(scansDir, "originals/$baseName").apply { mkdirs() }
            pageUris.forEachIndexed { index, uri ->
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        File(originalsDir, pageFileName(index)).outputStream()
                            .use { input.copyTo(it) }
                    }
                }
            }

            val scan = ScanDocument(
                title = name,
                createdAt = timestamp,
                pageCount = 1,
                pdfPath = pdfFile.absolutePath,
                thumbnailPath = if (hasThumb) thumbFile.absolutePath else null,
                sizeBytes = pdfFile.length(),
                ocrText = recognizeText(pageUris),
                originalsDir = originalsDir.absolutePath,
                isIdCard = true,
            )
            val saved = scan.copy(id = dao.insert(scan))
            enqueueBackupIfEnabled()
            saved
        }

    /**
     * Rebuilds two already-saved single scans as one ID-card-formatted page
     * — front on top, back below, each at true card size — for scans that
     * were captured as regular Documents but are actually a card's two
     * sides. Leaves both source scans untouched; adds the composited page
     * as a new scan alongside them.
     */
    suspend fun convertToIdCard(front: ScanDocument, back: ScanDocument): ScanDocument =
        withContext(Dispatchers.IO) {
            val frontUri = sourcePageUri(front) ?: error("Could not read the front image")
            val backUri = sourcePageUri(back) ?: error("Could not read the back image")

            val timestamp = System.currentTimeMillis()
            val name = "ID card ${
                SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(Date(timestamp))
            }"
            val baseName = "idcard_$timestamp"

            val pdfFile = File(scansDir, "$baseName.pdf")
            val wrote = ImageOptimizer.writeIdCardPdf(context, frontUri, backUri, pdfFile)
            check(wrote) { "Could not build the ID card page" }

            val thumbFile = File(scansDir, "$baseName.thumb.jpg")
            val hasThumb = ImageOptimizer.writeThumbnail(context, frontUri, thumbFile)

            val originalsDir = File(scansDir, "originals/$baseName").apply { mkdirs() }
            listOf(frontUri, backUri).forEachIndexed { index, uri ->
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        File(originalsDir, pageFileName(index)).outputStream()
                            .use { input.copyTo(it) }
                    }
                }
            }

            val scan = ScanDocument(
                title = name,
                createdAt = timestamp,
                pageCount = 1,
                pdfPath = pdfFile.absolutePath,
                thumbnailPath = if (hasThumb) thumbFile.absolutePath else null,
                sizeBytes = pdfFile.length(),
                ocrText = recognizeText(listOf(frontUri, backUri)),
                originalsDir = originalsDir.absolutePath,
                isIdCard = true,
            )
            val saved = scan.copy(id = dao.insert(scan))
            enqueueBackupIfEnabled()
            saved
        }

    /**
     * The best available single photo standing in for one side of [scan]:
     * its first kept original capture if there is one, otherwise its first
     * PDF page rendered fresh — covers imported PDFs and any other scan
     * with no per-page originals kept on disk.
     */
    private fun sourcePageUri(scan: ScanDocument): Uri? {
        scan.originalsDir?.let { dir ->
            val file = File(dir, pageFileName(0))
            if (file.exists()) return Uri.fromFile(file)
        }
        val pdfFile = File(scan.pdfPath)
        if (!pdfFile.exists()) return null
        val rendered = PdfEditor.renderPageFromFile(pdfFile, 0, ORIGINAL_MAX_DIMENSION_PX, 0)
            ?: return null
        val tempFile = File(context.cacheDir, "idcard_src_${scan.id}_${System.currentTimeMillis()}.jpg")
        FileOutputStream(tempFile).use {
            rendered.compress(Bitmap.CompressFormat.JPEG, ORIGINAL_JPEG_QUALITY, it)
        }
        rendered.recycle()
        return Uri.fromFile(tempFile)
    }

    /**
     * Imports an existing PDF picked from device storage as-is: copied into
     * the library unchanged (no re-optimization, since it wasn't captured by
     * the scanner), with a thumbnail and OCR text generated so it's
     * searchable and browsable like any other scan.
     */
    suspend fun importPdf(uri: Uri): ScanDocument = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis()
        val baseName = "import_$timestamp"
        val pdfFile = File(scansDir, "$baseName.pdf")
        context.contentResolver.openInputStream(uri)?.use { input ->
            pdfFile.outputStream().use { input.copyTo(it) }
        } ?: error("Could not read the selected PDF")

        val pageCount = PdfEditor.pageCount(pdfFile)
        if (pageCount <= 0) {
            pdfFile.delete()
        }
        check(pageCount > 0) { "That file isn't a valid PDF" }

        val thumbFile = File(scansDir, "$baseName.thumb.jpg")
        val hasThumb = PdfEditor.writeThumbnail(pdfFile, thumbFile)

        val scan = ScanDocument(
            title = displayNameOf(uri)?.removeSuffix(".pdf")
                ?: "Imported ${SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(Date(timestamp))}",
            createdAt = timestamp,
            pageCount = pageCount,
            pdfPath = pdfFile.absolutePath,
            thumbnailPath = if (hasThumb) thumbFile.absolutePath else null,
            sizeBytes = pdfFile.length(),
            ocrText = recognizeTextFromPdf(pdfFile),
            originalsDir = null,
        )
        val saved = scan.copy(id = dao.insert(scan))
        enqueueBackupIfEnabled()
        saved
    }

    /**
     * Builds a scan directly from picked gallery images — the same
     * optimize-and-assemble pipeline the camera scanner's pages go through,
     * just skipping the live capture (no perspective crop is applied, since
     * these aren't fresh photos of a document edge).
     */
    suspend fun saveImportedImages(uris: List<Uri>): ScanDocument = withContext(Dispatchers.IO) {
        require(uris.isNotEmpty()) { "No images selected" }
        val timestamp = System.currentTimeMillis()
        val name = "Scan ${
            SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(Date(timestamp))
        }"
        val baseName = "import_$timestamp"

        val pdfFile = File(scansDir, "$baseName.pdf")
        val pageCount = ImageOptimizer.writeOptimizedPdf(context, uris, pdfFile)
        check(pageCount > 0) { "Could not decode any selected image" }

        val thumbFile = File(scansDir, "$baseName.thumb.jpg")
        val hasThumb = ImageOptimizer.writeThumbnail(context, uris.first(), thumbFile)

        // Keep the untouched originals so pages can be re-enhanced or
        // re-edited later without quality loss, same as camera-scanned pages.
        val originalsDir = File(scansDir, "originals/$baseName").apply { mkdirs() }
        uris.forEachIndexed { index, uri ->
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    File(originalsDir, pageFileName(index)).outputStream()
                        .use { input.copyTo(it) }
                }
            }
        }

        val scan = ScanDocument(
            title = name,
            createdAt = timestamp,
            pageCount = pageCount,
            pdfPath = pdfFile.absolutePath,
            thumbnailPath = if (hasThumb) thumbFile.absolutePath else null,
            sizeBytes = pdfFile.length(),
            ocrText = recognizeText(uris),
            originalsDir = originalsDir.absolutePath,
        )
        val saved = scan.copy(id = dao.insert(scan))
        enqueueBackupIfEnabled()
        saved
    }

    /** Reads the display name of a content [uri] (e.g. the original file name), if available. */
    private fun displayNameOf(uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
            }
        }.getOrNull()

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
        if (trimmed.isNotEmpty()) {
            dao.update(scan.copy(title = trimmed))
            enqueueBackupIfEnabled()
        }
    }

    private fun deleteScanFiles(scan: ScanDocument) {
        File(scan.pdfPath).delete()
        scan.thumbnailPath?.let { File(it).delete() }
        scan.originalsDir?.let { File(it).deleteRecursively() }
        // Deleted locally means deleted from the backup too; otherwise the
        // next restore would resurrect it.
        scan.driveFileId?.let { DriveBackup.addStaleFileId(context, it) }
    }

    suspend fun delete(scan: ScanDocument) = withContext(Dispatchers.IO) {
        deleteScanFiles(scan)
        dao.delete(scan)
        enqueueBackupIfEnabled()
    }

    suspend fun deleteScans(scans: List<ScanDocument>) = withContext(Dispatchers.IO) {
        if (scans.isEmpty()) return@withContext
        scans.forEach(::deleteScanFiles)
        dao.delete(scans)
        enqueueBackupIfEnabled() // one enqueue for the whole batch, not N
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
