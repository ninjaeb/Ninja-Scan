package com.ninja.scan.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A contact extracted from a scanned business card. */
@Entity(tableName = "business_cards")
data class BusinessCard(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    val company: String = "",
    val jobTitle: String = "",
    val phone: String = "",
    val email: String = "",
    val website: String = "",
    val address: String = "",
    /** Free-form notes added by the user. */
    val notes: String = "",
    /** Comma-separated tags for grouping contacts (e.g. "supplier, kl"). */
    val tags: String = "",
    val createdAt: Long = 0,
    /** Small JPEG of the scanned card shown in the list. */
    val thumbnailPath: String? = null,
)
