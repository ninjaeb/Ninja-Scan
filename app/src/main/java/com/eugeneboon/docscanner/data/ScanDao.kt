package com.eugeneboon.docscanner.data

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

    @Query("SELECT * FROM scans WHERE id = :id")
    suspend fun getById(id: Long): ScanDocument?

    @Insert
    suspend fun insert(scan: ScanDocument): Long

    @Update
    suspend fun update(scan: ScanDocument)

    @Delete
    suspend fun delete(scan: ScanDocument)
}
