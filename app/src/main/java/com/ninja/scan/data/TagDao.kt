package com.ninja.scan.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Flat card/tag join projection, grouped by the repository into a per-card map. */
data class CardTagRow(val cardId: Long, val tagId: Long, val title: String, val color: String)

@Dao
interface TagDao {

    @Query("SELECT * FROM tags ORDER BY title")
    fun observeAll(): Flow<List<Tag>>

    @Query("SELECT * FROM tags ORDER BY title")
    suspend fun getAll(): List<Tag>

    @Insert
    suspend fun insert(tag: Tag): Long

    @Update
    suspend fun update(tag: Tag)

    @Query("DELETE FROM card_tag_cross_ref WHERE tagId = :tagId")
    suspend fun removeCrossRefsForTag(tagId: Long)

    @Query("DELETE FROM tags WHERE id = :tagId")
    suspend fun deleteTagRow(tagId: Long)

    @Transaction
    suspend fun delete(tagId: Long) {
        removeCrossRefsForTag(tagId)
        deleteTagRow(tagId)
    }

    @Query(
        "SELECT t.* FROM tags t INNER JOIN card_tag_cross_ref c ON c.tagId = t.id " +
            "WHERE c.cardId = :cardId ORDER BY t.title"
    )
    suspend fun getTagsForCard(cardId: Long): List<Tag>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addCardTag(ref: CardTagCrossRef)

    @Query("DELETE FROM card_tag_cross_ref WHERE cardId = :cardId AND tagId = :tagId")
    suspend fun removeCardTag(cardId: Long, tagId: Long)

    /** One query for the cards list + CSV export; grouped by cardId in Kotlin. */
    @Query(
        "SELECT c.cardId, c.tagId, t.title, t.color FROM card_tag_cross_ref c " +
            "INNER JOIN tags t ON t.id = c.tagId"
    )
    suspend fun getAllCardTagRows(): List<CardTagRow>
}
