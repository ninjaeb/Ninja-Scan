package com.eugeneboon.docscanner.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A scanned document stored on disk. [pdfPath] points to the cloud-optimized
 * PDF and [thumbnailPath] to a small JPEG of the first page used in the list UI.
 * [ocrText] holds the text recognized on all pages, used for full-text search.
 */
@Entity(tableName = "scans")
data class ScanDocument(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val pageCount: Int,
    val pdfPath: String,
    val thumbnailPath: String?,
    val sizeBytes: Long,
    val ocrText: String = "",
    /** Google Drive file id once the PDF has been backed up, null otherwise. */
    val driveFileId: String? = null,
    /** Optional folder name used to organize the library; null = unfiled. */
    val folder: String? = null,
)
