package com.ninja.scan.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A reusable label a business card can carry (many-to-many via [CardTagCrossRef]). */
@Entity(tableName = "tags")
data class Tag(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String = "",
    /** Hex color, e.g. "#E57373". */
    val color: String,
)
