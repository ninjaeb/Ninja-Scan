package com.ninja.scan.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A reusable label a scanned document can carry (many-to-many via
 * [ScanTagCrossRef]). Kept as a separate catalog from Business Cards' [Tag]
 * — the two never share rows, even though the shape is identical.
 */
@Entity(tableName = "document_tags")
data class DocumentTag(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String = "",
    /** Hex color, e.g. "#E57373". */
    val color: String,
)
