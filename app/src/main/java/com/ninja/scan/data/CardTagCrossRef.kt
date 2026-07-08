package com.ninja.scan.data

import androidx.room.Entity

/** Join row linking a business card to an applied [Tag] (many-to-many). */
@Entity(tableName = "card_tag_cross_ref", primaryKeys = ["cardId", "tagId"])
data class CardTagCrossRef(
    val cardId: Long,
    val tagId: Long,
)
