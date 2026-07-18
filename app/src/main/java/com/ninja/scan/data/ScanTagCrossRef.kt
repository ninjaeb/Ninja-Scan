package com.ninja.scan.data

import androidx.room.Entity

/** Join row linking a scanned document to an applied [DocumentTag] (many-to-many). */
@Entity(tableName = "scan_tag_cross_ref", primaryKeys = ["scanId", "tagId"])
data class ScanTagCrossRef(
    val scanId: Long,
    val tagId: Long,
)
