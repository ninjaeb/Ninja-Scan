package com.ninja.scan.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Flat scan/tag join projection, grouped by the repository into a per-scan map. */
data class ScanTagRow(val scanId: Long, val tagId: Long, val title: String, val color: String)

@Dao
interface ScanTagDao {

    @Query("SELECT * FROM document_tags ORDER BY title")
    fun observeAll(): Flow<List<DocumentTag>>

    @Query("SELECT * FROM document_tags ORDER BY title")
    suspend fun getAll(): List<DocumentTag>

    @Insert
    suspend fun insert(tag: DocumentTag): Long

    @Update
    suspend fun update(tag: DocumentTag)

    @Query("DELETE FROM scan_tag_cross_ref WHERE tagId = :tagId")
    suspend fun removeCrossRefsForTag(tagId: Long)

    @Query("DELETE FROM document_tags WHERE id = :tagId")
    suspend fun deleteTagRow(tagId: Long)

    @Transaction
    suspend fun delete(tagId: Long) {
        removeCrossRefsForTag(tagId)
        deleteTagRow(tagId)
    }

    @Query(
        "SELECT t.* FROM document_tags t INNER JOIN scan_tag_cross_ref c ON c.tagId = t.id " +
            "WHERE c.scanId = :scanId ORDER BY t.title"
    )
    suspend fun getTagsForScan(scanId: Long): List<DocumentTag>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addScanTag(ref: ScanTagCrossRef)

    @Query("DELETE FROM scan_tag_cross_ref WHERE scanId = :scanId AND tagId = :tagId")
    suspend fun removeScanTag(scanId: Long, tagId: Long)

    /** One query for the documents list; grouped by scanId in Kotlin. */
    @Query(
        "SELECT c.scanId, c.tagId, t.title, t.color FROM scan_tag_cross_ref c " +
            "INNER JOIN document_tags t ON t.id = c.tagId"
    )
    suspend fun getAllScanTagRows(): List<ScanTagRow>

    /** Reactive version so the Documents list's tag filter/chips stay live. */
    @Query(
        "SELECT c.scanId, c.tagId, t.title, t.color FROM scan_tag_cross_ref c " +
            "INNER JOIN document_tags t ON t.id = c.tagId"
    )
    fun observeAllScanTagRows(): Flow<List<ScanTagRow>>
}
