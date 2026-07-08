package com.ninja.scan.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A library folder. Kept as its own table so empty folders can exist —
 * scans reference folders by name via [ScanDocument.folder].
 */
@Entity(tableName = "folders")
data class Folder(
    @PrimaryKey val name: String,
    val color: String = "",
)
