package com.ninja.scan.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanDao {

    @Query("SELECT * FROM scans ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ScanDocument>>

    @Query(
        "SELECT * FROM scans WHERE title LIKE '%' || :query || '%' " +
            "OR ocrText LIKE '%' || :query || '%' ORDER BY createdAt DESC"
    )
    fun search(query: String): Flow<List<ScanDocument>>

    @Query("SELECT * FROM scans WHERE id = :id")
    suspend fun getById(id: Long): ScanDocument?

    @Query("SELECT * FROM scans WHERE driveFileId IS NULL ORDER BY createdAt ASC")
    suspend fun getPendingBackup(): List<ScanDocument>

    @Query("SELECT DISTINCT folder FROM scans WHERE folder IS NOT NULL ORDER BY folder")
    fun observeFolders(): Flow<List<String>>

    @Query("UPDATE scans SET folder = :folder WHERE id = :id")
    suspend fun setFolder(id: Long, folder: String?)

    @Query("UPDATE scans SET driveFileId = :fileId WHERE id = :id")
    suspend fun setDriveFileId(id: Long, fileId: String)

    @Insert
    suspend fun insert(scan: ScanDocument): Long

    @Update
    suspend fun update(scan: ScanDocument)

    @Delete
    suspend fun delete(scan: ScanDocument)
}
